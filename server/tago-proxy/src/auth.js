// 아이디 회원: 앱의 Firebase 계정(이메일·비밀번호, 문자 인증한 전화번호)에 아이디를 붙여 아이디로 로그인하게 함.
// 계정 자체(비밀번호·인증)는 Firebase 가 맡고, 이 서버는 아이디 ↔ 계정 연결만 D1(accounts)에 둔다.
//
// POST /w/auth/check    {username}                  → {free}                       아이디를 쓸 수 있는지
// POST /w/auth/register {idToken, username, phone}  → {username}                   가입 마치기 (토큰의 이메일·인증된 전화번호를 씀)
// POST /w/auth/login    {username, password}        → {email}                      비밀번호가 맞을 때만 Firebase 로그인용 이메일을 알려 줌
// POST /w/auth/find     {phone} | {email}           → {ids: ["ab***9"]}            아이디 찾기 (가운데를 가림)
// POST /w/auth/reset    {username, phone|email}     → {ok, phoneOk}                비밀번호 바꾸기 전에 아이디와 연락처가 맞는지
// POST /w/auth/me       {idToken}                   → {username, dev}              로그인한 계정의 아이디·개발자인지
//
// 개발자: 비밀 값 DEV_IDS(쉼표로 아이디들)에 든 아이디. 개발자는 앱에서 내비·지도 고치기를 쓸 수 있음(src/edits.js 도 이 토큰을 받음).
// 전화번호는 해시만 저장. 로그인 실패는 아이디마다 15분에 10번까지.

import { androidHeaders } from "./report.js";

const HEAD = { "Content-Type": "application/json; charset=utf-8", "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "POST", "Access-Control-Allow-Headers": "Content-Type", "Cache-Control": "no-store" };
const USERNAME_RE = /^[a-z0-9_]{4,20}$/;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const FAIL_MAX = 10;
const FAIL_WINDOW = 15 * 60;
const LOOK_MAX = 30;
const JWKS_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

const json = (v, status = 200) => new Response(JSON.stringify(v), { status, headers: HEAD });

/** 아이디: 영어 소문자·숫자·밑줄 4~20자 (대문자는 소문자로) */
export function normUsername(s) {
  const u = String(s || "").trim().toLowerCase();
  return USERNAME_RE.test(u) ? u : null;
}

/** 전화번호 → +82 형식 (010-1234-5678, 01012345678, +821012345678). 아니면 null */
export function normPhone(s) {
  const d = String(s || "").replace(/[\s\-()]/g, "");
  if (/^\+82\d{8,11}$/.test(d)) return "+82" + d.slice(3).replace(/^0/, "");
  if (/^0\d{8,10}$/.test(d)) return "+82" + d.slice(1);
  return null;
}

export function normEmail(s) {
  const e = String(s || "").trim().toLowerCase();
  return EMAIL_RE.test(e) ? e : null;
}

/** 아이디 가운데 가리기: "eunho3023" → "eu******3" */
export function mask(u) {
  if (u.length <= 3) return u[0] + "*".repeat(u.length - 1);
  return u.slice(0, 2) + "*".repeat(u.length - 3) + u.slice(-1);
}

export async function sha(text) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
}
const phoneHash = (e164) => sha("tagohub-phone:" + e164);

export function devIds(env) {
  return new Set(String(env.DEV_IDS || "").split(",").map((s) => s.trim().toLowerCase()).filter(Boolean));
}

const made = new WeakSet();
async function ensure(db) {
  if (made.has(db)) return;
  await db.batch([
    db.prepare("CREATE TABLE IF NOT EXISTS accounts (username TEXT PRIMARY KEY, uid TEXT NOT NULL UNIQUE, email TEXT NOT NULL, phone TEXT, phone_ok INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL) WITHOUT ROWID"),
    db.prepare("CREATE INDEX IF NOT EXISTS accounts_email ON accounts (email)"),
    db.prepare("CREATE INDEX IF NOT EXISTS accounts_phone ON accounts (phone)"),
    db.prepare("CREATE TABLE IF NOT EXISTS auth_fail (key TEXT PRIMARY KEY, n INTEGER NOT NULL, until INTEGER NOT NULL) WITHOUT ROWID"),
  ]);
  made.add(db);
}

// ── Firebase ID 토큰 확인 (RS256, 구글 공개키) ──

let jwks = null;
let jwksAt = 0;

function b64url(s) {
  const t = s.replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(t + "=".repeat((4 - (t.length % 4)) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

async function keys(fetcher, kid) {
  // 구글이 키를 바꾸면 모르는 kid 가 옴 → 다시 받음
  if (jwks && Date.now() - jwksAt < 3600_000 && jwks.some((k) => k.kid === kid)) return jwks;
  const r = await fetcher(JWKS_URL);
  if (!r.ok) throw new Error("jwks " + r.status);
  jwks = (await r.json()).keys || [];
  jwksAt = Date.now();
  return jwks;
}

/** 토큰이 맞으면 {uid, email, phone}, 아니면 null */
export async function verifyIdToken(token, env, fetcher = fetch, now = Date.now() / 1000) {
  const project = String(env.FIREBASE_PROJECT_ID || "").trim();
  const parts = String(token || "").split(".");
  if (!project || parts.length !== 3) return null;
  try {
    const head = JSON.parse(new TextDecoder().decode(b64url(parts[0])));
    const body = JSON.parse(new TextDecoder().decode(b64url(parts[1])));
    if (head.alg !== "RS256") return null;
    if (body.aud !== project || body.iss !== "https://securetoken.google.com/" + project) return null;
    if (!body.sub || body.exp < now || body.iat > now + 300) return null;
    const jwk = (await keys(fetcher, head.kid)).find((k) => k.kid === head.kid);
    if (!jwk) return null;
    const key = await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
    const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, b64url(parts[2]), new TextEncoder().encode(parts[0] + "." + parts[1]));
    if (!ok) return null;
    return { uid: body.sub, email: body.email ? String(body.email).toLowerCase() : null, phone: body.phone_number || null };
  } catch {
    return null;
  }
}

/** 요청의 "Authorization: Bearer <Firebase 토큰>" 이 개발자 계정인지 (src/edits.js) */
export async function isDevRequest(request, env, fetcher = fetch) {
  const m = /^Bearer\s+(\S+)$/.exec(request.headers.get("Authorization") || "");
  if (!m || !env.DB) return false;
  const who = await verifyIdToken(m[1], env, fetcher);
  if (!who) return false;
  await ensure(env.DB);
  const row = await env.DB.prepare("SELECT username FROM accounts WHERE uid = ?").bind(who.uid).first();
  return !!row && devIds(env).has(row.username);
}

// ── 비밀번호 확인 (Firebase REST) ──

async function passwordOk(email, password, env, fetcher) {
  const key = String(env.FIREBASE_API_KEY || "").trim();
  if (!key) throw new Error("no firebase key");
  const r = await fetcher("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" + encodeURIComponent(key), {
    method: "POST",
    headers: { "Content-Type": "application/json", ...androidHeaders(env) },
    body: JSON.stringify({ email, password, returnSecureToken: false }),
  });
  if (r.ok) return true;
  const t = await r.text();
  if (/INVALID_PASSWORD|INVALID_LOGIN_CREDENTIALS|EMAIL_NOT_FOUND|USER_DISABLED/.test(t)) return false;
  throw new Error("firebase " + r.status);
}

async function failed(db, key, now) {
  const row = await db.prepare("SELECT n, until FROM auth_fail WHERE key = ?").bind(key).first();
  return !!row && row.until > now && row.n >= FAIL_MAX;
}

async function noteFail(db, key, now) {
  await db.prepare("INSERT INTO auth_fail (key, n, until) VALUES (?, 1, ?) ON CONFLICT(key) DO UPDATE SET n = CASE WHEN until > ? THEN n + 1 ELSE 1 END, until = CASE WHEN until > ? THEN until ELSE ? END")
    .bind(key, now + FAIL_WINDOW, now, now, now + FAIL_WINDOW).run();
}

export async function handleAuth(request, url, env, ctx, fetcher = fetch) {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: HEAD });
  if (request.method !== "POST") return json({ error: "POST only" }, 405);
  if (!env.DB) return json({ error: "no db" }, 503);
  let body;
  try {
    const t = await request.text();
    if (t.length > 8000) return json({ error: "too large" }, 413);
    body = JSON.parse(t || "{}");
  } catch { return json({ error: "bad json" }, 400); }
  await ensure(env.DB);
  const db = env.DB;
  const op = url.pathname.slice("/w/auth/".length);
  const now = Math.floor(Date.now() / 1000);

  if (op === "check") {
    const u = normUsername(body.username);
    if (!u) return json({ error: "bad username" }, 400);
    const row = await db.prepare("SELECT 1 AS x FROM accounts WHERE username = ?").bind(u).first();
    return json({ free: !row });
  }

  if (op === "register") {
    const who = await verifyIdToken(body.idToken, env, fetcher);
    if (!who) return json({ error: "bad token" }, 401);
    const u = normUsername(body.username);
    if (!u) return json({ error: "bad username" }, 400);
    if (!who.email) return json({ error: "no email" }, 400);
    const mine = await db.prepare("SELECT username FROM accounts WHERE uid = ?").bind(who.uid).first();
    if (mine) return mine.username === u ? json({ username: u }) : json({ error: "already", username: mine.username }, 409);
    // 문자로 인증한 전화번호(토큰에 있음)가 우선. 없으면 입력한 번호를 인증 안 된 것으로
    const phone = who.phone ? normPhone(who.phone) : normPhone(body.phone);
    const r = await db.prepare("INSERT INTO accounts (username, uid, email, phone, phone_ok, created) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(username) DO NOTHING")
      .bind(u, who.uid, who.email, phone ? await phoneHash(phone) : null, who.phone ? 1 : 0, now).run();
    if (!r.meta || r.meta.changes !== 1) return json({ error: "taken" }, 409);
    return json({ username: u });
  }

  if (op === "login") {
    const u = normUsername(body.username);
    const pw = String(body.password || "");
    if (!u || !pw) return json({ error: "wrong" }, 401);
    if (await failed(db, "login:" + u, now)) return json({ error: "too many" }, 429);
    const row = await db.prepare("SELECT email FROM accounts WHERE username = ?").bind(u).first();
    let ok = false;
    if (row) {
      try { ok = await passwordOk(row.email, pw, env, fetcher); } catch { return json({ error: "server" }, 502); }
    }
    if (!ok) { await noteFail(db, "login:" + u, now); return json({ error: "wrong" }, 401); }
    await db.prepare("DELETE FROM auth_fail WHERE key = ?").bind("login:" + u).run();
    return json({ email: row.email });
  }

  // 아이디 찾기·비밀번호 확인은 주소(IP)마다 15분에 30번까지 (남의 연락처로 아이디를 훑지 못하게)
  if (op === "find" || op === "reset") {
    const ip = "look:" + (await sha(request.headers.get("CF-Connecting-IP") || "unknown")).slice(0, 16);
    const row = await db.prepare("SELECT n, until FROM auth_fail WHERE key = ?").bind(ip).first();
    if (row && row.until > now && row.n >= LOOK_MAX) return json({ error: "too many" }, 429);
    await noteFail(db, ip, now);
  }

  if (op === "find") {
    const phone = normPhone(body.phone);
    const email = normEmail(body.email);
    if (!phone && !email) return json({ error: "bad input" }, 400);
    const { results } = phone
      ? await db.prepare("SELECT username FROM accounts WHERE phone = ? ORDER BY created").bind(await phoneHash(phone)).all()
      : await db.prepare("SELECT username FROM accounts WHERE email = ? ORDER BY created").bind(email).all();
    return json({ ids: (results || []).map((r) => mask(r.username)) });
  }

  if (op === "reset") {
    const u = normUsername(body.username);
    if (!u) return json({ ok: false });
    const row = await db.prepare("SELECT email, phone, phone_ok FROM accounts WHERE username = ?").bind(u).first();
    if (!row) return json({ ok: false });
    const phone = normPhone(body.phone);
    const email = normEmail(body.email);
    const ok = phone ? row.phone === (await phoneHash(phone)) : !!email && row.email === email;
    return json({ ok, phoneOk: ok && !!phone && row.phone_ok === 1 });
  }

  if (op === "me") {
    const who = await verifyIdToken(body.idToken, env, fetcher);
    if (!who) return json({ error: "bad token" }, 401);
    const row = await db.prepare("SELECT username FROM accounts WHERE uid = ?").bind(who.uid).first();
    return json({ username: row ? row.username : null, dev: !!row && devIds(env).has(row.username) });
  }

  return json({ error: "not found" }, 404);
}
