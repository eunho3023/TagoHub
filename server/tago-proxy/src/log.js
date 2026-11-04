// 지난 운행 기록 모으기: 앱들이 버스가 정류장을 지난 시각(노선·정류장·분)을 올리면 D1(env.DB)에 쌓고,
// 정류장 시간표 화면이 노선·정류장으로 물으면 최근 6주 기록을 돌려준다.
// 개인 정보는 없음: 노선 ID, 정류장 ID, 지난 시각(분)만. 같은 노선·정류장·분은 한 번만 저장.

export const KEEP_DAYS = 42;
export const MAX_HITS = 300;
const ID = /^[A-Za-z0-9_.-]{1,40}$/;

let ready = false;

async function ensureTable(db) {
  if (ready) return;
  await db.prepare(
    "CREATE TABLE IF NOT EXISTS hits (route_id TEXT NOT NULL, node_id TEXT NOT NULL, minute INTEGER NOT NULL, PRIMARY KEY (route_id, node_id, minute)) WITHOUT ROWID"
  ).run();
  ready = true;
}

/**
 * 올린 기록 검사: { routeId, hits: [[nodeId, epochSec], ...] } → [{ nodeId, minute }].
 * 이상한 ID, 하루보다 오래되었거나 5분 넘게 미래인 시각은 버린다. 형식이 틀리면 null.
 */
export function parseUpload(body, nowSec) {
  if (!body || typeof body.routeId !== "string" || !ID.test(body.routeId) || !Array.isArray(body.hits)) return null;
  const seen = new Set();
  const out = [];
  for (const h of body.hits.slice(0, MAX_HITS)) {
    if (!Array.isArray(h) || typeof h[0] !== "string" || !ID.test(h[0]) || !Number.isFinite(h[1])) continue;
    const sec = Math.floor(h[1]);
    if (sec < nowSec - 86400 || sec > nowSec + 300) continue;
    const minute = Math.floor(sec / 60);
    const k = h[0] + "|" + minute;
    if (seen.has(k)) continue;
    seen.add(k);
    out.push({ nodeId: h[0], minute });
  }
  return { routeId: body.routeId, hits: out };
}

const json = (obj, status = 200, extra = {}) =>
  new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json; charset=utf-8", ...extra } });

/** POST /log/hits, GET /log/stop?route=&node= */
export async function handleLog(request, url, env, ctx) {
  if (!env.DB) return json({ error: "no database" }, 503);
  await ensureTable(env.DB);
  const nowSec = Math.floor(Date.now() / 1000);

  if (url.pathname === "/log/hits" && request.method === "POST") {
    let body;
    try { body = await request.json(); } catch { return json({ error: "bad json" }, 400); }
    const up = parseUpload(body, nowSec);
    if (!up) return json({ error: "bad upload" }, 400);
    if (up.hits.length) {
      const stmt = env.DB.prepare("INSERT OR IGNORE INTO hits (route_id, node_id, minute) VALUES (?, ?, ?)");
      await env.DB.batch(up.hits.map((h) => stmt.bind(up.routeId, h.nodeId, h.minute)));
    }
    // 가끔 오래된 기록 지우기 (따로 예약 작업 없이)
    if (Math.random() < 0.02) {
      const oldest = Math.floor(nowSec / 60) - KEEP_DAYS * 1440;
      ctx.waitUntil(env.DB.prepare("DELETE FROM hits WHERE minute < ?").bind(oldest).run());
    }
    return json({ saved: up.hits.length });
  }

  if (url.pathname === "/log/stop" && request.method === "GET") {
    const route = url.searchParams.get("route") || "";
    const node = url.searchParams.get("node") || "";
    if (!ID.test(route) || !ID.test(node)) return json({ error: "bad id" }, 400);
    const oldest = Math.floor(nowSec / 60) - KEEP_DAYS * 1440;
    const rows = await env.DB.prepare(
      "SELECT minute FROM hits WHERE route_id = ? AND node_id = ? AND minute >= ? ORDER BY minute LIMIT 6000"
    ).bind(route, node, oldest).all();
    return json({ minutes: (rows.results || []).map((r) => r.minute) }, 200, { "Cache-Control": "private, max-age=300" });
  }

  return json({ error: "not found" }, 404);
}
