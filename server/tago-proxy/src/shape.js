// 노선 도로 모양 (웹·PC 버전용). 정류장만 잇는 직선 대신 길을 따라 그리도록, 서버가 한 번 만들어 D1 에 저장하고 모두에게 나눠 준다.
// 폰 앱 RoadShapeClient·Shapes(OSRM 구간 요청, 정류장 사이 블록을 한 바퀴 도는 구간은 직선)와 같은 방법.
// GET /w/shape/<노선 id> → {"poly": "<인코딩 폴리라인, 정밀도 5>"} (못 만들면 404)

import { encodeKey, cacheable } from "./proxy.js";
import { storeKey, getStored, putStored, DAY_MS } from "./tagostore.js";
import { gpsShape, gpsPoints, markGps, nextGpsRoute } from "./gps.js";
import { appShape } from "./appshape.js";

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

/**
 * k 번째 정류장에서 회차함: 앞뒤 정류장이 거의 같은 자리 (예: 입석 → 용운입구 → 입석).
 * 길찾기는 경유지에서 U턴을 하지 않아, 회차 정류장을 가운데 두고 한 번에 물으면 멀리 돌아오고 그 뒤 정류장이 모두 직선이 됨
 */
export function turnsAt(stops, k) {
  if (k <= 0 || k >= stops.length - 1) return false;
  const ab = meters(stops[k - 1], stops[k]), bc = meters(stops[k], stops[k + 1]);
  return ab >= 150 && bc >= 150 && meters(stops[k - 1], stops[k + 1]) <= 0.3 * Math.min(ab, bc);
}

/** 앞 구간 끝 정류장을 다음 구간 시작으로 겹쳐서 나눔. 회차 정류장에서는 끊어 가는 길·돌아오는 길을 따로 물음 */
export function chunks(stops, size = CHUNK) {
  const out = [];
  let start = 0;
  while (start < stops.length - 1) {
    let end = Math.min(start + size, stops.length);
    for (let k = start + 1; k < end - 1; k++) if (turnsAt(stops, k)) { end = k + 1; break; }
    out.push(stops.slice(start, end));
    start = end - 1;
  }
  return out.length ? out : [stops];
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

/** 길이 같은 자리(30m 안)로 되돌아오는 고리·되돌아 나오기를 잘라냄 (돌아 나간 거리 100m 넘는 것만) */
export function cutLoops(path) {
  const out = [];
  let i = 0;
  while (i < path.length) {
    out.push(path[i]);
    let walked = 0, best = -1;
    for (let j = i + 1; j < path.length; j++) {
      walked += meters(path[j - 1], path[j]);
      if (walked >= 100 && meters(path[i], path[j]) <= 30) best = j;
    }
    i = best > 0 ? best : i + 1;
  }
  return out;
}

/**
 * 정류장 사이 길 다듬기. 직선의 1.8배 넘고 250m 넘게 더 긴 구간은 같은 자리로 되돌아오는 고리(블록 한 바퀴·멀리 가서 U턴)만 잘라내고,
 * 그래도 직선의 3배 + 300m 넘게 길면 직선으로. 호숫가·산길처럼 원래 굽이굽이 긴 길은 그대로
 * (예전엔 1.8배만 넘으면 통째로 직선이라 임실 운암 옥정호 길이 호수를 가로지르는 직선이 됐음)
 */
export function removeDetours(shape, stops) {
  if (shape.length < 2 || stops.length < 2) return shape;
  const idx = stopIndices(shape, stops);
  const out = [];
  for (let k = 0; k < stops.length - 1; k++) {
    const a = idx[k], b = idx[k + 1];
    const straight = meters(stops[k], stops[k + 1]);
    let seg = b > a ? shape.slice(a, b + 1) : [];
    if (seg.length >= 2 && length(seg) > straight * 1.8 && length(seg) > straight + 250) {
      seg = cutLoops(seg);
      if (length(seg) > straight * 3 + 300) seg = [];
    }
    if (seg.length < 2) seg = [stops[k], stops[k + 1]];
    out.push(...(out.length ? seg.slice(1) : seg));
  }
  return out;
}

/** straight=false: 경유지(정류장)에서 U턴을 허락 (항구·종점처럼 들어갔다 되돌아 나오는 정류장) */
export function osrmUrl(points, straight = true) {
  return OSRM + points.map(([la, lo]) => `${lo.toFixed(6)},${la.toFixed(6)}`).join(";") +
    `?overview=full&geometries=polyline6&continue_straight=${straight}`;
}
/** 구간 길이로 보아 쓸 만한 길 (빙 돌아가는 길은 아님) */
const usable = (path, part) => !!path && path.length >= 2 && length(path) <= length(part) * 3 + 300;

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
async function osrmOnce(points, fetcher, straight) {
  try {
    const r = await fetcher(osrmUrl(points, straight), { headers: { "User-Agent": "TagoHub-proxy" } });
    if (!r.ok) return null;
    const j = await r.json();
    const g = j.code === "Ok" && j.routes && j.routes[0] && j.routes[0].geometry;
    const shape = g ? decode(g, 6) : null;
    return shape && shape.length >= 2 ? shape : null;
  } catch (_) { return null; }
}
/**
 * OSRM 길. 먼저 정류장에서 직진(continue_straight)으로 묻고, 못 받거나 빙 돌아가는 길이면 U턴을 허락해 한 번 더.
 * 섬·항구처럼 정류장에서 되돌아 나오는 노선은 직진만 허락하면 수십 km 를 돌아가는 길이 나와 직선으로 그려졌음
 * (여수 365-5 초도 대동항: 직진 60.4km → U턴 허락 399m)
 */
async function osrmPath(points, fetcher) {
  const a = await osrmOnce(points, fetcher, true);
  if (usable(a, points)) return a;
  const b = await osrmOnce(points, fetcher, false);
  return usable(b, points) ? b : a;
}

/** 구간마다 OSRM 길을 이음 (다듬기 전). 구간 실패·너무 긴 구간은 직선, 하나도 못 받으면 null */
async function buildRaw(stops, fetcher, st = {}) {
  const out = [];
  let anyRoad = false, straight = 0;
  for (const part of chunks(stops)) {
    const o = await osrmPath(part, fetcher);
    const piece = usable(o, part) ? o : null; // 못 받으면 이 구간은 직선
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
/** 구간 하나: 카카오 경로, 못 받으면 OSRM, 그것도 못 받으면 직선 */
async function kakaoPart(part, routeNo, key, fetcher) {
  const k = await kakaoPiece(part, routeNo, key, fetcher);
  if (k) return { piece: k, kakao: true };
  const o = await osrmPath(part, fetcher);
  const piece = o && length(o) <= length(part) * 3 + 300 ? removeDetours(o, part) : part;
  return { piece, straight: piece === part };
}
/** 정류장이 많아 한 번에 카카오 경로를 다 못 받는 노선 (예약 작업이 여러 번에 나눠 만듦) */
export const longForKakao = (stops) => chunks(stops, KAKAO_CHUNK).length > KAKAO_MAX_PARTS;
const KAKAO_STEP_PARTS = 20;
/**
 * 긴 노선의 카카오 경로를 이어 만들기 (예약 작업 한 번에 구간 20개씩). 진행은 D1 shape_work 에 둠.
 * {done:false} 이면 다음 분에 이어서, {done:true, shape} 이면 다 만듦 (카카오를 절반 넘게 못 받았으면 shape null)
 */
export async function kakaoStep(db, id, sig, stops, routeNo, key, fetcher) {
  const parts = chunks(stops, KAKAO_CHUNK);
  const w = await db.prepare("SELECT sig, next, poly, got, straight FROM shape_work WHERE route_id = ?").bind(id).first();
  let next = 0, pts = [], got = 0, straight = 0;
  if (w && w.sig === sig) { next = w.next; pts = w.poly ? decode(w.poly, 6) : []; got = w.got; straight = w.straight; }
  const end = Math.min(parts.length, next + KAKAO_STEP_PARTS);
  for (; next < end; next++) {
    const r = await kakaoPart(parts[next], routeNo, key, fetcher);
    if (r.kakao) got++;
    if (r.straight) straight++;
    pts.push(...(pts.length ? r.piece.slice(1) : r.piece));
  }
  if (next < parts.length) {
    await db.prepare("INSERT OR REPLACE INTO shape_work (route_id, sig, next, poly, got, straight) VALUES (?, ?, ?, ?, ?, ?)")
      .bind(id, sig, next, encode(pts, 6), got, straight).run();
    return { done: false, next, of: parts.length };
  }
  await db.prepare("DELETE FROM shape_work WHERE route_id = ?").bind(id).run();
  return { done: true, shape: got * 2 >= parts.length ? pts : null, straight };
}

/** 카카오 경로로 만든 모양. 카카오가 못 준 구간은 그 구간만 OSRM. 절반 넘게 못 받으면 null (OSRM 방식으로) */
export async function buildKakao(stops, routeNo, key, fetcher, st = {}) {
  const parts = chunks(stops, KAKAO_CHUNK);
  // Workers 무료 요금제는 요청 하나에 바깥 요청 50번까지: 구간이 많으면(정류장 약 110개 넘음) 카카오는 쓰지 않음
  if (parts.length > KAKAO_MAX_PARTS) return null;
  const out = [];
  let got = 0, straight = 0;
  for (const part of parts) {
    const r = await kakaoPart(part, routeNo, key, fetcher);
    if (r.kakao) got++;
    if (r.straight) straight++;
    out.push(...(out.length ? r.piece.slice(1) : r.piece));
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
  // v7: 서울·경기·전주는 지역 공식 노선 모양 먼저 (예전 모양은 다시 만듦)
  return `v7:${stops.length}:${f[0].toFixed(4)},${f[1].toFixed(4)}:${l[0].toFixed(4)},${l[1].toFixed(4)}`;
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

/** 전주시 노선 정류장 목록(XML) → [{seq, id: "JUB…", name}] (순번 차례, 같은 순번은 한 번만) */
export function jeonjuStopIds(xml) {
  const tag = (it, t) => { const r = new RegExp(`<${t}[^>]*>([^<]*)</${t}>`, "i").exec(it); return r ? r[1].trim() : null; };
  const seen = new Set();
  return [...String(xml).matchAll(/<list[^>]*>([\s\S]*?)<\/list>/g)]
    .map((m) => ({ seq: parseInt(tag(m[1], "brsseqno") ?? tag(m[1], "brnseqno"), 10), id: tag(m[1], "stopstandardid"), name: tag(m[1], "stopkname") || "" }))
    .filter((s) => Number.isFinite(s.seq) && s.id && !seen.has(s.seq) && seen.add(s.seq))
    .map((s) => ({ ...s, id: "JUB" + s.id }))
    .sort((a, b) => a.seq - b.seq);
}

/**
 * 전주 노선(JUB…) 정류장: 전주시 버스 API(4641000, 웹 /w/bus/jeonju/stops 와 같은 자료)의 정류장 차례 + 이름 자료 조각(data/names)의 좌표.
 * TAGO 노선정보 하루 한도가 차면 서버가 정류장을 못 받아 모양을 못 만들었음 (110번: 웹은 정류장 직선)
 */
async function jeonjuRouteStops(env, routeId, fetcher) {
  const m = /^JUB(\d{9})$/.exec(routeId);
  if (!m || !env.DATA_GO_KR_KEY || !env.ASSETS) return null;
  try {
    const r = await fetcher(`https://apis.data.go.kr/4641000/realtime/bus_location_bus_position_common?brtStdid=${m[1]}&serviceKey=${encodeKey(env.DATA_GO_KR_KEY)}`);
    if (!r.ok) return null;
    const list = jeonjuStopIds(await r.text());
    if (list.length < 2) return null;
    // 좌표: 이름 자료 조각(data/names/<이름 첫 글자 16진수>.json, [id, 이름, 위도, 경도, …])에서 정류장 id 로 (웹 withCoords 와 같음)
    const at = new Map();
    const chars = [...new Set(list.map((s) => s.name.codePointAt(0)?.toString(16)).filter(Boolean))];
    await Promise.all(chars.map(async (ch) => {
      const f = await env.ASSETS.fetch(new Request(`https://assets.local/data/names/${ch}.json`)).catch(() => null);
      if (!f || !f.ok) return;
      for (const a of await f.json().catch(() => [])) if (String(a[0]).startsWith("JUB")) at.set(a[0], [a[2], a[3]]);
    }));
    const stops = list.map((s) => at.get(s.id) && [...at.get(s.id), s.name]).filter(Boolean);
    return stops.length >= 2 && stops.length >= list.length * 0.8 ? stops : null;
  } catch (_) { return null; }
}

/**
 * 서버 노선 조각에 없는 노선(전주·제주·교통안전공단 등): TAGO 노선 정류장 목록으로 (도시코드가 있어야 함).
 * D1 에 사흘 안에 받아 둔 것이 있으면 다시 묻지 않음 (예약 작업이 매번 물어 하루 한도를 다 썼었음, src/tagostore.js).
 * 공공데이터가 실패하면 30일 안의 지난 목록
 */
async function tagoRouteStops(env, routeId, city, fetcher) {
  if (!/^\d{1,6}$/.test(city || "") || !env.DATA_GO_KR_KEY) return null;
  const sk = storeKey("/1613000/BusRouteInfoInqireService/getRouteAcctoThrghSttnList", `cityCode=${city}&routeId=${encodeURIComponent(routeId)}`);
  const parse = (xml) => { const s = xml ? tagoStops(xml) : []; return s.length >= 2 ? s : null; };
  const saved = parse(await getStored(env.DB, sk, 3 * DAY_MS));
  if (saved) return saved;
  const q = `serviceKey=${encodeKey(env.DATA_GO_KR_KEY)}&cityCode=${city}&routeId=${encodeURIComponent(routeId)}&numOfRows=1000&pageNo=1&_type=xml`;
  try {
    const r = await fetcher(`https://apis.data.go.kr/1613000/BusRouteInfoInqireService/getRouteAcctoThrghSttnList?${q}`);
    const body = r.ok ? await r.text() : "";
    const stops = cacheable(r.status ?? (r.ok ? 200 : 0), body) ? parse(body) : null;
    if (stops) { await putStored(env.DB, sk, body); return stops; }
  } catch (_) { /* 아래에서 지난 목록 */ }
  return parse(await getStored(env.DB, sk, 30 * DAY_MS));
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

// ── 지역 공식 노선 모양 (앱 MapViewModel 과 같은 자료) ──
/** 서울 getRoutePath(SEB…), 경기 getBusRouteLineListv2(GGB…), 전주 노선 그림(JUB…). 없거나 못 받으면 null */
export async function officialShape(env, id, fetcher) {
  const key = env.DATA_GO_KR_KEY;
  if (!key) return null;
  try {
    let m;
    if ((m = /^SEB(\d{1,12})$/.exec(id))) {
      const r = await fetcher(`http://ws.bus.go.kr/api/rest/busRouteInfo/getRoutePath?busRouteId=${m[1]}&resultType=json&serviceKey=${encodeKey(key)}`);
      if (!r.ok) return null;
      const list = (await r.json())?.msgBody?.itemList;
      return pointsOf(list, "no", "gpsY", "gpsX");
    }
    if ((m = /^GGB(\d{9})$/.exec(id))) {
      const r = await fetcher(`https://apis.data.go.kr/6410000/busrouteservice/v2/getBusRouteLineListv2?routeId=${m[1]}&format=json&serviceKey=${encodeKey(key)}`);
      if (!r.ok) return null;
      const list = (await r.json())?.response?.msgBody?.busRouteLineList;
      return pointsOf(list, "lineSeq", "y", "x");
    }
    if ((m = /^JUB(\d{9})$/.exec(id))) {
      const r = await fetcher(`https://apis.data.go.kr/4641000/nosun/bus_nosun_drawing_common?brtStdid=${m[1]}&serviceKey=${encodeKey(key)}`);
      if (!r.ok) return null;
      return jeonjuDrawing(await r.text());
    }
  } catch (_) { /* 없음 */ }
  return null;
}

/**
 * 공식 경로를 앱이 올린 모양보다 먼저 쓰는 노선: 서울(SEB, 서울시 getRoutePath)·경기(GGB, 경로 이력)·전주(JUB, 전주시 노선 그림).
 * 전주는 앱이 카카오 길찾기 조각을 먼저 이어 선이 흐트러졌음(110번). 전주 그림은 점이 듬성듬성해 정류장 95% 가 맞아야 씀
 */
export const OFFICIAL_FIRST = /^(SEB\d{1,12}|GGB\d{9}|JUB\d{9})$/;
/**
 * 서울·경기·전주 노선의 공식 경로를 폴리라인 글자로. 정류장의 80%(전주 95%) 가 120m 안을 지날 때만 (노선이 바뀌었으면 null).
 * 앱이 올린 모양보다 먼저 씀: 앱이 서울 API 를 못 받던 때(HTTP 401) 카카오·OSRM 으로 대신 만든 엉뚱한 모양이 올라가
 * 앱·웹 모두 그걸 그렸음. 하루 저장(없으면 1시간)
 */
export async function regionOfficial(env, id, fetcher = fetch) {
  if (!OFFICIAL_FIRST.test(id)) return null;
  const cache = caches.default;
  const key = new Request(`https://cache.tagohub/official/v2/${id}`);
  const hit = await cache.match(key).catch(() => null);
  if (hit) return (await hit.text()) || null;
  const pts = await officialShape(env, id, fetcher).catch(() => null);
  let poly = null;
  if (pts && pts.length >= 2) {
    const jeonju = id.startsWith("JUB");
    const stops = (await routeStops(env, id).catch(() => null)) || (jeonju ? await jeonjuRouteStops(env, id, fetcher).catch(() => null) : null);
    // 전주는 정류장을 모르면 쓰지 않음 (맞는지 볼 수 없음)
    const fits = !stops || stops.length < 2 ? !jeonju : stops.filter((s) => distToPath(s, pts) <= 120).length >= stops.length * (jeonju ? 0.95 : 0.8);
    if (fits) poly = encode(pts, 5);
  }
  await cache.put(key, new Response(poly || "", { headers: { "Cache-Control": `public, max-age=${poly ? 86400 : 3600}` } })).catch(() => {});
  return poly;
}
function pointsOf(list, seqKey, latKey, lngKey) {
  const rows = (Array.isArray(list) ? list : list ? [list] : [])
    .map((x) => [+x[seqKey] || 0, parseFloat(x[latKey]), parseFloat(x[lngKey])]).filter((x) => Number.isFinite(x[1]) && Number.isFinite(x[2]));
  rows.sort((a, b) => a[0] - b[0]);
  return rows.length >= 2 ? rows.map((x) => [x[1], x[2]]) : null;
}
/** 전주시 노선 그림 XML (<list><vtxy>위도</vtxy><vtxx>경도</vtxx></list>, 응답 순서대로) */
export function jeonjuDrawing(xml) {
  const tag = (it, t) => { const r = new RegExp(`<${t}[^>]*>([^<]*)</${t}>`, "i").exec(it); return r ? parseFloat(r[1]) : NaN; };
  const pts = [...String(xml).matchAll(/<list[^>]*>([\s\S]*?)<\/list>/g)].map((m) => [tag(m[1], "vtxy"), tag(m[1], "vtxx")]).filter((p) => Number.isFinite(p[0]) && Number.isFinite(p[1]));
  return pts.length >= 2 ? pts : null;
}

/** 노선 모양 만들기 (카카오 먼저, 안 되면 OSRM). 바깥 요청은 45번까지. {shape, partial(직선 섞임)} */
async function makeShape(id, stops, routeNo, env, fetcher) {
  let used = 0;
  const limited = async (u, o) => (++used > SUBREQ_BUDGET ? { ok: false } : fetcher(u, o));
  // 1) 지역이 공개한 실제 노선 모양 (서울·경기·전주, 앱과 같은 자료). 정류장 80% 가 120m 안을 지나야 씀
  const official = await officialShape(env, id, limited);
  if (official && fitsStops(official, stops, 120, 0.8)) return { shape: official, partial: false, official: true };
  const st = {};
  const kakaoKey = (env.KAKAO_REST_KEY || "").trim();
  let shape = null;
  let kakao = false;
  if (routeNo && kakaoKey) {
    const k = await buildKakao(stops, routeNo, kakaoKey, limited, st);
    if (k && fitsStops(k, stops)) { shape = k; kakao = true; }
  }
  if (!shape) shape = await build(stops, limited, st);
  // 긴 노선이라 카카오를 못 쓰고 OSRM 으로 만들었으면 "임시" (예약 작업이 나중에 카카오로 다시 만듦)
  const interim = !kakao && !!(routeNo && kakaoKey) && longForKakao(stops);
  return { shape, partial: (st.straight || 0) > 0, interim };
}

async function ensureQueue(db) {
  await db.prepare("CREATE TABLE IF NOT EXISTS shape_queue (route_id TEXT PRIMARY KEY, city TEXT, no TEXT, prio INTEGER NOT NULL, tries INTEGER NOT NULL DEFAULT 0, at INTEGER NOT NULL) WITHOUT ROWID").run();
  await db.prepare("CREATE TABLE IF NOT EXISTS shape_meta (k TEXT PRIMARY KEY, v TEXT) WITHOUT ROWID").run();
  await db.prepare("CREATE TABLE IF NOT EXISTS shape_work (route_id TEXT PRIMARY KEY, sig TEXT NOT NULL, next INTEGER NOT NULL, poly TEXT, got INTEGER NOT NULL, straight INTEGER NOT NULL) WITHOUT ROWID").run();
  await db.prepare("CREATE TABLE IF NOT EXISTS shape_fix (route_id TEXT PRIMARY KEY, city TEXT, at INTEGER NOT NULL) WITHOUT ROWID").run();
}

/** 저장된 모양 (지금 방식으로 만든 것만). interim: 긴 노선을 카카오 대신 OSRM 으로 임시로 만든 것 (sig 끝 ":o") */
async function storedShape(db, id, sig) {
  const row = await db.prepare("SELECT sig, poly, made FROM shapes WHERE route_id = ?").bind(id).first();
  if (!row) return null;
  if (row.sig === sig || row.sig === sig + ":g") return { poly: row.poly, interim: false, stale: staleStraight(row) };
  if (row.sig === sig + ":o") return { poly: row.poly, interim: true, stale: staleStraight(row) };
  return null;
}

/** 정류장 사이 직선(길찾기가 못 찾아 정류장끼리 이은 선)으로 보는 길이 */
const STRAIGHT_M = 500;
/**
 * 이때(2026-11-20) 전에 만든 모양은 OSRM 에 정류장에서 직진만 허락해 물어서, 섬·항구처럼 정류장에서 되돌아 나오는 노선이
 * 수십 km 돌아가는 길로 나와 버려지고 직선으로 저장됐음 (여수 365-5 초도). 그런 모양은 다시 만듦 (지금은 U턴을 허락해 한 번 더 물음)
 */
export const STRAIGHT_FIX_AT = 1795159000;
/** 모양에 [STRAIGHT_M] 넘는 한 조각이 있음 */
export function longStraight(poly) {
  let line;
  try { line = decode(String(poly || ""), 5); } catch (_) { return false; }
  for (let i = 1; i < line.length; i++) if (meters(line[i - 1], line[i]) > STRAIGHT_M) return true;
  return false;
}
/**
 * 정류장 좌표에서 정류장 좌표로 곧장 이은 [STRAIGHT_M] 넘는 조각 (길찾기를 못 해 정류장끼리 이은 것)의 선 자리 목록.
 * 긴 노선은 요청 하나의 바깥 요청(45번)을 다 써서 뒤쪽 구간이 직선이 되고, 다섯 번 다시 해도 같아 그대로 저장되곤 했음
 * (2026-11-25 광주 1003 김대중컨벤션센터역 → 세하동 2.6km 직선, 길은 3.2km 있음)
 */
export function stopStraights(line, stops) {
  const near = (p) => stops.some((q) => meters(p, q) <= 5);
  const out = [];
  for (let i = 1; i < line.length; i++) if (meters(line[i - 1], line[i]) > STRAIGHT_M && near(line[i - 1]) && near(line[i])) out.push(i - 1);
  return out;
}
/** 그런 조각을 두 정류장 사이 OSRM 길로 바꿈 (돌아가는 길은 안 씀: 직선의 1.8배 + 250m 안, removeDetours 와 같은 기준). {line, fixed, left} */
export async function fillStraights(line, stops, fetcher, max = 20) {
  const out = line.slice();
  let fixed = 0, tried = 0;
  for (const i of stopStraights(line, stops).reverse()) {
    if (tried++ >= max) break;
    const a = line[i], b = line[i + 1], d = meters(a, b);
    const o = await osrmPath([a, b], fetcher);
    if (!o || o.length < 2 || length(o) > d * 1.8 + 250) continue;
    out.splice(i, 2, a, ...o.slice(1, -1), b);
    fixed++;
  }
  return { line: out, fixed, left: stopStraights(out, stops).length };
}
const noteFix = (db, id, city) => db.prepare("INSERT OR IGNORE INTO shape_fix (route_id, city, at) VALUES (?, ?, ?)").bind(id, /^\d{1,6}$/.test(city || "") ? city : null, Math.floor(Date.now() / 1000)).run();
/** 예약 작업: 저장된 모양 하나의 정류장 사이 직선을 길로 (한 번에 20곳, 바깥 요청은 새로 45번) */
async function straightFixCron(env, fetcher) {
  const job = await env.DB.prepare("SELECT route_id, city FROM shape_fix ORDER BY at ASC LIMIT 1").first();
  if (!job) return null;
  const id = job.route_id;
  const done = () => env.DB.prepare("DELETE FROM shape_fix WHERE route_id = ?").bind(id).run();
  const row = await env.DB.prepare("SELECT sig, poly FROM shapes WHERE route_id = ?").bind(id).first();
  if (!row || !row.poly || String(row.sig).endsWith(":g")) { await done(); return { id, fix: "none" }; }
  const stops = (await routeStops(env, id)) || (await jeonjuRouteStops(env, id, fetcher)) || (await tagoRouteStops(env, id, job.city, fetcher));
  if (!stops || stops.length < 2) { await done(); return { id, fix: "no stops" }; }
  let used = 0;
  const limited = async (u, o) => (++used > SUBREQ_BUDGET ? { ok: false } : fetcher(u, o));
  const r = await fillStraights(decode(row.poly, 5), stops, limited);
  if (r.fixed) await env.DB.prepare("UPDATE shapes SET poly = ? WHERE route_id = ? AND sig = ?").bind(encode(r.line, 5), id, row.sig).run();
  // 다 고쳤거나 이번에 하나도 못 고쳤으면(그 구간은 길이 없음) 줄에서 뺌. 남았으면 다음 차례에 이어서
  if (!r.left || !r.fixed) await done();
  else await env.DB.prepare("UPDATE shape_fix SET at = ? WHERE route_id = ?").bind(Math.floor(Date.now() / 1000), id).run();
  return { id, fixed: r.fixed, left: r.left };
}

/** 저장된 모양이 예전 방식으로 만든, 긴 직선이 든 것 (버스 자리로 만든 모양은 아님) */
export const staleStraight = (row) => !!row && !String(row.sig || "").endsWith(":g") && (row.made || 0) < STRAIGHT_FIX_AT && longStraight(row.poly);

/** 버스 자리로 만든 저장 모양 (없으면 null) */
async function gpsStored(db, id) {
  const row = await db.prepare("SELECT sig, poly FROM shapes WHERE route_id = ?").bind(id).first();
  return row && String(row.sig).endsWith(":g") ? row.poly : null;
}

export async function handleShape(request, url, env, ctx, fetcher = fetch) {
  const id = decodeURIComponent(url.pathname.slice("/w/shape/".length));
  // 노선 id 에 "-" 가 든 지역도 있음 (구미 GMB101-110 등)
  if (!/^[A-Za-z0-9_-]{1,32}$/.test(id)) return new Response("not allowed", { status: 404, headers: HEAD });
  // 버스가 실제로 지나간 자리로 만든 모양(src/gps.js, sig 끝 ":g")이 있으면 그것이 가장 먼저 (실제 길·차로·회전교차로 그대로)
  const fromGps = env.DB ? await gpsStored(env.DB, id).catch(() => null) : null;
  if (fromGps) return new Response(JSON.stringify({ poly: fromGps, src: "gps" }), { headers: { ...HEAD, "Cache-Control": "public, max-age=300" } });
  // 서울·경기 노선은 공식 경로 (앱이 올린 모양보다 먼저)
  const official = await regionOfficial(env, id, fetcher).catch(() => null);
  if (official) return new Response(JSON.stringify({ poly: official, src: "official" }), { headers: { ...HEAD, "Cache-Control": "public, max-age=300" } });
  // 앱(폰)이 만들어 올린 모양이 있으면 그것 (앱과 웹의 노선 선이 같게, src/appshape.js). 저장해 둔 서버 모양보다 먼저
  // (서울·경기는 앱 모양을 쓰지 않음: 공식 경로를 못 받았으면 아래에서 서버가 만듦)
  const fromApp = env.DB && !OFFICIAL_FIRST.test(id) ? await appShape(env.DB, id).catch(() => null) : null;
  if (fromApp) return new Response(JSON.stringify({ poly: fromApp, src: "app" }), { headers: { ...HEAD, "Cache-Control": "public, max-age=300" } });
  // ?only=app: 앱 모양만 물음 (없으면 만들지 않고 404 → 웹은 앱 안 공식 모양을 먼저 봄)
  if (url.searchParams.get("only") === "app") return new Response("no app shape", { status: 404, headers: { ...HEAD, "Cache-Control": "public, max-age=300" } });
  const cache = caches.default;
  const city = url.searchParams.get("city");
  // 노선 번호가 있으면 카카오 경로(앱과 같은 방법)를 먼저 씀 (자료 조각 노선은 조각의 번호)
  const routeNo = cleanNo(url.searchParams.get("no")) || (await dataRouteNo(env, id));
  const useKakao = !!(routeNo && (env.KAKAO_REST_KEY || "").trim());
  // v7b: 회차에서 끊어 다시 만든 모양을 받게 (예전 응답은 하루 동안 남아 있음)
  // v7c: 예전 방식으로 직선이 든 모양을 하루 동안 저장해 두던 응답을 쓰지 않게 (STRAIGHT_FIX_AT, 여수 365-5)
  const key = new Request(`https://cache.tagohub/shape/v7c${useKakao ? "k" : ""}/${id}`);
  const hit = await cache.match(key);
  if (hit) return hit;

  const getStops = async () => (await routeStops(env, id)) || (await ggRouteStops(env, id, fetcher)) || (await jeonjuRouteStops(env, id, fetcher)) || (await tagoRouteStops(env, id, city, fetcher));
  // 저장해 둔 모양이 있으면 바로 줌: 정류장 목록(전주 버스·TAGO)을 받아 지금 노선과 맞는지 보는 건 뒤에서 하고,
  // 달라졌으면 예약 작업 줄 맨 앞에 넣어 다시 만듦. 예전엔 맞는지 보느라 처음 여는 노선마다 정류장 목록을 기다려
  // 1~4초(중계 전엔 45~60초) 동안 웹에 정류장 사이 직선이 보였음 (2026-11-24)
  if (env.DB) {
    await ensureTable(env.DB);
    const row = await env.DB.prepare("SELECT sig, poly, made FROM shapes WHERE route_id = ?").bind(id).first().catch(() => null);
    if (row && row.poly && !staleStraight(row)) {
      ctx.waitUntil((async () => {
        const st = await getStops().catch(() => null);
        if (!st || st.length < 2) return;
        const want = signature(st) + (useKakao ? ":k" : "");
        if (!String(row.sig).endsWith(":g") && stopStraights(decode(row.poly, 5), st).length) { await ensureQueue(env.DB); await noteFix(env.DB, id, city); }
        if (row.sig === want || row.sig === want + ":g") return;
        await enqueue(env.DB, id, city, routeNo, row.sig === want + ":o" ? 1 : 2);
      })().catch(() => {}));
      const res = new Response(JSON.stringify({ poly: row.poly }), { headers: { ...HEAD, "Cache-Control": "public, max-age=600" } });
      ctx.waitUntil(cache.put(key, res.clone()));
      return res;
    }
  }

  const stops = await getStops();
  if (!stops || stops.length < 2 || stops.length > MAX_STOPS) return new Response("no shape", { status: 404, headers: HEAD });
  const sig = signature(stops) + (useKakao ? ":k" : "");
  let poly = null, partial = false, old = null;
  if (env.DB) {
    await ensureTable(env.DB);
    const got = await storedShape(env.DB, id, sig);
    // 예전 방식으로 직선이 든 모양(stale)은 지금 다시 만들어 봄 (예약 작업 줄은 차례가 늦게 옴, 여수 365-5). 못 만들면 그것을 그대로
    if (got && got.stale) old = got.poly;
    else if (got) {
      poly = got.poly;
      // 임시 모양이면 예약 작업이 카카오로 다시 만들게 줄에 (이미 있으면 그대로)
      if (got.interim) ctx.waitUntil(enqueue(env.DB, id, city, routeNo, 1));
    }
  }
  if (!poly) {
    // 서버가 미리 만들어 둔 것이 없음: 지금 한 번 만들어 보여 주고, 직선이 섞였으면 예약 작업 줄 맨 앞에 넣어 나중에 제대로 만듦
    const made = await makeShape(id, stops, routeNo, env, fetcher);
    if (!made.shape) {
      if (env.DB) ctx.waitUntil(enqueue(env.DB, id, city, routeNo, 2));
      // 못 만듦: 브라우저가 10분 동안 이 답을 들고 있으면 웹이 다시 물어도(30초·1분 30초 뒤) 정류장 직선만 보여서 1분만
      if (!old) return new Response("no shape", { status: 404, headers: { ...HEAD, "Cache-Control": "public, max-age=60" } });
      poly = old; partial = true; // 다시 못 만듦: 예전 모양을 잠깐만 (예약 작업이 이어서)
    } else {
      poly = encode(made.shape, 5);
      partial = made.partial;
      if (env.DB) {
        if (!partial) {
          ctx.waitUntil(env.DB.prepare("INSERT OR REPLACE INTO shapes (route_id, sig, poly, made) VALUES (?, ?, ?, ?)")
            .bind(id, made.interim ? sig + ":o" : sig, poly, Math.floor(Date.now() / 1000)).run());
          if (made.interim) ctx.waitUntil(enqueue(env.DB, id, city, routeNo, 1));
        } else ctx.waitUntil(enqueue(env.DB, id, city, routeNo, 2));
      }
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

/** 버스가 실제로 지나간 자리(GPS 칸)로 노선 모양 만들기 (모든 지역). 정류장 85% 가 60m 안을 지나야 저장 (sig 끝 ":g") */
async function gpsCron(env, fetcher) {
  const job = await nextGpsRoute(env.DB);
  if (!job) return null;
  const id = job.route_id;
  const stops = (await routeStops(env, id)) || (await jeonjuRouteStops(env, id, fetcher)) || (await tagoRouteStops(env, id, job.city, fetcher));
  if (!stops || stops.length < 2 || stops.length > MAX_STOPS) { await markGps(env.DB, id, job.cells, false); return { id, gps: "no stops" }; }
  const sig = signature(stops);
  const row = await env.DB.prepare("SELECT poly FROM shapes WHERE route_id = ?").bind(id).first();
  const base = row?.poly ? decode(row.poly, 5) : stops.map((s) => [s[0], s[1]]);
  const res = gpsShape(await gpsPoints(env.DB, id), base, stops);
  if (res.shape && fitsStops(res.shape, stops, 60, 0.85)) {
    const routeNo = await dataRouteNo(env, id);
    const k = routeNo && (env.KAKAO_REST_KEY || "").trim() ? ":k" : "";
    await env.DB.prepare("INSERT OR REPLACE INTO shapes (route_id, sig, poly, made) VALUES (?, ?, ?, ?)").bind(id, sig + k + ":g", encode(res.shape, 5), Math.floor(Date.now() / 1000)).run();
    await markGps(env.DB, id, job.cells, true);
    return { id, gps: "saved" };
  }
  await markGps(env.DB, id, job.cells, false);
  return { id, gps: res.why || "not fit" };
}

// ── 예약 작업: 노선 모양을 미리 만들어 저장 (1분마다 노선 하나) ──
// 사람들이 연 노선(직선이 섞였던 것)이 먼저, 그다음 자료 조각의 모든 노선을 묶음 차례로 줄에 넣음
const MAX_TRIES = 5;
/** 모양 만드는 방식의 판 (바뀌면 예약 작업이 모든 노선 묶음을 다시 훑음) */
const SHAPE_VER = "12"; // "11": 예전 방식으로 만든 긴 직선 모양을 다시 만듦 (STRAIGHT_FIX_AT). "12": 정류장 사이 직선이 든 저장 모양을 고칠 줄에 (stopStraights)
/** 자료 조각 노선 정류장([순번, id, 이름, 위도, 경도, …])에 회차가 있고, 저장 모양에 500m 넘는 직선 조각이 있음 */
export function turnStraight(stops, poly) {
  const pts = (stops || []).filter((s) => s[3] != null && s[4] != null).map((s) => [s[3], s[4]]);
  if (!pts.some((_, k) => turnsAt(pts, k))) return false;
  let line;
  try { line = decode(String(poly || ""), 5); } catch (_) { return false; }
  for (let i = 1; i < line.length; i++) if (meters(line[i - 1], line[i]) > 500) return true;
  return false;
}
export async function shapeCron(env, fetcher = fetch, now = Date.now()) {
  if (!env.DB) return null;
  await ensureTable(env.DB);
  await ensureQueue(env.DB);
  // 2분에 한 번: 실제 버스 GPS 가 충분히 쌓인 노선 하나(사람들이 보는 노선 먼저)를 그 길로 다시 만듦
  if (Math.floor(now / 60000) % 2 === 0) {
    const g = await gpsCron(env, fetcher).catch(() => null);
    if (g) return g;
  } else {
    // 나머지 분: 저장된 모양의 정류장 사이 직선을 길로 (고칠 것이 있을 때만)
    const f = await straightFixCron(env, fetcher).catch(() => null);
    if (f) return f;
  }
  // 모양 만드는 방식이 바뀌면(SHAPE_VER) 노선 묶음을 처음부터 다시 훑어, 지금 방식(v7)이 아닌 모양을 줄에 넣음
  // (판 "9": 잠깐 배포됐던 v8 — 공식 모양을 OSRM 으로 다시 이은 것, 큰길 대신 옆 골목으로 빠져 되돌림 — 을 원래대로 다시 만듦)
  const ver = (await env.DB.prepare("SELECT v FROM shape_meta WHERE k = 'ver'").first())?.v;
  if (ver !== SHAPE_VER) {
    await env.DB.prepare("INSERT OR REPLACE INTO shape_meta (k, v) VALUES ('bucket', ?)").bind("0").run();
    await env.DB.prepare("INSERT OR REPLACE INTO shape_meta (k, v) VALUES ('ver', ?)").bind(SHAPE_VER).run();
  }
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
      const rows = await env.DB.prepare(`SELECT route_id, sig, poly, made FROM shapes WHERE route_id IN (${ids.map(() => "?").join(",")})`).bind(...ids).all();
      for (const x of rows.results || []) saved.set(x.route_id, x);
    }
    for (const [rid, rt] of Object.entries(routes)) {
      const has = saved.get(rid) || null;
      // 회차 정류장이 있는데 500m 넘는 직선이 든 모양 (예전엔 회차에서 길찾기가 멀리 돌아 그 뒤가 직선이 됐음) → 지우고 다시 만듦
      // 예전 방식(직진만 물음)으로 만들어 긴 직선이 든 모양도 (섬·항구에서 되돌아 나오는 노선, 여수 365-5)
      // 정류장 사이 직선이 든 모양: 그 조각만 길로 고침 (straightFixCron)
      if (has && !String(has.sig).endsWith(":g")) {
        const pts = (rt.stops || []).filter((q) => q[3] != null && q[4] != null).map((q) => [q[3], q[4]]);
        let line = null;
        try { line = decode(String(has.poly || ""), 5); } catch (_) { line = null; }
        if (line && pts.length && stopStraights(line, pts).length) await noteFix(env.DB, rid, rt.city);
      }
      if (has && !String(has.sig).endsWith(":g") && (turnStraight(rt.stops, has.poly) || staleStraight(has))) {
        await env.DB.prepare("DELETE FROM shapes WHERE route_id = ?").bind(rid).run();
        await enqueue(env.DB, rid, /^\d+$/.test(rt.city || "") ? rt.city : null, cleanNo(rt.no), 0, false);
        continue;
      }
      // 아직 없거나, 예전 방식이거나, 긴 노선을 임시(OSRM)로 만든 것
      if (!has || !String(has.sig).startsWith("v7:") || String(has.sig).endsWith(":o")) await enqueue(env.DB, rid, /^\d+$/.test(rt.city || "") ? rt.city : null, cleanNo(rt.no), 0, false);
    }
    await env.DB.prepare("INSERT OR REPLACE INTO shape_meta (k, v) VALUES ('bucket', ?)").bind(String(cur + 1)).run();
    return { bucket: cur };
  }
  const id = job.route_id;
  const stops = (await routeStops(env, id)) || (await ggRouteStops(env, id, fetcher)) || (await jeonjuRouteStops(env, id, fetcher)) || (await tagoRouteStops(env, id, job.city, fetcher));
  if (!stops || stops.length < 2 || stops.length > MAX_STOPS) {
    await env.DB.prepare("DELETE FROM shape_queue WHERE route_id = ?").bind(id).run();
    return { id, skipped: true };
  }
  // 버스 자리로 만든 모양은 덮어쓰지 않음 (gpsCron 이 버스 자리가 늘면 다시 만듦)
  if (await gpsStored(env.DB, id)) { await env.DB.prepare("DELETE FROM shape_queue WHERE route_id = ?").bind(id).run(); return { id, gps: true }; }
  const routeNo = cleanNo(job.no) || (await dataRouteNo(env, id));
  const useKakao = !!(routeNo && (env.KAKAO_REST_KEY || "").trim());
  const sig = signature(stops) + (useKakao ? ":k" : "");
  const have = await storedShape(env.DB, id, sig);
  if (have && !have.interim && !have.stale) {
    await env.DB.prepare("DELETE FROM shape_queue WHERE route_id = ?").bind(id).run();
    return { id, already: true };
  }
  const save = async (poly, s) => env.DB.prepare("INSERT OR REPLACE INTO shapes (route_id, sig, poly, made) VALUES (?, ?, ?, ?)").bind(id, s, poly, Math.floor(Date.now() / 1000)).run();
  const dequeue = async () => env.DB.prepare("DELETE FROM shape_queue WHERE route_id = ?").bind(id).run();
  // 긴 노선: 공식 모양이 맞으면 그것, 아니면 카카오 경로를 여러 분에 나눠 이어 만듦
  if (useKakao && longForKakao(stops)) {
    let used = 0;
    const limited = async (u, o) => (++used > SUBREQ_BUDGET ? { ok: false } : fetcher(u, o));
    if (!have || have.stale) {
      const official = await officialShape(env, id, limited);
      if (official && fitsStops(official, stops, 120, 0.8)) { await save(encode(official, 5), sig); await dequeue(); return { id, saved: true, official: true }; }
    }
    const step = await kakaoStep(env.DB, id, sig, stops, routeNo, (env.KAKAO_REST_KEY || "").trim(), limited);
    if (!step.done) return { id, step: step.next, of: step.of };
    if (step.shape && fitsStops(step.shape, stops)) { await save(encode(step.shape, 5), sig); await dequeue(); return { id, saved: true, kakao: true }; }
    // 카카오로 못 만듦: 임시 모양이 있으면 그대로 두고, 없으면 아래에서 OSRM 으로
    if (have && !have.stale) { await dequeue(); return { id, kept: true }; }
  }
  const made = await makeShape(id, stops, routeNo, env, fetcher);
  // 다 만들었거나 여러 번 해도 직선이 남으면(그 구간은 길이 없음) 저장하고 줄에서 뺌
  if (made.shape && (!made.partial || job.tries + 1 >= MAX_TRIES)) {
    await save(encode(made.shape, 5), sig);
    await env.DB.prepare("DELETE FROM shape_queue WHERE route_id = ?").bind(id).run();
    // 직선이 남은 채 저장: 그 조각만 다른 차례(바깥 요청을 새로 받음)에 길로
    if (made.partial) await noteFix(env.DB, id, job.city);
    return { id, saved: true, partial: made.partial };
  }
  if (job.tries + 1 >= MAX_TRIES) await env.DB.prepare("DELETE FROM shape_queue WHERE route_id = ?").bind(id).run();
  else await env.DB.prepare("UPDATE shape_queue SET tries = tries + 1, at = ? WHERE route_id = ?").bind(Math.floor(Date.now() / 1000), id).run();
  return { id, retry: true };
}
