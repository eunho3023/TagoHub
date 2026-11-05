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

/** 정류장 좌표들 → 길 따라 모양. 구간 요청이 실패하거나 너무 돌아가면 그 구간은 직선. 하나도 못 받으면 null */
export async function build(stops, fetcher) {
  const out = [];
  let anyRoad = false;
  for (const part of chunks(stops)) {
    let piece = null;
    try {
      const r = await fetcher(osrmUrl(part), { headers: { "User-Agent": "TagoHub-proxy" } });
      if (r.ok) {
        const j = await r.json();
        const g = j.code === "Ok" && j.routes && j.routes[0] && j.routes[0].geometry;
        if (g) {
          const shape = decode(g, 6);
          if (shape.length >= 2 && length(shape) <= length(part) * 3 + 300) piece = removeDetours(shape, part);
        }
      }
    } catch (_) { /* 이 구간은 직선 */ }
    if (piece) anyRoad = true;
    const seg = piece || part;
    out.push(...(out.length ? seg.slice(1) : seg));
  }
  return anyRoad ? out : null;
}

/** 정류장 목록이 바뀌면 다시 만들게 하는 표시 */
export function signature(stops) {
  const f = stops[0], l = stops[stops.length - 1];
  return `${stops.length}:${f[0].toFixed(4)},${f[1].toFixed(4)}:${l[0].toFixed(4)},${l[1].toFixed(4)}`;
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
  return route.stops.filter((s) => s[3] != null && s[4] != null).map((s) => [s[3], s[4]]);
}

/** TAGO 노선 정류장 목록(XML) → 정류장 좌표 (순번 차례) */
export function tagoStops(xml) {
  const tag = (it, t) => { const m = new RegExp(`<${t}>([^<]*)</${t}>`).exec(it); return m ? m[1].trim() : null; };
  return [...xml.matchAll(/<item>([\s\S]*?)<\/item>/g)].map((m) => [parseInt(tag(m[1], "nodeord"), 10), parseFloat(tag(m[1], "gpslati")), parseFloat(tag(m[1], "gpslong"))])
    .filter((x) => Number.isFinite(x[0]) && Number.isFinite(x[1]) && Number.isFinite(x[2])).sort((a, b) => a[0] - b[0]).map((x) => [x[1], x[2]]);
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

export async function handleShape(request, url, env, ctx, fetcher = fetch) {
  const id = decodeURIComponent(url.pathname.slice("/w/shape/".length));
  if (!/^[A-Za-z0-9_]{1,32}$/.test(id)) return new Response("not allowed", { status: 404, headers: HEAD });
  const cache = caches.default;
  const key = new Request(`https://cache.tagohub/shape/${id}`);
  const hit = await cache.match(key);
  if (hit) return hit;

  const stops = (await routeStops(env, id)) || (await tagoRouteStops(env, id, url.searchParams.get("city"), fetcher));
  if (!stops || stops.length < 2 || stops.length > MAX_STOPS) return new Response("no shape", { status: 404, headers: HEAD });
  const sig = signature(stops);
  let poly = null;
  if (env.DB) {
    await ensureTable(env.DB);
    const row = await env.DB.prepare("SELECT sig, poly FROM shapes WHERE route_id = ?").bind(id).first();
    if (row && row.sig === sig) poly = row.poly;
  }
  if (!poly) {
    const shape = await build(stops, fetcher);
    if (!shape) return new Response("no shape", { status: 404, headers: { ...HEAD, "Cache-Control": "public, max-age=600" } });
    poly = encode(shape, 5);
    if (env.DB) {
      ctx.waitUntil(env.DB.prepare("INSERT OR REPLACE INTO shapes (route_id, sig, poly, made) VALUES (?, ?, ?, ?)")
        .bind(id, sig, poly, Math.floor(Date.now() / 1000)).run());
    }
  }
  const res = new Response(JSON.stringify({ poly }), { headers: { ...HEAD, "Cache-Control": "public, max-age=86400" } });
  ctx.waitUntil(cache.put(key, res.clone()));
  return res;
}
