// 노선 도로 모양 (웹·PC 버전용). 정류장만 잇는 직선 대신 길을 따라 그리도록, 서버가 한 번 만들어 D1 에 저장하고 모두에게 나눠 준다.
// 폰 앱 RoadShapeClient·Shapes(OSRM 구간 요청, 정류장 사이 블록을 한 바퀴 도는 구간은 직선)와 같은 방법.
// GET /w/shape/<노선 id> → {"poly": "<인코딩 폴리라인, 정밀도 5>"} (못 만들면 404)

import { encodeKey } from "./proxy.js";

export const OSRM = "https://router.project-osrm.org/route/v1/driving/";
export const CHUNK = 25;
export const MAX_STOPS = 400;
const BUCKETS = 1024;

/** 노선 묶음 번호 (pipeline/build_web_data.py bucket, 앱 WebData.bucket 과 같음) */
export function bucket(routeId) {
  let h = 0x811c9dc5;
  for (const b of new TextEncoder().encode(routeId)) {
    h ^= b;
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h % BUCKETS;
}

export function meters(a, b) {
  const dy = (b[0] - a[0]) * 111000;
  const dx = (b[1] - a[1]) * 111000 * Math.cos((a[0] * Math.PI) / 180);
  return Math.sqrt(dx * dx + dy * dy);
}

export function length(path) {
  let d = 0;
  for (let i = 1; i < path.length; i++) d += meters(path[i - 1], path[i]);
  return d;
}

/** 앞 구간 끝 정류장을 다음 구간 시작으로 겹쳐서 나눔 */
export function chunks(stops, size = CHUNK) {
  if (stops.length <= size) return [stops];
  const out = [];
  let start = 0;
  while (start < stops.length - 1) {
    const end = Math.min(start + size, stops.length);
    out.push(stops.slice(start, end));
    start = end - 1;
  }
  return out;
}

export function decode(s, precision) {
  const factor = 10 ** precision;
  const out = [];
  let i = 0, lat = 0, lng = 0;
  while (i < s.length) {
    for (const which of [0, 1]) {
      let shift = 0, result = 0, b;
      do {
        b = s.charCodeAt(i++) - 63;
        result += (b & 0x1f) * 2 ** shift;
        shift += 5;
      } while (b >= 0x20 && i < s.length);
      const v = result % 2 ? -(result + 1) / 2 : result / 2;
      if (which === 0) lat += v; else lng += v;
    }
    out.push([lat / factor, lng / factor]);
  }
  return out;
}

export function encode(points, precision = 5) {
  const factor = 10 ** precision;
  let out = "", pl = 0, pg = 0;
  const num = (v) => {
    let n = v < 0 ? -2 * v - 1 : 2 * v;
    let s = "";
    while (n >= 0x20) {
      s += String.fromCharCode((0x20 | (n % 32)) + 63);
      n = Math.floor(n / 32);
    }
    return s + String.fromCharCode(n + 63);
  };
  for (const [la, lo] of points) {
    const a = Math.round(la * factor), b = Math.round(lo * factor);
    out += num(a - pl) + num(b - pg);
    pl = a; pg = b;
  }
  return out;
}

/** 각 정류장에 가장 가까운 모양 꼭짓점 (앞으로만, 직전 정류장에서 4km 안) */
export function stopIndices(shape, stops) {
  const cum = [0];
  for (let i = 1; i < shape.length; i++) cum.push(cum[i - 1] + meters(shape[i - 1], shape[i]));
  const out = [];
  let from = 0;
  for (const s of stops) {
    let best = from, bestD = Infinity;
    for (let j = from; j < shape.length && cum[j] - cum[from] <= 4000; j++) {
      const d = meters(s, shape[j]);
      if (d < bestD) { bestD = d; best = j; }
    }
    out.push(best);
    from = best;
  }
  return out;
}

/** 정류장 사이에서 블록을 한 바퀴 도는 구간(직선의 1.8배 넘고 250m 넘게 더 김)은 직선으로 */
export function removeDetours(shape, stops) {
  if (shape.length < 2 || stops.length < 2) return shape;
  const idx = stopIndices(shape, stops);
  const out = [];
  for (let k = 0; k < stops.length - 1; k++) {
    const a = idx[k], b = idx[k + 1];
    const straight = meters(stops[k], stops[k + 1]);
    const piece = b > a ? shape.slice(a, b + 1) : [];
    const road = piece.length >= 2 ? length(piece) : 0;
    const loop = piece.length >= 2 && road > straight * 1.8 && road > straight + 250;
    const seg = piece.length < 2 || loop ? [stops[k], stops[k + 1]] : piece;
    out.push(...(out.length ? seg.slice(1) : seg));
  }
  return out;
}

export function osrmUrl(points) {
  return OSRM + points.map(([la, lo]) => `${lo.toFixed(6)},${la.toFixed(6)}`).join(";") +
    "?overview=full&geometries=polyline6&continue_straight=true";
}

/** 점 p 에서 선까지 가장 가까운 거리(m) */
export function distToPath(p, path) {
  let best = Infinity;
  const kx = 111000 * Math.cos((p[0] * Math.PI) / 180), ky = 111000;
  for (let i = 0; i < path.length - 1; i++) {
    const ax = (path[i][1] - p[1]) * kx, ay = (path[i][0] - p[0]) * ky, bx = (path[i + 1][1] - p[1]) * kx, by = (path[i + 1][0] - p[0]) * ky;
    const dx = bx - ax, dy = by - ay, l = dx * dx + dy * dy, t = l ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l)) : 0;
    best = Math.min(best, Math.hypot(ax + t * dx, ay + t * dy));
  }
  return best;
}

/** 길이 크게 돌아 들어가게 만드는 정류장 (좌표가 큰길 옆 골목·단지 쪽에 찍힌 것): 앞뒤 정류장 사이 길이 직선의 1.4배 + 150m 넘게 김 */
export function detourStops(path, stops) {
  if (stops.length < 3 || path.length < 2) return [];
  const idx = stopIndices(path, stops);
  const out = [];
  for (let k = 1; k < stops.length - 1; k++) {
    const a = idx[k - 1], b = idx[k + 1];
    if (b <= a) continue;
    const through = length(path.slice(a, b + 1)), straight = meters(stops[k - 1], stops[k + 1]);
    if (through > straight * 1.4 + 150) out.push(k);
  }
  return out;
}

const NEAR_MAIN_M = 60, OFF_COORD_M = 250;
/** 버스가 실제로 안으로 들어가는 정류장 이름 (○○내, ○○ 안, 구내) */
export const insideName = (n) => /(^|[s.·])?(내|안|구내)$/.test(String(n || "").trim()) || /구내|단지내|병원내|시장내|역내|터미널내/.test(String(n || ""));
/**
 * 정류장 좌표들 → 길 따라 모양. 정류장 좌표가 큰길 옆 골목·단지 쪽에 찍혀 길이 그쪽으로 돌아 들어가면,
 * 그 정류장을 빼고 다시 받아 큰길로 (뺀 정류장이 새 길에서 60m 넘게 멀면 실제로 들어가는 길이라 다시 넣음)
 */
export async function build(stops, fetcher, st = {}) {
  // 돌아 들어가는지는 다듬기(removeDetours) 전의 길로 봄 (다듬으면 골목 길이 직선으로 바뀌어 안 보임)
  const done = (path, used) => removeDetours(path, used);
  const first = await buildRaw(stops, fetcher, st);
  if (!first) return null;
  const sus = detourStops(first, stops).slice(0, MAX_CHECKS);
  if (!sus.length) return done(first, stops);
  // 정류장마다 따로: 앞뒤 정류장만 이은 길에서 60m 안이면 큰길 위 정류장 → 뺌 (한꺼번에 빼면 붙은 정류장들이 같이 빠져 다른 길로 감)
  const drop = new Set();
  for (const k of sus) {
    const pair = await osrmPath([stops[k - 1], stops[k + 1]], fetcher);
    if (!pair) continue;
    // 60m 안이면 큰길 위 정류장. 250m 안이고 이름이 '○○내'처럼 안으로 들어가는 곳이 아니면 정류장 좌표가 골목 쪽으로 잘못 찍힌 것으로 봄
    // (전주 조촌초교.제일아파트: 좌표는 기린대로에서 181m 안쪽이지만 버스는 기린대로로만 다님)
    const d = distToPath(stops[k], pair);
    if (d <= NEAR_MAIN_M || (d <= OFF_COORD_M && !insideName(stops[k][2]))) drop.add(k);
  }
  if (!drop.size) return done(first, stops);
  const used = stops.filter((_, i) => !drop.has(i));
  const second = await buildRaw(used, fetcher, st);
  return second ? done(second, used) : done(first, stops);
}
const MAX_CHECKS = 12;

/** OSRM 길 한 번 (실패하면 null) */
async function osrmPath(points, fetcher) {
  try {
    const r = await fetcher(osrmUrl(points), { headers: { "User-Agent": "TagoHub-proxy" } });
    if (!r.ok) return null;
    const j = await r.json();
    const g = j.code === "Ok" && j.routes && j.routes[0] && j.routes[0].geometry;
    const shape = g ? decode(g, 6) : null;
    return shape && shape.length >= 2 ? shape : null;
  } catch (_) { return null; }
}

/** 구간마다 OSRM 길을 이음 (다듬기 전). 구간 실패·너무 긴 구간은 직선, 하나도 못 받으면 null */
async function buildRaw(stops, fetcher, st = {}) {
  const out = [];
  let anyRoad = false, straight = 0;
  for (const part of chunks(stops)) {
    let piece = null;
    try {
      const r = await fetcher(osrmUrl(part), { headers: { "User-Agent": "TagoHub-proxy" } });
      if (r.ok) {
        const j = await r.json();
        const g = j.code === "Ok" && j.routes && j.routes[0] && j.routes[0].geometry;
        if (g) {
          const shape = decode(g, 6);
          if (shape.length >= 2 && length(shape) <= length(part) * 3 + 300) piece = shape;
        }
      }
    } catch (_) { /* 이 구간은 직선 */ }
    if (piece) anyRoad = true;
    if (!piece) straight++;
    const seg = piece || part;
    out.push(...(out.length ? seg.slice(1) : seg));
  }
  st.straight = straight; // 마지막으로 만든 길 기준
  return anyRoad ? out : null;
}

// ── 카카오 경로 (앱 RoadShapeClient 와 같은 방법) ──
// 정류장 6개씩 카카오 대중교통 길찾기를 받아, 이 노선 번호 버스를 타는 구간의 경로를 씀 (카카오 지도의 실제 버스 길).
// 카카오에 보내는 것은 정류장 좌표(공개 자료)뿐. 구간 시작·끝이 정류장 200m 안, 사이 정류장 80m 곁을 지나고, 너무 돌지 않아야 채택
export const KAKAO_CHUNK = 6, KAKAO_MAX_PARTS = 22, SUBREQ_BUDGET = 45;
const sameNo = (a, b) => { const n = (s) => String(s || "").replace(/\s/g, "").replace(/번$/, ""); return n(a) !== "" && n(a) === n(b); };
export function kakaoRidePath(body, routeNo, a, b) {
  for (const r of body?.routes || []) {
    for (const s of r.steps || []) {
      const p = s.properties || {};
      if (p.type !== "BUS" || !(p.vehicles || []).some((v) => sameNo(v.name, routeNo))) continue;
      const pts = (s.path?.points || []).map((xy) => [parseFloat(xy[1]), parseFloat(xy[0])]).filter((q) => Number.isFinite(q[0]) && Number.isFinite(q[1]));
      if (pts.length >= 2 && meters(pts[0], a) <= 200 && meters(pts[pts.length - 1], b) <= 200) return pts;
    }
  }
  return null;
}
async function kakaoPiece(part, routeNo, key, fetcher) {
  const [a, b] = [part[0], part[part.length - 1]];
  try {
    const q = `start_x=${a[1].toFixed(6)}&start_y=${a[0].toFixed(6)}&end_x=${b[1].toFixed(6)}&end_y=${b[0].toFixed(6)}`;
    const r = await fetcher("https://dapi.kakao.com/v2/routing/publictraffic?" + q, { headers: { Authorization: "KakaoAK " + key } });
    if (!r.ok) return null;
    const p = kakaoRidePath(await r.json(), routeNo, a, b);
    if (!p) return null;
    if (part.slice(1, -1).some((s) => distToPath(s, p) > 80)) return null;
    return length(p) <= length(part) * 2.2 + 300 ? p : null;
  } catch (_) { return null; }
}
/** 카카오 경로로 만든 모양. 카카오가 못 준 구간은 그 구간만 OSRM. 절반 넘게 못 받으면 null (OSRM 방식으로) */
export async function buildKakao(stops, routeNo, key, fetcher, st = {}) {
  const parts = chunks(stops, KAKAO_CHUNK);
  // Workers 무료 요금제는 요청 하나에 바깥 요청 50번까지: 구간이 많으면(정류장 약 110개 넘음) 카카오는 쓰지 않음
  if (parts.length > KAKAO_MAX_PARTS) return null;
  const out = [];
  let got = 0, straight = 0;
  for (const part of parts) {
    let piece = await kakaoPiece(part, routeNo, key, fetcher);
    if (piece) got++;
    else {
      const o = await osrmPath(part, fetcher);
      piece = o && length(o) <= length(part) * 3 + 300 ? removeDetours(o, part) : part;
      if (piece === part) straight++;
    }
    out.push(...(out.length ? piece.slice(1) : piece));
  }
  st.straight = straight;
  return got * 2 >= parts.length ? out : null;
}

/** 정류장의 85% 가 선에서 100m 안 (앱 Shapes.fitsStops 와 같은 기준) */
export function fitsStops(path, stops, maxM = 100, ratio = 0.85) {
  if (!path || path.length < 2) return false;
  return stops.filter((s) => distToPath(s, path) <= maxM).length >= stops.length * ratio;
}

/** 정류장 목록이 바뀌면 다시 만들게 하는 표시 */
export function signature(stops) {
  const f = stops[0], l = stops[stops.length - 1];
  // v6: 직선 섞인 모양은 저장하지 않음 (v5 때 저장된 직선 섞인 모양을 다시 만듦)
  return `v6:${stops.length}:${f[0].toFixed(4)},${f[1].toFixed(4)}:${l[0].toFixed(4)},${l[1].toFixed(4)}`;
}

const HEAD = { "Access-Control-Allow-Origin": "*", "Content-Type": "application/json; charset=utf-8" };

async function ensureTable(db) {
  await db.prepare(
    "CREATE TABLE IF NOT EXISTS shapes (route_id TEXT PRIMARY KEY, sig TEXT NOT NULL, poly TEXT NOT NULL, made INTEGER NOT NULL) WITHOUT ROWID",
  ).run();
}

/** 서버에 올린 노선 조각에서 정류장 좌표 (정적 파일, env.ASSETS) */
async function routeStops(env, routeId) {
  const r = await env.ASSETS.fetch(new Request(`https://assets.local/data/routes/${bucket(routeId)}.json`));
  if (!r.ok) return null;
  const route = (await r.json())[routeId];
  if (!route) return null;
  return route.stops.filter((s) => s[3] != null && s[4] != null).map((s) => (s[2] ? [s[3], s[4], s[2]] : [s[3], s[4]]));
}

/** TAGO 노선 정류장 목록(XML) → 정류장 좌표 (순번 차례) */
export function tagoStops(xml) {
  const tag = (it, t) => { const m = new RegExp(`<${t}>([^<]*)</${t}>`).exec(it); return m ? m[1].trim() : null; };
  return [...xml.matchAll(/<item>([\s\S]*?)<\/item>/g)].map((m) => [parseInt(tag(m[1], "nodeord"), 10), parseFloat(tag(m[1], "gpslati")), parseFloat(tag(m[1], "gpslong")), tag(m[1], "nodenm")])
    .filter((x) => Number.isFinite(x[0]) && Number.isFinite(x[1]) && Number.isFinite(x[2])).sort((a, b) => a[0] - b[0]).map((x) => (x[3] ? [x[1], x[2], x[3]] : [x[1], x[2]]));
}

/** 경기 노선 (GBIS 정류장 목록, 좌표 x 경도·y 위도) */
async function ggRouteStops(env, routeId, fetcher) {
  const m = /^GGB(\d{9})$/.exec(routeId);
  if (!m || !env.DATA_GO_KR_KEY) return null;
  try {
    const r = await fetcher(`https://apis.data.go.kr/6410000/busrouteservice/v2/getBusRouteStationListv2?routeId=${m[1]}&format=json&serviceKey=${encodeKey(env.DATA_GO_KR_KEY)}`);
    if (!r.ok) return null;
    const j = await r.json();
    const list = j?.response?.msgBody?.busRouteStationList;
    const rows = (Array.isArray(list) ? list : list ? [list] : []).map((s) => [+s.stationSeq, +s.y, +s.x, s.stationName ? String(s.stationName) : null]).filter((x) => x.slice(0, 3).every(Number.isFinite)).sort((a, b) => a[0] - b[0]);
    return rows.length >= 2 ? rows.map((x) => (x[3] ? [x[1], x[2], x[3]] : [x[1], x[2]])) : null;
  } catch (_) { return null; }
}

/** 서버 노선 조각에 없는 노선(전주·제주·교통안전공단 등): TAGO 노선 정류장 목록으로 (도시코드가 있어야 함) */
async function tagoRouteStops(env, routeId, city, fetcher) {
  if (!/^\d{1,6}$/.test(city || "") || !env.DATA_GO_KR_KEY) return null;
  const q = `serviceKey=${encodeKey(env.DATA_GO_KR_KEY)}&cityCode=${city}&routeId=${encodeURIComponent(routeId)}&numOfRows=1000&pageNo=1&_type=xml`;
  try {
    const r = await fetcher(`https://apis.data.go.kr/1613000/BusRouteInfoInqireService/getRouteAcctoThrghSttnList?${q}`);
    if (!r.ok) return null;
    const stops = tagoStops(await r.text());
    return stops.length >= 2 ? stops : null;
  } catch (_) { return null; }
}

/** 노선 번호 확인 (카카오 경로용) */
const cleanNo = (no) => { const n = String(no || "").trim(); return /^[0-9A-Za-z가-힣()-]{1,20}$/.test(n) ? n : null; };

/** 자료 조각 노선의 번호 */
async function dataRouteNo(env, routeId) {
  try {
    const r = await env.ASSETS.fetch(new Request(`https://assets.local/data/routes/${bucket(routeId)}.json`));
    return r.ok ? cleanNo((await r.json())[routeId]?.no) : null;
  } catch (_) { return null; }
}

/** 노선 모양 만들기 (카카오 먼저, 안 되면 OSRM). 바깥 요청은 45번까지. {shape, partial(직선 섞임)} */
async function makeShape(stops, routeNo, env, fetcher) {
  let used = 0;
  const limited = async (u, o) => (++used > SUBREQ_BUDGET ? { ok: false } : fetcher(u, o));
  const st = {};
  const kakaoKey = (env.KAKAO_REST_KEY || "").trim();
  let shape = null;
  if (routeNo && kakaoKey) {
    const k = await buildKakao(stops, routeNo, kakaoKey, limited, st);
    if (k && fitsStops(k, stops)) shape = k;
  }
  if (!shape) shape = await build(stops, limited, st);
  return { shape, partial: (st.straight || 0) > 0 };
}

async function ensureQueue(db) {
  await db.prepare("CREATE TABLE IF NOT EXISTS shape_queue (route_id TEXT PRIMARY KEY, city TEXT, no TEXT, prio INTEGER NOT NULL, tries INTEGER NOT NULL DEFAULT 0, at INTEGER NOT NULL) WITHOUT ROWID").run();
  await db.prepare("CREATE TABLE IF NOT EXISTS shape_meta (k TEXT PRIMARY KEY, v TEXT) WITHOUT ROWID").run();
}

/** 저장된 모양 (지금 방식 v6 로 다 만든 것만) */
async function storedShape(db, id, sig) {
  const row = await db.prepare("SELECT sig, poly FROM shapes WHERE route_id = ?").bind(id).first();
  return row && row.sig === sig ? row.poly : null;
}

export async function handleShape(request, url, env, ctx, fetcher = fetch) {
  const id = decodeURIComponent(url.pathname.slice("/w/shape/".length));
  // 노선 id 에 "-" 가 든 지역도 있음 (구미 GMB101-110 등)
  if (!/^[A-Za-z0-9_-]{1,32}$/.test(id)) return new Response("not allowed", { status: 404, headers: HEAD });
  const cache = caches.default;
  const city = url.searchParams.get("city");
  // 노선 번호가 있으면 카카오 경로(앱과 같은 방법)를 먼저 씀 (자료 조각 노선은 조각의 번호)
  const routeNo = cleanNo(url.searchParams.get("no")) || (await dataRouteNo(env, id));
  const useKakao = !!(routeNo && (env.KAKAO_REST_KEY || "").trim());
  const key = new Request(`https://cache.tagohub/shape/v6${useKakao ? "k" : ""}/${id}`);
  const hit = await cache.match(key);
  if (hit) return hit;

  const stops = (await routeStops(env, id)) || (await ggRouteStops(env, id, fetcher)) || (await tagoRouteStops(env, id, city, fetcher));
  if (!stops || stops.length < 2 || stops.length > MAX_STOPS) return new Response("no shape", { status: 404, headers: HEAD });
  const sig = signature(stops) + (useKakao ? ":k" : "");
  let poly = null, partial = false;
  if (env.DB) {
    await ensureTable(env.DB);
    poly = await storedShape(env.DB, id, sig);
  }
  if (!poly) {
    // 서버가 미리 만들어 둔 것이 없음: 지금 한 번 만들어 보여 주고, 직선이 섞였으면 예약 작업 줄 맨 앞에 넣어 나중에 제대로 만듦
    const made = await makeShape(stops, routeNo, env, fetcher);
    if (!made.shape) {
      if (env.DB) ctx.waitUntil(enqueue(env.DB, id, city, routeNo, 2));
      return new Response("no shape", { status: 404, headers: { ...HEAD, "Cache-Control": "public, max-age=600" } });
    }
    poly = encode(made.shape, 5);
    partial = made.partial;
    if (env.DB) {
      if (!partial) {
        ctx.waitUntil(env.DB.prepare("INSERT OR REPLACE INTO shapes (route_id, sig, poly, made) VALUES (?, ?, ?, ?)")
          .bind(id, sig, poly, Math.floor(Date.now() / 1000)).run());
      } else ctx.waitUntil(enqueue(env.DB, id, city, routeNo, 2));
    }
  }
  const res = new Response(JSON.stringify({ poly }), { headers: { ...HEAD, "Cache-Control": "public, max-age=" + (partial ? 600 : 86400) } });
  ctx.waitUntil(cache.put(key, res.clone()));
  return res;
}

async function enqueue(db, id, city, no, prio, ensure = true) {
  if (ensure) await ensureQueue(db);
  await db.prepare("INSERT INTO shape_queue (route_id, city, no, prio, tries, at) VALUES (?, ?, ?, ?, 0, ?) ON CONFLICT(route_id) DO UPDATE SET prio = MAX(prio, excluded.prio), city = COALESCE(excluded.city, city), no = COALESCE(excluded.no, no)")
    .bind(id, /^\d{1,6}$/.test(city || "") ? city : null, no || null, prio, Math.floor(Date.now() / 1000)).run();
}

// ── 예약 작업: 노선 모양을 미리 만들어 저장 (1분마다 노선 하나) ──
// 사람들이 연 노선(직선이 섞였던 것)이 먼저, 그다음 자료 조각의 모든 노선을 묶음 차례로 줄에 넣음
const MAX_TRIES = 5;
export async function shapeCron(env, fetcher = fetch) {
  if (!env.DB) return null;
  await ensureTable(env.DB);
  await ensureQueue(env.DB);
  let job = await env.DB.prepare("SELECT route_id, city, no, tries FROM shape_queue ORDER BY prio DESC, tries ASC, at ASC LIMIT 1").first();
  if (!job) {
    // 줄이 비면 자료 조각 노선 묶음 하나(약 9개)를 줄에 넣음 (아직 지금 방식으로 저장 안 된 것만)
    const cur = +((await env.DB.prepare("SELECT v FROM shape_meta WHERE k = 'bucket'").first())?.v || 0);
    if (cur >= 1024) return { done: true };
    const r = await env.ASSETS.fetch(new Request(`https://assets.local/data/routes/${cur}.json`));
    const routes = r.ok ? await r.json() : {};
    // D1 질의 수를 아끼려고 이 묶음의 저장된 모양을 한 번에 봄
    const ids = Object.keys(routes);
    const saved = new Map();
    if (ids.length) {
      const rows = await env.DB.prepare(`SELECT route_id, sig FROM shapes WHERE route_id IN (${ids.map(() => "?").join(",")})`).bind(...ids).all();
      for (const x of rows.results || []) saved.set(x.route_id, x.sig);
    }
    for (const [rid, rt] of Object.entries(routes)) {
      const has = saved.has(rid) ? { sig: saved.get(rid) } : null;
      if (!has || !String(has.sig).startsWith("v6:")) await enqueue(env.DB, rid, /^\d+$/.test(rt.city || "") ? rt.city : null, cleanNo(rt.no), 0, false);
    }
    await env.DB.prepare("INSERT OR REPLACE INTO shape_meta (k, v) VALUES ('bucket', ?)").bind(String(cur + 1)).run();
    return { bucket: cur };
  }
  const id = job.route_id;
  const stops = (await routeStops(env, id)) || (await ggRouteStops(env, id, fetcher)) || (await tagoRouteStops(env, id, job.city, fetcher));
  if (!stops || stops.length < 2 || stops.length > MAX_STOPS) {
    await env.DB.prepare("DELETE FROM shape_queue WHERE route_id = ?").bind(id).run();
    return { id, skipped: true };
  }
  const routeNo = cleanNo(job.no) || (await dataRouteNo(env, id));
  const useKakao = !!(routeNo && (env.KAKAO_REST_KEY || "").trim());
  const sig = signature(stops) + (useKakao ? ":k" : "");
  if (await storedShape(env.DB, id, sig)) {
    await env.DB.prepare("DELETE FROM shape_queue WHERE route_id = ?").bind(id).run();
    return { id, already: true };
  }
  const made = await makeShape(stops, routeNo, env, fetcher);
  // 다 만들었거나 여러 번 해도 직선이 남으면(그 구간은 길이 없음) 저장하고 줄에서 뺌
  if (made.shape && (!made.partial || job.tries + 1 >= MAX_TRIES)) {
    await env.DB.prepare("INSERT OR REPLACE INTO shapes (route_id, sig, poly, made) VALUES (?, ?, ?, ?)").bind(id, sig, encode(made.shape, 5), Math.floor(Date.now() / 1000)).run();
    await env.DB.prepare("DELETE FROM shape_queue WHERE route_id = ?").bind(id).run();
    return { id, saved: true, partial: made.partial };
  }
  if (job.tries + 1 >= MAX_TRIES) await env.DB.prepare("DELETE FROM shape_queue WHERE route_id = ?").bind(id).run();
  else await env.DB.prepare("UPDATE shape_queue SET tries = tries + 1, at = ? WHERE route_id = ?").bind(Math.floor(Date.now() / 1000), id).run();
  return { id, retry: true };
}
