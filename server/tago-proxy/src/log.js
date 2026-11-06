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

// ── 웹 버전 (토큰 없음) ──
const WEB_HEAD = { "Access-Control-Allow-Origin": "*", "Content-Type": "application/json; charset=utf-8" };

/** GET /w/log/stop?node=A&node=B (같은 자리 정류장 4개까지) → {routes: {노선 id: [분...]}} 최근 6주. 5분 저장 */
export async function handleWebLog(url, env) {
  if (!env.DB) return new Response(JSON.stringify({ routes: {} }), { headers: WEB_HEAD });
  const nodes = [...new Set(url.searchParams.getAll("node"))].filter((n) => ID.test(n)).slice(0, 4);
  if (!nodes.length) return new Response(JSON.stringify({ error: "bad id" }), { status: 400, headers: WEB_HEAD });
  await ensureTable(env.DB);
  const oldest = Math.floor(Date.now() / 60000) - KEEP_DAYS * 1440;
  const rows = await env.DB.prepare(
    `SELECT route_id, minute FROM hits WHERE node_id IN (${nodes.map(() => "?").join(",")}) AND minute >= ? ORDER BY minute LIMIT 20000`,
  ).bind(...nodes, oldest).all();
  const routes = {};
  for (const r of rows.results || []) (routes[r.route_id] ||= []).push(r.minute);
  return new Response(JSON.stringify({ routes }), { headers: { ...WEB_HEAD, "Cache-Control": "public, max-age=300" } });
}

/**
 * 노선별 버스 위치 응답(TAGO)을 받을 때마다: 바로 앞 응답(5분 안)과 비교해 정류장을 옮겨 간 버스는
 * 지금 정류장에 지금 도착한 것으로 기록 (앱 ArrivalStats.hits 와 같은 생각, 한 번에 6정류장 넘게 건너뛴 것은 버림).
 * 차량 번호는 비교에만 쓰고 서버 임시 저장소에 5분만 둠 (기록에는 노선·정류장·분만).
 */
export async function hitsFromBusLc(db, cache, routeId, xml, nowMs = Date.now()) {
  if (!db || !ID.test(routeId || "")) return 0;
  const tag = (it, t) => { const m = new RegExp(`<${t}>([^<]*)</${t}>`).exec(it); return m ? m[1].trim() : null; };
  const cur = {};
  for (const m of String(xml).matchAll(/<item>([\s\S]*?)<\/item>/g)) {
    const v = tag(m[1], "vehicleno"), ord = parseInt(tag(m[1], "nodeord"), 10), node = tag(m[1], "nodeid");
    if (v && Number.isFinite(ord) && node && ID.test(node)) cur[v] = [ord, node];
  }
  const key = new Request(`https://cache.tagohub/prevpos/${routeId}`);
  const prevRes = await cache.match(key);
  const prev = prevRes ? await prevRes.json().catch(() => null) : null;
  await cache.put(key, new Response(JSON.stringify({ at: nowMs, cur }), { headers: { "Cache-Control": "max-age=300" } }));
  if (!prev || nowMs - prev.at > 4 * 60000 || nowMs <= prev.at) return 0;
  const minute = Math.floor(nowMs / 60000);
  const out = [];
  for (const [v, [ord, node]] of Object.entries(cur)) {
    const p = prev.cur?.[v];
    if (!p) continue;
    const step = ord - p[0];
    if (step >= 1 && step <= 6) out.push(node);
  }
  if (!out.length) return 0;
  await ensureTable(db);
  const stmt = db.prepare("INSERT OR IGNORE INTO hits (route_id, node_id, minute) VALUES (?, ?, ?)");
  await db.batch([...new Set(out)].map((n) => stmt.bind(routeId, n, minute)));
  return out.length;
}
