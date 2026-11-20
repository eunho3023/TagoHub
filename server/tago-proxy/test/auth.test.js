import { test } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { handleAuth, normUsername, normPhone, mask, verifyIdToken, isDevRequest } from "../src/auth.js";

/** D1 흉내 (node:sqlite) */
function d1() {
  const db = new DatabaseSync(":memory:");
  const stmt = (sql, args = []) => ({
    bind: (...a) => stmt(sql, a),
    first: async () => db.prepare(sql).get(...args) ?? null,
    all: async () => ({ results: db.prepare(sql).all(...args) }),
    run: async () => ({ meta: { changes: Number(db.prepare(sql).run(...args).changes) } }),
  });
  return { prepare: (sql) => stmt(sql), batch: async (list) => { for (const s of list) await s.run(); } };
}

const enc = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
const PROJECT = "tagohub-test";

/** 가짜 Firebase: RSA 키로 토큰을 만들고, 공개키·비밀번호 확인을 흉내 */
async function firebase() {
  const pair = await crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);
  const kid = "k" + Math.random().toString(36).slice(2);
  const jwk = { ...(await crypto.subtle.exportKey("jwk", pair.publicKey)), kid, alg: "RS256", use: "sig" };
  const passwords = new Map();
  const mails = [];
  let seq = 0;
  const token = async (claims) => {
    const now = Math.floor(Date.now() / 1000);
    const head = enc({ alg: "RS256", kid });
    const body = enc({ aud: PROJECT, iss: "https://securetoken.google.com/" + PROJECT, iat: now, exp: now + 3600, ...claims });
    const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", pair.privateKey, new TextEncoder().encode(head + "." + body));
    return head + "." + body + "." + Buffer.from(sig).toString("base64url");
  };
  const fetcher = async (url, init) => {
    if (String(url).includes("jwk")) return new Response(JSON.stringify({ keys: [jwk] }));
    if (String(url).includes("signInWithPassword")) {
      const { email, password } = JSON.parse(init.body);
      return passwords.get(email) === password ? new Response(JSON.stringify({ idToken: "ID:" + email, refreshToken: "RT:" + email })) : new Response('{"error":{"message":"INVALID_LOGIN_CREDENTIALS"}}', { status: 400 });
    }
    if (String(url).includes("accounts:signUp")) {
      const { email, password } = JSON.parse(init.body);
      if (passwords.has(email)) return new Response('{"error":{"message":"EMAIL_EXISTS"}}', { status: 400 });
      passwords.set(email, password);
      return new Response(JSON.stringify({ localId: "W" + ++seq, idToken: "ID:" + email, refreshToken: "RT:" + email }));
    }
    if (String(url).includes("sendOobCode")) { mails.push(JSON.parse(init.body).email); return new Response("{}"); }
    if (String(url).includes("securetoken.googleapis.com/v1/token")) {
      const rt = new URLSearchParams(init.body).get("refresh_token");
      return rt.startsWith("RT:") ? new Response(JSON.stringify({ id_token: "ID2", refresh_token: rt })) : new Response("{}", { status: 400 });
    }
    return new Response("?", { status: 404 });
  };
  return { token, fetcher, passwords, mails };
}

function call(env, fb, op, body, ip = "1.2.3.4") {
  const url = new URL("https://x/w/auth/" + op);
  const req = new Request(url, { method: "POST", body: JSON.stringify(body), headers: { "CF-Connecting-IP": ip } });
  return handleAuth(req, url, env, {}, fb.fetcher).then(async (r) => [r.status, await r.json()]);
}

test("아이디·전화번호 정리와 가리기", () => {
  assert.equal(normUsername("EunHo_3023"), "eunho_3023");
  assert.equal(normUsername("ab"), null);
  assert.equal(normUsername("한글아이디"), null);
  assert.equal(normPhone("010-1234-5678"), "+821012345678");
  assert.equal(normPhone("+82 10 1234 5678"), "+821012345678");
  assert.equal(normPhone("+8201012345678"), "+821012345678");
  assert.equal(normPhone("1234"), null);
  assert.equal(mask("eunho3023"), "eu******3");
  assert.equal(mask("abc"), "a**");
});

test("토큰 확인: 서명·프로젝트·만료", async () => {
  const fb = await firebase();
  const env = { FIREBASE_PROJECT_ID: PROJECT };
  const who = await verifyIdToken(await fb.token({ sub: "U1", email: "A@x.com", phone_number: "+821012345678" }), env, fb.fetcher);
  assert.deepEqual(who, { uid: "U1", email: "a@x.com", phone: "+821012345678" });
  assert.equal(await verifyIdToken(await fb.token({ sub: "U1", aud: "other" }), env, fb.fetcher), null);
  assert.equal(await verifyIdToken(await fb.token({ sub: "U1", exp: 1 }), env, fb.fetcher), null);
  const t = await fb.token({ sub: "U1" });
  assert.equal(await verifyIdToken(t.slice(0, -4) + "AAAA", env, fb.fetcher), null);   // 서명 망가짐
});

test("가입 → 아이디 로그인 → 아이디 찾기 → 비밀번호 확인 → 개발자", async () => {
  const fb = await firebase();
  const env = { DB: d1(), FIREBASE_PROJECT_ID: PROJECT, FIREBASE_API_KEY: "k", DEV_IDS: "eunho, other" };
  fb.passwords.set("me@x.com", "secret12");
  const tok = await fb.token({ sub: "U1", email: "me@x.com", phone_number: "+821012345678" });

  assert.deepEqual(await call(env, fb, "check", { username: "Eunho" }), [200, { free: true }]);
  assert.deepEqual(await call(env, fb, "register", { idToken: tok, username: "Eunho", phone: "010-0000-0000" }), [200, { username: "eunho" }]);
  assert.deepEqual(await call(env, fb, "check", { username: "eunho" }), [200, { free: false }]);
  // 같은 아이디를 다른 계정이: 이미 있음
  const tok2 = await fb.token({ sub: "U2", email: "you@x.com" });
  assert.equal((await call(env, fb, "register", { idToken: tok2, username: "eunho" }))[0], 409);
  assert.equal((await call(env, fb, "register", { idToken: "garbage", username: "zzzz" }))[0], 401);

  assert.deepEqual(await call(env, fb, "login", { username: "EUNHO", password: "secret12" }), [200, { email: "me@x.com" }]);
  assert.equal((await call(env, fb, "login", { username: "eunho", password: "nope" }))[0], 401);
  assert.equal((await call(env, fb, "login", { username: "nobody", password: "x" }))[0], 401);

  // 문자로 인증한 번호(토큰)로 저장됨: 입력한 010-0000-0000 이 아니라
  assert.deepEqual(await call(env, fb, "find", { phone: "010-1234-5678" }), [200, { ids: ["eu**o"] }]);
  assert.deepEqual(await call(env, fb, "find", { phone: "010-0000-0000" }), [200, { ids: [] }]);
  assert.deepEqual(await call(env, fb, "find", { email: "ME@x.com" }), [200, { ids: ["eu**o"] }]);

  assert.deepEqual(await call(env, fb, "reset", { username: "eunho", phone: "01012345678" }), [200, { ok: true, phoneOk: true }]);
  assert.deepEqual(await call(env, fb, "reset", { username: "eunho", email: "me@x.com" }), [200, { ok: true, phoneOk: false }]);
  assert.deepEqual(await call(env, fb, "reset", { username: "eunho", email: "you@x.com" }), [200, { ok: false, phoneOk: false }]);

  assert.deepEqual(await call(env, fb, "me", { idToken: tok }), [200, { username: "eunho", dev: true }]);
  assert.deepEqual(await call(env, fb, "me", { idToken: tok2 }), [200, { username: null, dev: false }]);

  const req = (t) => new Request("https://x/w/edits", { method: "POST", headers: { Authorization: "Bearer " + t } });
  assert.equal(await isDevRequest(req(tok), env, fb.fetcher), true);
  assert.equal(await isDevRequest(req(tok2), env, fb.fetcher), false);
});

test("웹: 가입 → 로그인(토큰) → 토큰 새로 → 비밀번호 메일", async () => {
  const fb = await firebase();
  const env = { DB: d1(), FIREBASE_PROJECT_ID: PROJECT, FIREBASE_API_KEY: "k", DEV_IDS: "webdev" };
  const join = (b) => call(env, fb, "signup", { username: "webdev", password: "abcd1234", email: "w@x.com", phone: "010-2222-3333", ...b });
  assert.equal((await join({ password: "short1" }))[0], 400);
  assert.equal((await join({ password: "onlyletters" }))[0], 400);
  assert.equal((await join({ email: "nope" }))[0], 400);
  assert.equal((await join({ phone: "12" }))[0], 400);
  assert.deepEqual(await join({}), [200, { username: "webdev", dev: true, email: "w@x.com", idToken: "ID:w@x.com", refreshToken: "RT:w@x.com" }]);
  assert.deepEqual(await join({}), [409, { error: "taken" }]);
  assert.deepEqual(await join({ username: "other1" }), [409, { error: "email" }]);   // 이미 쓰는 이메일

  assert.deepEqual(await call(env, fb, "login", { username: "webdev", password: "abcd1234" }), [200, { email: "w@x.com" }]);   // 앱은 그대로
  assert.deepEqual(await call(env, fb, "login", { username: "webdev", password: "abcd1234", web: true }),
    [200, { email: "w@x.com", username: "webdev", dev: true, idToken: "ID:w@x.com", refreshToken: "RT:w@x.com" }]);
  assert.deepEqual(await call(env, fb, "refresh", { refreshToken: "RT:w@x.com" }), [200, { idToken: "ID2", refreshToken: "RT:w@x.com" }]);
  assert.equal((await call(env, fb, "refresh", { refreshToken: "bad" }))[0], 401);

  // 전화번호는 인증 안 된 것으로: 아이디 찾기는 되고, 문자로 비밀번호 바꾸기는 안 됨
  assert.deepEqual(await call(env, fb, "find", { phone: "01022223333" }), [200, { ids: ["we***v"] }]);
  assert.deepEqual(await call(env, fb, "reset", { username: "webdev", phone: "01022223333" }), [200, { ok: true, phoneOk: false }]);
  assert.deepEqual(await call(env, fb, "resetmail", { username: "webdev", email: "x@x.com" }), [200, { ok: false }]);
  assert.deepEqual(fb.mails, []);
  assert.deepEqual(await call(env, fb, "resetmail", { username: "webdev", email: "W@x.com" }), [200, { ok: true }]);
  assert.deepEqual(fb.mails, ["w@x.com"]);
});

test("로그인 실패 10번이면 15분 막힘, 아이디 찾기는 주소마다 30번", async () => {
  const fb = await firebase();
  const env = { DB: d1(), FIREBASE_PROJECT_ID: PROJECT, FIREBASE_API_KEY: "k" };
  fb.passwords.set("me@x.com", "secret12");
  await call(env, fb, "register", { idToken: await fb.token({ sub: "U1", email: "me@x.com" }), username: "eunho" });
  for (let i = 0; i < 10; i++) assert.equal((await call(env, fb, "login", { username: "eunho", password: "bad" }))[0], 401);
  assert.equal((await call(env, fb, "login", { username: "eunho", password: "secret12" }))[0], 429);
  for (let i = 0; i < 30; i++) assert.equal((await call(env, fb, "find", { email: "a@b.co" }, "9.9.9.9"))[0], 200);
  assert.equal((await call(env, fb, "find", { email: "a@b.co" }, "9.9.9.9"))[0], 429);
  assert.equal((await call(env, fb, "find", { email: "a@b.co" }, "8.8.8.8"))[0], 200);
});
