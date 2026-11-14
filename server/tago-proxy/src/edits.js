// 지도 고치기: 사람이 손으로 고친 버스 노선 모양·정류장 위치·지하철역 위치·지하철 노선 모양 (웹·앱이 같이 씀).
// 자동으로 만든 노선 모양(src/shape.js)이나 공공데이터 좌표가 틀어져 보일 때, 지도에서 고친 것을 D1 에 저장하고 모두에게 나눠 준다.
// GET  /w/edits[?v=번호]   → (가진 번호 v 가 지금과 같으면 204 "바뀐 것 없음") {v, bus:{노선 id: 폴리라인}, rail:{노선 이름: 폴리라인}, stop:{정류장 id:[위도,경도]}, station:{역 id:[위도,경도]}}
// POST /w/edits {kind, id, data} → 저장 (data 가 null 이면 고친 것을 지우고 원래대로). 관리자만: X-TagoHub-Admin 헤더 = 비밀 값 EDIT_ADMIN_KEY
// GET  /w/edits/check (X-TagoHub-Admin) → 관리자 키가 맞으면 200, 아니면 403 (설정 화면에서 키 확인)
// 고친 기록은 모두 edit_log 에 남김 (잘못 고친 것을 되돌릴 수 있게, 주소(IP)는 해시만)

import { decode, encode } from "./shape.js";

export const KINDS = ["bus", "rail", "stop", "station"];
const LINE_KINDS = new Set(["bus", "rail"]);
const MAX_POINTS = 3000;
const HEAD = { "Content-Type": "application/json; charset=utf-8", "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "GET, POST", "Access-Control-Allow-Headers": "Content-Type, X-TagoHub-Admin" };
export const ADMIN_HEADER = "X-TagoHub-Admin";

/** 관리자 키가 맞는지 (서버에 키가 없으면 아무도 못 고침). 글자 비교 시간이 키에 따라 달라지지 않게 */
export function isAdmin(request, env) {
  const want = String(env.EDIT_ADMIN_KEY || "").trim();
  const got = String(request.headers.get(ADMIN_HEADER) || "").trim();
  if (want.length < 16 || got.length !== want.length) return false;
  let diff = 0;
  for (let i = 0; i < want.length; i++) diff |= want.charCodeAt(i) ^ got.charCodeAt(i);
  return diff === 0;
}

let made = false;
async function ensure(db) {
  if (made) return;
  await db.batch([
    db.prepare("CREATE TABLE IF NOT EXISTS edits (kind TEXT NOT NULL, id TEXT NOT NULL, data TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (kind, id)) WITHOUT ROWID"),
    db.prepare("CREATE TABLE IF NOT EXISTS edit_log (seq INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, id TEXT NOT NULL, data TEXT, prev TEXT, at INTEGER NOT NULL, who TEXT)"),
  ]);
  made = true;
}

/** 대한민국 안 좌표인지 (대략) */
export function inKorea(lat, lng) {
  return Number.isFinite(lat) && Number.isFinite(lng) && lat >= 32.5 && lat <= 39 && lng >= 124 && lng <= 132;
}

/** 받은 고침을 검사해 저장할 글자로. 틀리면 null. data 가 null 이면 "" (지우기) */
export function clean(kind, id, data) {
  if (!KINDS.includes(kind)) return null;
  if (typeof id !== "string" || !/^[0-9A-Za-z가-힣_().,·:\- ]{1,40}$/.test(id)) return null;
  if (data === null) return "";
  if (LINE_KINDS.has(kind)) {
    if (!Array.isArray(data) || data.length < 2 || data.length > MAX_POINTS) return null;
    const pts = [];
    for (const p of data) {
      if (!Array.isArray(p) || !inKorea(+p[0], +p[1])) return null;
      pts.push([+(+p[0]).toFixed(5), +(+p[1]).toFixed(5)]);
    }
    return encode(pts, 5);
  }
  if (!Array.isArray(data) || data.length !== 2 || !inKorea(+data[0], +data[1])) return null;
  return JSON.stringify([+(+data[0]).toFixed(6), +(+data[1]).toFixed(6)]);
}

/** 저장된 줄들 → 내보낼 모양 */
export function bundle(rows) {
  const out = { v: 0, bus: {}, rail: {}, stop: {}, station: {} };
  for (const r of rows) {
    if (!out[r.kind]) continue;
    out[r.kind][r.id] = LINE_KINDS.has(r.kind) ? r.data : JSON.parse(r.data);
    out.v = Math.max(out.v, r.at);
  }
  return out;
}

async function hashIp(ip) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("tagohub-edit:" + ip));
  return [...new Uint8Array(b).slice(0, 8)].map((x) => x.toString(16).padStart(2, "0")).join("");
}

export async function handleEdits(request, env, ctx, path = "/w/edits") {
  if (path === "/w/edits/check") return new Response(JSON.stringify({ ok: isAdmin(request, env) }), { status: isAdmin(request, env) ? 200 : 403, headers: { ...HEAD, "Cache-Control": "no-store" } });
  if (!env.DB) return new Response(JSON.stringify({ v: 0, bus: {}, rail: {}, stop: {}, station: {} }), { headers: HEAD });
  await ensure(env.DB);
  if (request.method === "GET") {
    // 고치면 바로 모두에게 보이게 저장(캐시)하지 않음. 대신 판 번호(고친 기록 마지막 번호)가 같으면 목록 없이 204 로 짧게
    const v = (await env.DB.prepare("SELECT MAX(seq) AS v FROM edit_log").first())?.v || 0;
    const have = new URL(request.url).searchParams.get("v");
    if (have != null && String(v) === have) return new Response(null, { status: 204, headers: { ...HEAD, "Cache-Control": "no-store" } });
    const { results } = await env.DB.prepare("SELECT kind, id, data, at FROM edits").all();
    const body = JSON.stringify({ ...bundle(results || []), v });
    return new Response(body, { headers: { ...HEAD, "Cache-Control": "no-store" } });
  }
  if (request.method !== "POST") return new Response("GET, POST only", { status: 405, headers: HEAD });
  if (!isAdmin(request, env)) return new Response("admin only", { status: 403, headers: HEAD });
  let body;
  try {
    const text = await request.text();
    if (text.length > 120000) return new Response("too large", { status: 413, headers: HEAD });
    body = JSON.parse(text);
  } catch { return new Response("bad json", { status: 400, headers: HEAD }); }
  const data = clean(body?.kind, body?.id, body?.data ?? null);
  if (data == null) return new Response("bad edit", { status: 400, headers: HEAD });
  const now = Math.floor(Date.now() / 1000);
  const prev = await env.DB.prepare("SELECT data FROM edits WHERE kind = ? AND id = ?").bind(body.kind, body.id).first();
  const who = await hashIp(request.headers.get("CF-Connecting-IP") || "unknown");
  const write = data === ""
    ? env.DB.prepare("DELETE FROM edits WHERE kind = ? AND id = ?").bind(body.kind, body.id)
    : env.DB.prepare("INSERT OR REPLACE INTO edits (kind, id, data, at) VALUES (?, ?, ?, ?)").bind(body.kind, body.id, data, now);
  await env.DB.batch([
    write,
    env.DB.prepare("INSERT INTO edit_log (kind, id, data, prev, at, who) VALUES (?, ?, ?, ?, ?, ?)").bind(body.kind, body.id, data || null, prev?.data ?? null, now, who),
  ]);
  const v = (await env.DB.prepare("SELECT MAX(seq) AS v FROM edit_log").first())?.v || 0;
  return new Response(JSON.stringify({ ok: true, at: now, v }), { headers: HEAD });
}

/** 테스트용: 폴리라인 글자 → 점 */
export const points = (poly) => decode(poly, 5);
