// 서울시 교통정보(TOPIS, 서울 열린데이터광장 Open API) 중계: 실시간 도로 소통·돌발·교통량.
// 키는 SEOUL_SUBWAY_KEY (서울 열린데이터광장 인증키, 지하철 실시간과 같은 키). 응답은 잠시 저장해 모두가 나눠 씀.
//  GET /w/topis/traffic?links=id,id,…  링크별 실시간 속도·통행시간 {id: [km/h, 초]} (60개까지, 5분 저장. 한 번에 새로 묻는 것은 40개까지)
//  GET /w/topis/acc                    실시간 돌발(사고·공사·행사 등) [{id, type, sub, link, lat, lng, info, from, to}] (2분 저장)
//  GET /w/topis/spots                  교통량 지점 [{no, name, lat, lng}] (하루 저장)
//  GET /w/topis/vol?spot=&ymd=&hh=     지점·시각별 교통량 {in, out, lanes} (지난 시각은 하루, 이번 시각은 10분 저장)
//  GET /w/topis/status                 확인용: 서울 API 에 닿는지

const BASE = "http://openapi.seoul.go.kr:8088/";
const HEAD = { "Access-Control-Allow-Origin": "*", "Content-Type": "application/json; charset=utf-8" };

// ── 좌표: GRS80 TM 중부원점(EPSG:5181) → 위경도 (pipeline/seoul_tm.py 와 같은 계산) ──
const A = 6378137, F = 1 / 298.257222101, E2 = 2 * F - F * F, EP2 = E2 / (1 - E2);
const LAT0 = (38 * Math.PI) / 180, LON0 = (127 * Math.PI) / 180, K0 = 1, FE = 200000, FN = 500000;
function mArc(phi) {
  const e4 = E2 * E2, e6 = e4 * E2;
  return A * ((1 - E2 / 4 - (3 * e4) / 64 - (5 * e6) / 256) * phi - ((3 * E2) / 8 + (3 * e4) / 32 + (45 * e6) / 1024) * Math.sin(2 * phi)
    + ((15 * e4) / 256 + (45 * e6) / 1024) * Math.sin(4 * phi) - ((35 * e6) / 3072) * Math.sin(6 * phi));
}
/** (TM X, TM Y) → [위도, 경도] */
export function tmToWgs84(x, y) {
  const e4 = E2 * E2, e6 = e4 * E2;
  const mu = (mArc(LAT0) + (y - FN) / K0) / (A * (1 - E2 / 4 - (3 * e4) / 64 - (5 * e6) / 256));
  const e1 = (1 - Math.sqrt(1 - E2)) / (1 + Math.sqrt(1 - E2));
  const phi1 = mu + ((3 * e1) / 2 - (27 * e1 ** 3) / 32) * Math.sin(2 * mu) + ((21 * e1 ** 2) / 16 - (55 * e1 ** 4) / 32) * Math.sin(4 * mu)
    + ((151 * e1 ** 3) / 96) * Math.sin(6 * mu) + ((1097 * e1 ** 4) / 512) * Math.sin(8 * mu);
  const s = Math.sin(phi1), c = Math.cos(phi1), t = Math.tan(phi1);
  const c1 = EP2 * c * c, t1 = t * t, n1 = A / Math.sqrt(1 - E2 * s * s), r1 = (A * (1 - E2)) / (1 - E2 * s * s) ** 1.5;
  const d = (x - FE) / (n1 * K0);
  const lat = phi1 - ((n1 * t) / r1) * ((d * d) / 2 - ((5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d ** 4) / 24
    + ((61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d ** 6) / 720);
  const lon = LON0 + (d - ((1 + 2 * t1 + c1) * d ** 3) / 6 + ((5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d ** 5) / 120) / c;
  return [(lat * 180) / Math.PI, (lon * 180) / Math.PI];
}

/** XML 글자 참조 풀기 (돌발 내용의 &#13; 줄바꿈 등) */
const unescapeXml = (v) => v.replace(/&#13;|&#10;/g, "\n").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, "&");
/** <row> 마다 {태그(소문자): 값} */
export function rows(xml) {
  return [...String(xml).matchAll(/<row>([\s\S]*?)<\/row>/g)].map((m) => {
    const o = {};
    for (const [, k, v] of m[1].matchAll(/<(\w+)>([^<]*)<\/\1>/g)) o[k.toLowerCase()] = unescapeXml(v).trim();
    return o;
  });
}
const okXml = (x) => /<list_total_count>|INFO-200/.test(x) && !/ERROR-|INFO-100/.test(x);

/** 서울 API 하나 (키는 주소에) */
function seoulUrl(key, svc, a, b, ...rest) {
  return BASE + [encodeURIComponent(key), "xml", svc, a, b, ...rest.map((x) => encodeURIComponent(x))].join("/");
}

/** 저장해 둔 것이 있으면 그것, 없으면 받아서 ttl 초 저장 (make 가 null 이면 저장 안 함) */
async function memo(ctx, key, ttl, make) {
  const cache = caches.default;
  const req = new Request("https://cache.tagohub/seoul/" + key);
  const hit = await cache.match(req);
  if (hit) return hit.json();
  const v = await make();
  if (v != null) ctx.waitUntil(cache.put(req, new Response(JSON.stringify(v), { headers: { "Content-Type": "application/json", "Cache-Control": `public, max-age=${ttl}` } })));
  return v;
}

async function getXml(fetcher, url) {
  const r = await fetcher(url);
  return r.ok ? r.text() : "";
}

/** 링크별 실시간 속도 (저장해 둔 것 + 새로 묻는 것 40개까지) */
export async function trafficFor(ids, key, ctx, fetcher = fetch) {
  const out = {}, cache = caches.default;
  let asked = 0;
  await Promise.all(ids.map(async (id) => {
    const req = new Request("https://cache.tagohub/seoul/traffic/" + id);
    const hit = await cache.match(req);
    if (hit) { const v = await hit.json(); if (v) out[id] = v; return; }
    if (asked >= 40) return; // 나머지는 다음에 (서버 한 요청당 바깥 요청 수 제한)
    asked++;
    try {
      const x = await getXml(fetcher, seoulUrl(key, "TrafficInfo", 1, 5, id));
      const r = rows(x)[0];
      const v = r && r.prcs_spd != null ? [Math.round(+r.prcs_spd), Math.round(+r.prcs_trv_time || 0)] : null;
      if (v) out[id] = v;
      if (okXml(x)) ctx.waitUntil(cache.put(req, new Response(JSON.stringify(v), { headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=300" } })));
    } catch (_) { /* 이번엔 없음 */ }
  }));
  return out;
}

/** 돌발 유형 코드 → 이름 (하루 저장) */
async function accCodes(key, ctx, fetcher) {
  return memo(ctx, "acccodes", 86400, async () => {
    const [m, s] = await Promise.all([getXml(fetcher, seoulUrl(key, "AccMainCode", 1, 100)), getXml(fetcher, seoulUrl(key, "AccSubCode", 1, 500))]);
    if (!okXml(m)) return null;
    const main = {}, sub = {};
    for (const r of rows(m)) main[r.acc_type] = r.acc_type_nm;
    for (const r of rows(s)) sub[r.acc_dtype] = r.acc_dtype_nm;
    return { main, sub };
  });
}

/** 실시간 돌발 목록 */
export async function accidents(key, ctx, fetcher = fetch) {
  return memo(ctx, "acc", 120, async () => {
    const [x, codes] = await Promise.all([getXml(fetcher, seoulUrl(key, "AccInfo", 1, 1000)), accCodes(key, ctx, fetcher).catch(() => null)]);
    if (!okXml(x)) return null;
    return rows(x).map((r) => {
      const [lat, lng] = tmToWgs84(+r.grs80tm_x, +r.grs80tm_y);
      const when = (d, t) => (d && /^\d{8}$/.test(d) ? `${d.slice(0, 4)}-${d.slice(4, 6)}-${d.slice(6, 8)}${t && /^\d{4}/.test(t) ? " " + t.slice(0, 2) + ":" + t.slice(2, 4) : ""}` : null);
      return {
        id: r.acc_id, type: codes?.main?.[r.acc_type] || r.acc_type || null, sub: codes?.sub?.[r.acc_dtype] || r.acc_dtype || null,
        link: r.link_id || null, lat: Math.round(lat * 1e6) / 1e6, lng: Math.round(lng * 1e6) / 1e6, info: r.acc_info || "",
        from: when(r.occr_date, r.occr_time), to: when(r.exp_clr_date, r.exp_clr_time),
      };
    }).filter((a) => a.lat > 37 && a.lat < 38 && a.lng > 126.5 && a.lng < 127.5);
  });
}

/** 교통량 지점 */
export async function spots(key, ctx, fetcher = fetch) {
  return memo(ctx, "spots", 86400, async () => {
    const x = await getXml(fetcher, seoulUrl(key, "SpotInfo", 1, 1000));
    if (!okXml(x)) return null;
    return rows(x).map((r) => {
      const [lat, lng] = tmToWgs84(+r.grs80tm_x, +r.grs80tm_y);
      return { no: r.spot_num, name: r.spot_nm, lat: Math.round(lat * 1e6) / 1e6, lng: Math.round(lng * 1e6) / 1e6 };
    });
  });
}

/** 지점·시각별 교통량: 들어옴·나감 합과 차로별 */
export async function volume(key, spot, ymd, hh, ctx, fetcher = fetch, now = new Date()) {
  const kstNow = new Date(now.getTime() + 9 * 3600 * 1000);
  const current = ymd === kstNow.toISOString().slice(0, 10).replace(/-/g, "") && +hh === kstNow.getUTCHours();
  return memo(ctx, `vol/${spot}/${ymd}/${hh}`, current ? 600 : 86400, async () => {
    const x = await getXml(fetcher, seoulUrl(key, "VolInfo", 1, 500, spot, ymd, hh));
    if (!okXml(x)) return null;
    const out = { in: 0, out: 0, lanes: {} };
    for (const r of rows(x)) {
      const v = +r.vol || 0, io = r.io_type === "1" ? "in" : "out";
      out[io] += v;
      const k = io + (r.lane_num || "");
      out.lanes[k] = (out.lanes[k] || 0) + v;
    }
    return out;
  });
}

/** /w/topis/… */
export async function handleSeoulTraffic(url, env, ctx, fetcher = fetch) {
  const key = (env.SEOUL_SUBWAY_KEY || "").trim();
  const json = (v, maxAge) => new Response(JSON.stringify(v), { headers: { ...HEAD, "Cache-Control": `public, max-age=${maxAge}` } });
  if (!key) return new Response(JSON.stringify({ error: "no key" }), { status: 503, headers: HEAD });
  const op = url.pathname.slice("/w/topis/".length);
  try {
    if (op === "traffic") {
      const ids = [...new Set((url.searchParams.get("links") || "").split(",").filter((s) => /^\d{6,12}$/.test(s)))].slice(0, 60);
      return json(ids.length ? await trafficFor(ids, key, ctx, fetcher) : {}, 60);
    }
    if (op === "acc") { const v = await accidents(key, ctx, fetcher); return v ? json(v, 60) : json([], 30); }
    if (op === "spots") { const v = await spots(key, ctx, fetcher); return v ? json(v, 3600) : json([], 60); }
    if (op === "vol") {
      const spot = url.searchParams.get("spot") || "", ymd = url.searchParams.get("ymd") || "", hh = (url.searchParams.get("hh") || "").padStart(2, "0");
      if (!/^[A-Z]-\d{2}$/.test(spot) || !/^\d{8}$/.test(ymd) || !/^\d{2}$/.test(hh) || +hh > 23) return new Response("bad request", { status: 400, headers: HEAD });
      const v = await volume(key, spot, ymd, hh, ctx, fetcher);
      return v ? json(v, 300) : json(null, 60);
    }
    if (op === "status") {
      const x = await getXml(fetcher, seoulUrl(key, "RoadDivInfo", 1, 5)).catch((e) => "ERR " + (e && e.message));
      return json({ reach: okXml(x), sample: okXml(x) ? rows(x).length : String(x).replace(key, "").slice(0, 120) }, 0);
    }
  } catch (e) {
    return new Response(JSON.stringify({ error: String(e && e.message || e).replace(key, "") }), { status: 502, headers: HEAD });
  }
  return new Response("not found", { status: 404, headers: HEAD });
}
