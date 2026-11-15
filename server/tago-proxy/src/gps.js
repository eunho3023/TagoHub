// 버스가 실제로 다닌 길로 노선 모양 만들기.
// 서버가 받는 TAGO 버스 위치(GPS, 공공데이터)를 노선별로 약 5m 칸에 모음. 차량 번호·시각은 저장하지 않고
// "이 노선 버스가 지나간 자리"만 남김 (차량 추적 자료가 아니라 도로 모양 자료).
// 노선 선을 충분히 덮으면(85%) 그 자리들을 정류장 순서대로 이어 노선 모양으로 씀 (gpsShape).

import { distToPath, length, meters } from "./shape.js";

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

const NEAR_M = 60, BIN_M = 200, COVER = 0.85, STEP_M = 15, GAP_M = 60;

/**
 * line 의 [from, to] m 구간에서 점 p 가 가장 가까운 자리 {d, ch} (없으면 null).
 * [core] 를 주면 그 안의 자리를 먼저 (밖이면 25m 먼 것으로 침: 같은 길을 오가는 곳에서 거리가 같을 때 맞는 방향으로)
 */
function chainIn(p, line, cum, from, to, core = null) {
  let best = null;
  const kx = 111320 * Math.cos((p[0] * Math.PI) / 180), ky = 110540;
  for (let i = 0; i < line.length - 1; i++) {
    if (cum[i + 1] < from || cum[i] > to) continue;
    const ax = (line[i][1] - p[1]) * kx, ay = (line[i][0] - p[0]) * ky, bx = (line[i + 1][1] - p[1]) * kx, by = (line[i + 1][0] - p[0]) * ky;
    const dx = bx - ax, dy = by - ay, l = dx * dx + dy * dy, seg = cum[i + 1] - cum[i];
    // 조각 안에서도 [from, to] 안의 자리만
    const t0 = seg > 0 ? Math.max(0, (from - cum[i]) / seg) : 0, t1 = seg > 0 ? Math.min(1, (to - cum[i]) / seg) : 1;
    if (t0 > t1) continue;
    const t = l ? Math.max(t0, Math.min(t1, -(ax * dx + ay * dy) / l)) : t0;
    const d = Math.hypot(ax + t * dx, ay + t * dy), ch = cum[i] + t * seg;
    const score = d + (core && (ch < core[0] || ch > core[1]) ? 25 : 0);
    if (!best || score < best.score) best = { d, ch, score };
  }
  return best;
}
/** line 위 처음부터 ch m 자리 */
function pointAtCh(line, cum, ch) {
  let i = 0;
  while (i < cum.length - 2 && cum[i + 1] < ch) i++;
  const len = cum[i + 1] - cum[i], t = len ? Math.max(0, Math.min(1, (ch - cum[i]) / len)) : 0;
  return [line[i][0] + t * (line[i + 1][0] - line[i][0]), line[i][1] + t * (line[i + 1][1] - line[i][1])];
}

/**
 * 버스가 실제로 지나간 자리(5m 칸)로 노선 모양 만들기 (지도 맞추기 없이 자리 그대로).
 *  1) 칸마다 지금 노선 선(base) 위 자리를 찾음. 버스가 있던 정류장 순번(ord)을 알면 그 정류장~다음 정류장 구간에서만 찾아,
 *     같은 길을 오가는 노선에서 반대 방향 자리에 붙지 않게 함. 선에서 60m 넘게 먼 칸은 버림
 *  2) 노선을 200m 칸으로 나눠 85% 넘게 덮어야 만듦
 *  3) 15m 칸마다 지나간 횟수로 무게를 준 평균 자리를 이어 선으로 (오른쪽 차로·회전교차로·실제로 꺾은 길이 그대로 나옴).
 *     60m 넘게 빈 곳은 지금 선으로 채움
 *  4) 앞뒤 칸과 평균 내어 GPS 흔들림을 고름
 * [stops] 는 정류장 순서대로 [[위도, 경도]...] (TAGO nodeord 1 이 0 번째). {shape} 또는 {why}
 */
export function gpsShape(points, base, stops = []) {
  if (!base || base.length < 2) return { why: "no base" };
  const cum = [0];
  for (let i = 1; i < base.length; i++) cum.push(cum[i - 1] + meters(base[i - 1], base[i]));
  const total = cum[cum.length - 1];
  if (!(total > 0)) return { why: "no base" };
  // 정류장마다 선 위 자리 (앞으로만)
  const stopCh = [];
  let from = 0;
  for (const s of stops || []) {
    const h = chainIn([s[0], s[1]], base, cum, from, from + 5000);
    const ch = h && h.d <= 300 ? h.ch : from;
    stopCh.push(ch);
    from = ch;
  }
  const on = [];
  for (const p of points) {
    const q = [p.lat, p.lng];
    let lo = 0, hi = total, core = null;
    const k = Number.isFinite(p.ord) ? p.ord - 1 : -1;
    if (k >= 0 && k < stopCh.length) {
      // 버스는 순번 정류장(k)에서 다음 정류장(k+1) 사이 (앞뒤 200m 여유: 순번이 막 바뀐 때)
      lo = stopCh[k] - 200;
      hi = (k + 1 < stopCh.length ? stopCh[k + 1] : total) + 200;
      core = [stopCh[k], k + 1 < stopCh.length ? stopCh[k + 1] : total];
    }
    const h = chainIn(q, base, cum, lo, hi, core);
    if (h && h.d <= NEAR_M) on.push({ ch: h.ch, p: q, n: Math.min(p.n || 1, 50) });
  }
  const bins = new Set(on.map((x) => Math.floor(x.ch / BIN_M)));
  const need = Math.max(1, Math.ceil(total / BIN_M));
  if (bins.size < need * COVER) return { why: `coverage ${bins.size}/${need}` };
  // 15m 칸마다 무게 평균
  const slots = new Map();
  for (const x of on) {
    const b = Math.floor(x.ch / STEP_M);
    const s = slots.get(b) || { la: 0, lo: 0, w: 0 };
    s.la += x.p[0] * x.n; s.lo += x.p[1] * x.n; s.w += x.n;
    slots.set(b, s);
  }
  const keys = [...slots.keys()].sort((a, b) => a - b);
  const pts = [];
  let lastCh = null;
  const push = (pt, ch) => { pts.push(pt); lastCh = ch; };
  push(base[0], 0);
  for (const b of keys) {
    const s = slots.get(b), ch = (b + 0.5) * STEP_M;
    // 빈 곳은 지금 선으로
    if (ch - lastCh > GAP_M) for (let c = lastCh + STEP_M; c < ch - STEP_M / 2; c += STEP_M) push(pointAtCh(base, cum, c), c);
    push([s.la / s.w, s.lo / s.w], ch);
  }
  if (total - lastCh > GAP_M) for (let c = lastCh + STEP_M; c < total; c += STEP_M) push(pointAtCh(base, cum, c), c);
  pts.push(base[base.length - 1]);
  // 앞뒤 칸과 평균 (처음·끝은 그대로)
  const out = pts.map((p, i) => (i === 0 || i === pts.length - 1 ? p
    : [(pts[i - 1][0] + 2 * p[0] + pts[i + 1][0]) / 4, (pts[i - 1][1] + 2 * p[1] + pts[i + 1][1]) / 4]));
  if (length(out) > total * 1.3 + 500) return { why: "too long" };
  return { shape: out };
}

/**
 * 예약 작업: 점이 충분히 쌓인(만든 뒤 30% 넘게 늘어난) 노선 하나 → {id, city} 또는 null.
 * 사람들이 보는 노선(route_interest, src/poll.js) 먼저: 모으는 노선이 전국 수천 개라 아무 노선이나 고르면 차례가 오지 않음
 */
export async function nextGpsRoute(db) {
  await ensureGps(db);
  try {
    const hot = await db.prepare("SELECT g.route_id FROM gps_routes g JOIN route_interest i ON i.route_id = g.route_id ORDER BY g.tried ASC, i.score DESC LIMIT 10").all();
    const ids = (hot.results || []).map((r) => r.route_id);
    if (ids.length) {
      const q = ids.map(() => "?").join(",");
      await db.prepare(`UPDATE gps_routes SET cells = (SELECT COUNT(*) FROM gps_cells c WHERE c.route_id = gps_routes.route_id) WHERE route_id IN (${q})`).bind(...ids).run();
      const pick = await db.prepare(`SELECT route_id, city, cells FROM gps_routes WHERE route_id IN (${q}) AND cells >= 30 AND cells > built * 1.3 ORDER BY tried ASC, cells DESC LIMIT 1`).bind(...ids).first();
      if (pick) return pick;
    }
  } catch (_) { /* 관심 노선 표가 아직 없음 */ }
  await db.prepare("UPDATE gps_routes SET cells = (SELECT COUNT(*) FROM gps_cells c WHERE c.route_id = gps_routes.route_id) WHERE route_id IN (SELECT route_id FROM gps_routes ORDER BY tried ASC LIMIT 20)").run();
  return db.prepare("SELECT route_id, city, cells FROM gps_routes WHERE cells >= 30 AND cells > built * 1.3 ORDER BY tried ASC, cells DESC LIMIT 1").first();
}

export async function gpsPoints(db, id) {
  const rows = await db.prepare("SELECT lat, lng, ord, n FROM gps_cells WHERE route_id = ?").bind(id).all();
  return rows.results || [];
}

export async function markGps(db, id, cells, built) {
  await db.prepare("UPDATE gps_routes SET tried = ?, built = CASE WHEN ? THEN ? ELSE built END WHERE route_id = ?")
    .bind(Math.floor(Date.now() / 1000), built ? 1 : 0, cells, id).run();
}

/**
 * 확인용: 이 노선에 모인 버스 자리 {cells(칸 수), passes(지나간 횟수 합), built(만들 때 칸 수), tried(마지막으로 만들어 본 때), gps(버스 자리로 만든 모양이 있음)}.
 * 차량 번호·시각은 원래 저장하지 않음
 */
export async function gpsInfo(db, id) {
  if (!db || !/^[A-Za-z0-9_-]{1,32}$/.test(id || "")) return null;
  await ensureGps(db);
  const c = await db.prepare("SELECT COUNT(*) AS cells, COALESCE(SUM(n), 0) AS passes FROM gps_cells WHERE route_id = ?").bind(id).first();
  const r = await db.prepare("SELECT built, tried FROM gps_routes WHERE route_id = ?").bind(id).first();
  const s = await db.prepare("SELECT sig FROM shapes WHERE route_id = ?").bind(id).first().catch(() => null);
  return { id, cells: c?.cells || 0, passes: c?.passes || 0, built: r?.built || 0, tried: r?.tried || 0, gps: !!(s && String(s.sig).endsWith(":g")) };
}

export { distToPath };
