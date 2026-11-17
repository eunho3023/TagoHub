// 서울시 교통정보(TOPIS, 서울 열린데이터광장 Open API) 중계: 실시간 도로 소통·돌발. (교통량 지점은 쓰는 사람이 적어 뺐음)
// 키는 SEOUL_SUBWAY_KEY (서울 열린데이터광장 인증키, 지하철 실시간과 같은 키). 응답은 잠시 저장해 모두가 나눠 씀.
//  GET /w/topis/traffic?links=id,id,…  링크별 실시간 속도·통행시간 {id: [km/h, 초]} (30개까지, 묶음마다 5분 저장)
//  GET /w/topis/acc                    실시간 돌발(사고·공사·행사 등) [{id, type, sub, link, lat, lng, info, from, to}] (2분 저장)
//  GET /w/topis/bike                   공영자전거 대여소 실시간 [{id, name, lat, lng, bikes, racks}] (1분 저장)
//                                      서울 따릉이(서울 키, 거치대 수 있음) + 그 밖 10곳(한국지역정보개발원 전국 공영자전거, 공공데이터 키)
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

/** 링크 속도 저장 시간 (초). 하나라도 못 받았으면 짧게 */
const SPEED_TTL = 300, SPEED_TTL_PART = 60;
/** 한 요청에 묻는 링크 수 (Workers 무료 요금제는 한 요청의 바깥 요청이 50번까지: 저장소 읽기·쓰기 포함) */
export const SPEED_MAX = 30;
/**
 * 링크별 실시간 속도 {id: [km/h, 초]}. 웹은 링크를 지역별 30개 묶음으로 나눠 여러 묶음을 동시에 물음 →
 * 묶음(요청한 링크 목록)마다 따로 저장해 동시에 와도 서로 덮어쓰지 않음 (읽기 1번 + 서울 API 30번 + 쓰기 1번)
 */
export async function trafficFor(ids, key, ctx, fetcher = fetch) {
  const ask = [...new Set(ids)].slice(0, SPEED_MAX).sort();
  if (!ask.length) return {};
  const cache = caches.default;
  const req = new Request("https://cache.tagohub/seoul/traffic/g/" + ask.join("-"));
  try { const hit = await cache.match(req); if (hit) return (await hit.json()) || {}; } catch (_) { /* 다시 받음 */ }
  const out = {};
  let got = 0;
  await Promise.all(ask.map(async (id) => {
    try {
      const x = await getXml(fetcher, seoulUrl(key, "TrafficInfo", 1, 5, id));
      if (!okXml(x)) return;
      got++;
      const r = rows(x)[0];
      if (r && r.prcs_spd != null) out[id] = [Math.round(+r.prcs_spd), Math.round(+r.prcs_trv_time || 0)];
    } catch (_) { /* 이번엔 없음 */ }
  }));
  if (got) {
    const ttl = got === ask.length ? SPEED_TTL : SPEED_TTL_PART;
    ctx.waitUntil(cache.put(req, new Response(JSON.stringify(out), { headers: { "Content-Type": "application/json", "Cache-Control": `public, max-age=${ttl}` } })));
  }
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

/**
 * 따릉이 한 번에 받는 대여소 수 (서울 API 한도 1,000) · 몇 쪽 (2026-11 대여소 2,741곳).
 * list_total_count 는 전체가 아니라 그 쪽의 개수라 쪽 수를 미리 알 수 없음 → 4쪽을 한꺼번에 묻고 빈 쪽은 버림
 */
const BIKE_PAGE = 1000, BIKE_PAGES = 4;
/** 대여소 이름 앞 번호 떼기 ("102. 망원역 1번출구 앞" → "망원역 1번출구 앞") */
export const bikeName = (n) => String(n || "").replace(/^\s*\d+\s*[.)]\s*/, "").trim();

/** 서울 공공자전거(따릉이) 실시간 대여정보 (bikeList, JSON). 모든 대여소를 받아 1분 저장 */
export async function bikes(key, ctx, fetcher = fetch) {
  return memo(ctx, "bike", 60, async () => {
    const page = async (i) => {
      const a = i * BIKE_PAGE + 1;
      const r = await fetcher(BASE + [encodeURIComponent(key), "json", "bikeList", a, a + BIKE_PAGE - 1].join("/") + "/");
      if (!r.ok) return null;
      const j = await r.json().catch(() => null);
      return j?.rentBikeStatus || null;
    };
    const pages = await Promise.all(Array.from({ length: BIKE_PAGES }, (_, i) => page(i).catch(() => null)));
    // 첫 쪽을 못 받으면 저장하지 않음 (다음에 다시 물음)
    if (!Array.isArray(pages[0]?.row)) return null;
    return pages.flatMap((p) => (Array.isArray(p?.row) ? p.row : [])).map((r) => ({
      id: r.stationId, name: bikeName(r.stationName),
      lat: Math.round(+r.stationLatitude * 1e6) / 1e6, lng: Math.round(+r.stationLongitude * 1e6) / 1e6,
      bikes: +r.parkingBikeTotCnt || 0, racks: +r.rackTotCnt || 0,
    })).filter((b) => b.id && b.lat > 37 && b.lat < 38 && b.lng > 126.5 && b.lng < 127.5);
  });
}

/** 전국 공영자전거: 한 번에 받는 수 (1,000 넘게 물으면 K10 오류) · 최대 쪽 수 (2026-11 전체 5,667곳) */
const KR_PAGE = 1000, KR_PAGES = 10;
const KR_BASE = "https://apis.data.go.kr/B551982/pbdo_v2/inf_101_00010002_v2";
/** 서울은 따릉이 자체 자료(거치대 수 있음)를 써서 전국 자료에서는 뺌 */
const SEOUL_CD = "1100000000";

/**
 * 한국지역정보개발원 "전국 공영자전거 실시간 정보" 대여소별 대여 가능 대수 (서울 밖 10곳: 대전 타슈·세종 어울링·창원 누비자 등).
 * 지역 코드 없이 물으면 전부 → 첫 쪽에서 전체 수를 보고 나머지 쪽을 한꺼번에. 대여소 ID 가 지역마다 겹칠 수 있어 "지역코드:ID".
 * 서울 따릉이 원본과 같은 순간 값이 470/477곳 같음 (2026-11-17, 나머지는 1대 차이) → 실시간. 누가 볼 때만 물음(1분 저장)
 */
export async function nationalBikes(rawKey, ctx, fetcher = fetch) {
  const k = rawKey.trim(), key = k.includes("%") ? k : encodeURIComponent(k);
  return memo(ctx, "bike-kr", 60, async () => {
    const page = async (n) => {
      const r = await fetcher(`${KR_BASE}?serviceKey=${key}&pageNo=${n}&numOfRows=${KR_PAGE}&type=JSON`);
      if (!r.ok) return null;
      const j = await r.json().catch(() => null);
      return j?.header?.resultCode === "K0" ? j.body : null;
    };
    const first = await page(1);
    if (!first) return null;
    const pages = Math.min(KR_PAGES, Math.ceil((+first.totalCount || 0) / KR_PAGE));
    const rest = await Promise.all(Array.from({ length: Math.max(0, pages - 1) }, (_, i) => page(i + 2).catch(() => null)));
    return [first, ...rest].flatMap((b) => (Array.isArray(b?.item) ? b.item : [])).filter((r) => r.lcgvmnInstCd !== SEOUL_CD && r.rntstnId).map((r) => ({
      id: `${r.lcgvmnInstCd}:${r.rntstnId}`, name: bikeName(r.rntstnNm).replace(/_/g, " "),
      lat: Math.round(+r.lat * 1e6) / 1e6, lng: Math.round(+r.lot * 1e6) / 1e6, bikes: +r.bcyclTpkctNocs || 0, racks: 0,
    })).filter((b) => b.lat > 33 && b.lat < 39 && b.lng > 124 && b.lng < 132);
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
    if (op === "bike") {
      // 서울 따릉이 + 전국(서울 밖). 한쪽이 실패해도 받은 쪽은 보여 줌 (둘 다 실패면 빈 목록을 짧게)
      const dg = (env.DATA_GO_KR_KEY || "").trim();
      const [seoul, kr] = await Promise.all([bikes(key, ctx, fetcher).catch(() => null), dg ? nationalBikes(dg, ctx, fetcher).catch(() => null) : null]);
      const v = [...(seoul || []), ...(kr || [])];
      return json(v, seoul && (kr || !dg) ? 60 : 30);
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
