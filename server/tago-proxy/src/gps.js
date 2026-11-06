// 버스가 실제로 다닌 길로 노선 모양 만들기.
// 서버가 받는 TAGO 버스 위치(GPS, 공공데이터)를 노선별로 약 5m 칸에 모음. 차량 번호·시각은 저장하지 않고
// "이 노선 버스가 지나간 자리"만 남김 (차량 추적 자료가 아니라 도로 모양 자료).
// 노선 선을 충분히 덮으면(85%) 그 점들을 OSRM 지도 맞추기(match)로 도로에 붙여 노선 모양으로 씀.

import { decode, distToPath, length, meters } from "./shape.js";

const CELL = 20000;            // 1/20000도 ≈ 5.5m(위도) × 4.5m(경도) 칸
const MAX_ROWS = 80;           // 응답 하나에서 볼 버스 수
let ready = false;

async function ensureGps(db) {
  if (ready) return;
  await db.prepare("CREATE TABLE IF NOT EXISTS gps_cells (route_id TEXT NOT NULL, cell TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, ord INTEGER, n INTEGER NOT NULL, PRIMARY KEY (route_id, cell)) WITHOUT ROWID").run();
  await db.prepare("CREATE TABLE IF NOT EXISTS gps_routes (route_id TEXT PRIMARY KEY, city TEXT, cells INTEGER NOT NULL DEFAULT 0, built INTEGER NOT NULL DEFAULT 0, tried INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID").run();
  ready = true;
}

/** TAGO 노선별 버스 위치 응답(XML) → [{lat, lng, ord}] (한국 범위 안의 좌표만) */
export function busPoints(xml) {
  const tag = (it, t) => { const m = new RegExp(`<${t}>([^<]*)</${t}>`).exec(it); return m ? m[1].trim() : null; };
  return [...String(xml).matchAll(/<item>([\s\S]*?)<\/item>/g)].slice(0, MAX_ROWS)
    .map((m) => ({ lat: parseFloat(tag(m[1], "gpslati")), lng: parseFloat(tag(m[1], "gpslong")), ord: parseInt(tag(m[1], "nodeord"), 10) }))
    .filter((p) => p.lat > 33 && p.lat < 39 && p.lng > 124 && p.lng < 132);
}

/** 버스 위치 응답을 받을 때마다: 노선별 칸에 더함 (D1 한 번 묶어 쓰기) */
export async function recordGps(db, routeId, city, xml) {
  if (!db || !/^[A-Za-z0-9_-]{1,32}$/.test(routeId || "")) return 0;
  const pts = busPoints(xml);
  if (!pts.length) return 0;
  await ensureGps(db);
  const stmts = pts.map((p) => {
    const cell = `${Math.round(p.lat * CELL)}_${Math.round(p.lng * CELL)}`;
    return db.prepare("INSERT INTO gps_cells (route_id, cell, lat, lng, ord, n) VALUES (?, ?, ?, ?, ?, 1) ON CONFLICT(route_id, cell) DO UPDATE SET n = MIN(n + 1, 1000000), ord = COALESCE(excluded.ord, ord)")
      .bind(routeId, cell, Math.round(p.lat * 1e6) / 1e6, Math.round(p.lng * 1e6) / 1e6, Number.isFinite(p.ord) ? p.ord : null);
  });
  stmts.push(db.prepare("INSERT INTO gps_routes (route_id, city, cells) VALUES (?, ?, 0) ON CONFLICT(route_id) DO UPDATE SET city = COALESCE(excluded.city, city)")
    .bind(routeId, /^\d{1,6}$/.test(city || "") ? city : null));
  await db.batch(stmts);
  return pts.length;
}

/** 점 p 의 선 위 자리(처음부터 m)와 거리 */
function chainage(p, line, cum) {
  let best = { d: Infinity, ch: 0 };
  const kx = 111000 * Math.cos((p[0] * Math.PI) / 180), ky = 111000;
  for (let i = 0; i < line.length - 1; i++) {
    const ax = (line[i][1] - p[1]) * kx, ay = (line[i][0] - p[0]) * ky, bx = (line[i + 1][1] - p[1]) * kx, by = (line[i + 1][0] - p[0]) * ky;
    const dx = bx - ax, dy = by - ay, l = dx * dx + dy * dy, t = l ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l)) : 0;
    const d = Math.hypot(ax + t * dx, ay + t * dy);
    if (d < best.d) best = { d, ch: cum[i] + t * (cum[i + 1] - cum[i]) };
  }
  return best;
}

const NEAR_M = 150, BIN_M = 200, COVER = 0.85, STEP_M = 40, MATCH_N = 90;

/**
 * 모인 점을 지금 노선 선(base)을 따라 줄 세워, 노선을 85% 넘게 덮으면 40m 간격으로 골라 OSRM 지도 맞추기.
 * {shape} 또는 {why: 까닭}
 */
export async function gpsShape(points, base, fetcher) {
  if (!base || base.length < 2) return { why: "no base" };
  const cum = [0];
  for (let i = 1; i < base.length; i++) cum.push(cum[i - 1] + meters(base[i - 1], base[i]));
  const total = cum[cum.length - 1];
  const on = [];
  for (const p of points) {
    const c = chainage([p.lat, p.lng], base, cum);
    if (c.d <= NEAR_M) on.push({ ch: c.ch, p: [p.lat, p.lng], n: p.n || 1 });
  }
  const bins = new Set(on.map((x) => Math.floor(x.ch / BIN_M)));
  const need = Math.max(1, Math.ceil(total / BIN_M));
  if (bins.size < need * COVER) return { why: `coverage ${bins.size}/${need}` };
  on.sort((a, b) => a.ch - b.ch);
  // 40m 칸마다 여러 번 지나간(많이 쌓인) 점 하나
  const pick = [];
  let cur = null;
  for (const x of on) {
    const k = Math.floor(x.ch / STEP_M);
    if (!cur || cur.k !== k) { if (cur) pick.push(cur.x.p); cur = { k, x }; } else if (x.n > cur.x.n) cur.x = x;
  }
  if (cur) pick.push(cur.x.p);
  if (pick.length < 2) return { why: "few points" };
  // OSRM 지도 맞추기 (90점씩, 끝점 겹침)
  const out = [];
  for (let s = 0; s < pick.length - 1; s += MATCH_N - 1) {
    const part = pick.slice(s, s + MATCH_N);
    if (part.length < 2) break;
    const url = "https://router.project-osrm.org/match/v1/driving/" + part.map(([la, lo]) => `${lo.toFixed(6)},${la.toFixed(6)}`).join(";") +
      "?overview=full&geometries=polyline6&gaps=ignore&tidy=true&radiuses=" + part.map(() => "30").join(";");
    let seg = null;
    try {
      const r = await fetcher(url, { headers: { "User-Agent": "TagoHub-proxy" } });
      if (r.ok) {
        const j = await r.json();
        if (j.code === "Ok" && Array.isArray(j.matchings) && j.matchings.length) seg = j.matchings.flatMap((m) => decode(m.geometry, 6));
      }
    } catch (_) { /* 이 구간은 점 그대로 */ }
    if (!seg || seg.length < 2) return { why: "match failed" };
    out.push(...(out.length ? seg.slice(1) : seg));
  }
  // 너무 돌거나(1.3배) 노선 선에서 크게 벗어나면 쓰지 않음
  if (length(out) > total * 1.3 + 500) return { why: "too long" };
  return { shape: out };
}

/** 예약 작업: 점이 충분히 쌓인(만든 뒤 30% 넘게 늘어난) 노선 하나 → {id, city} 또는 null */
export async function nextGpsRoute(db) {
  await ensureGps(db);
  await db.prepare("UPDATE gps_routes SET cells = (SELECT COUNT(*) FROM gps_cells c WHERE c.route_id = gps_routes.route_id) WHERE route_id IN (SELECT route_id FROM gps_routes ORDER BY tried ASC LIMIT 20)").run();
  return db.prepare("SELECT route_id, city, cells FROM gps_routes WHERE cells >= 30 AND cells > built * 1.3 AND route_id NOT LIKE 'SEB%' AND route_id NOT LIKE 'GGB%' AND route_id NOT LIKE 'JUB%' ORDER BY tried ASC, cells DESC LIMIT 1").first();
}

export async function gpsPoints(db, id) {
  const rows = await db.prepare("SELECT lat, lng, ord, n FROM gps_cells WHERE route_id = ?").bind(id).all();
  return rows.results || [];
}

export async function markGps(db, id, cells, built) {
  await db.prepare("UPDATE gps_routes SET tried = ?, built = CASE WHEN ? THEN ? ELSE built END WHERE route_id = ?")
    .bind(Math.floor(Date.now() / 1000), built ? 1 : 0, cells, id).run();
}

export { distToPath };
