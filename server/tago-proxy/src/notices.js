// 공지사항: 관리자가 웹에서 직접 쓰고 고치고 지움 (D1 notices). 예전처럼 저장소 notices.json 에 적은 공지도 함께 내보냄.
// GET  /w/notices → [{date, title, body, id?, key?}] 최근 날짜 먼저 (앱 Notices.parse·웹 more.js 가 같은 모양으로 읽음)
//   id: D1 에 쓴 공지 번호, key: notices.json 공지 열쇠("f:날짜|제목") — 관리자가 고치거나 지울 때 씀
// POST /w/notices {id?, key?, date, title, body} → 새로 쓰기(id 없음)·고치기(id)·파일 공지 고치기(key)
// POST /w/notices {id 또는 key, delete: true} → 지우기 (D1 공지는 지운 표시만, 파일 공지는 숨김)
// 관리자만: X-TagoHub-Admin 헤더(EDIT_ADMIN_KEY) 또는 개발자 아이디 로그인 토큰 (지도 고치기와 같음, src/edits.js)

import { isAdmin } from "./edits.js";
import { isDevRequest } from "./auth.js";

export const FILE_URL = "https://raw.githubusercontent.com/eunho3023/TagoHub/main/notices.json";
const HEAD = { "Content-Type": "application/json; charset=utf-8", "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "GET, POST", "Access-Control-Allow-Headers": "Content-Type, X-TagoHub-Admin, Authorization" };
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
export const MAX_TITLE = 80, MAX_BODY = 4000;

/** 파일 공지 열쇠 */
export const fileKey = (n) => "f:" + String(n.date || "") + "|" + String(n.title || "");

/** 받은 공지를 검사해 {date, title, body}. 틀리면 null */
export function cleanNotice(b) {
  if (!b || typeof b !== "object") return null;
  const date = String(b.date ?? "").trim(), title = String(b.title ?? "").trim(), body = String(b.body ?? "").replace(/\r\n/g, "\n").trim();
  if (!DATE_RE.test(date) || Number.isNaN(Date.parse(date + "T00:00:00Z"))) return null;
  if (!title || title.length > MAX_TITLE || body.length > MAX_BODY) return null;
  return { date, title, body };
}

/** 파일 공지 + D1 줄 → 내보낼 목록 (지운 것·고쳐 쓴 파일 공지는 빼고, 최근 날짜 먼저·같은 날이면 나중에 쓴 것 먼저) */
export function merge(file, rows) {
  const live = (rows || []).filter((r) => !r.deleted);
  const replaced = new Set((rows || []).map((r) => r.replaces).filter(Boolean));
  const out = [];
  for (const n of Array.isArray(file) ? file : []) {
    if (!n || !n.title) continue;
    const key = fileKey(n);
    if (replaced.has(key)) continue;
    out.push({ date: String(n.date || ""), title: String(n.title), body: String(n.body || ""), key, at: 0 });
  }
  for (const r of live) out.push({ date: r.date, title: r.title, body: r.body, id: r.id, at: r.at });
  out.sort((a, b) => b.date.localeCompare(a.date) || b.at - a.at);
  return out.map(({ at, ...n }) => n);
}

let made = false;
async function ensure(db) {
  if (made) return;
  await db.prepare("CREATE TABLE IF NOT EXISTS notices (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, at INTEGER NOT NULL, replaces TEXT, deleted INTEGER NOT NULL DEFAULT 0)").run();
  made = true;
}

/** 저장소 notices.json (5분 저장, 못 받으면 빈 목록) */
async function fileNotices(fetcher) {
  try {
    const r = await fetcher(FILE_URL, { cf: { cacheTtl: 300, cacheEverything: true } });
    if (!r.ok) return [];
    const j = JSON.parse(await r.text());
    return Array.isArray(j) ? j : [];
  } catch { return []; }
}

async function list(env, fetcher) {
  const file = await fileNotices(fetcher);
  if (!env.DB) return merge(file, []);
  await ensure(env.DB);
  const { results } = await env.DB.prepare("SELECT id, date, title, body, at, replaces, deleted FROM notices").all();
  return merge(file, results || []);
}

export async function handleNotices(request, env, { fetcher = fetch, devCheck = isDevRequest } = {}) {
  const json = (o, status = 200, extra = {}) => new Response(JSON.stringify(o), { status, headers: { ...HEAD, ...extra } });
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: HEAD });
  if (request.method === "GET") return json(await list(env, fetcher), 200, { "Cache-Control": "public, max-age=60" });
  if (request.method !== "POST") return json({ error: "GET, POST only" }, 405);
  if (!isAdmin(request, env) && !(await devCheck(request, env).catch(() => false))) return json({ error: "admin only" }, 403);
  if (!env.DB) return json({ error: "no db" }, 500);
  let b;
  try {
    const text = await request.text();
    if (text.length > 20000) return json({ error: "too large" }, 413);
    b = JSON.parse(text);
  } catch { return json({ error: "bad json" }, 400); }
  await ensure(env.DB);
  const now = Date.now();
  const id = b && b.id != null ? Number(b.id) : null;
  const key = b && typeof b.key === "string" && b.key.startsWith("f:") && b.key.length <= 200 ? b.key : null;
  if (id != null && !(Number.isInteger(id) && id > 0)) return json({ error: "bad id" }, 400);
  if (b && b.delete === true) {
    if (id != null) await env.DB.prepare("UPDATE notices SET deleted = 1, at = ? WHERE id = ?").bind(now, id).run();
    else if (key) await env.DB.prepare("INSERT INTO notices (date, title, body, at, replaces, deleted) VALUES ('', '', '', ?, ?, 1)").bind(now, key).run();
    else return json({ error: "id or key" }, 400);
    return json({ ok: true, notices: await list(env, fetcher) });
  }
  const n = cleanNotice(b);
  if (!n) return json({ error: "bad notice" }, 400);
  if (id != null) {
    const r = await env.DB.prepare("UPDATE notices SET date = ?, title = ?, body = ?, at = ? WHERE id = ? AND deleted = 0").bind(n.date, n.title, n.body, now, id).run();
    if (!r.meta || !r.meta.changes) return json({ error: "not found" }, 404);
  } else {
    await env.DB.prepare("INSERT INTO notices (date, title, body, at, replaces, deleted) VALUES (?, ?, ?, ?, ?, 0)").bind(n.date, n.title, n.body, now, key).run();
  }
  return json({ ok: true, notices: await list(env, fetcher) });
}
