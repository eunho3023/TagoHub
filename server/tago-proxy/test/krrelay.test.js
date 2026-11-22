import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { upstreamFetch, handleRelay, relayUrl, _reset } from "../src/krrelay.js";
import { handle, allowedTarget } from "../../relay/relay.mjs";

/** kr_relay 표 하나만 흉내 내는 D1 */
function fakeDb() {
  let row = null;
  return {
    set(r) { row = r; },
    prepare(sql) {
      const st = {
        args: [],
        bind(...a) { st.args = a; return st; },
        async run() { if (/^INSERT/.test(sql)) row = { url: st.args[0], at: st.args[1] }; return {}; },
        async first() { return row; },
      };
      return st;
    },
  };
}
const KEY = "k".repeat(64);
const DG = "https://apis.data.go.kr/1613000/X/y?serviceKey=s&a=1";

beforeEach(() => _reset());

test("중계가 켜져 있으면 공공데이터포털은 중계로", async () => {
  const db = fakeDb(); db.set({ url: "https://abc-def.trycloudflare.com", at: Date.now() });
  const seen = [];
  const fetcher = async (u, init) => { seen.push([u, init.headers["X-Relay-Key"]]); return new Response("ok", { headers: { "X-Relay-Upstream": "200" } }); };
  const r = await upstreamFetch(DG, {}, { DB: db, RELAY_KEY: KEY }, { fetcher });
  assert.equal(await r.text(), "ok");
  assert.equal(r.headers.get("X-Via"), "kr-relay");
  assert.equal(seen.length, 1);
  assert.equal(seen[0][0], "https://abc-def.trycloudflare.com/f?u=" + encodeURIComponent(DG));
  assert.equal(seen[0][1], KEY);
});

test("중계가 실패하면 바로 묻고, 1분 동안은 중계를 건너뜀", async () => {
  const db = fakeDb(); db.set({ url: "https://abc.trycloudflare.com", at: Date.now() });
  const seen = [];
  const fetcher = async (u) => { seen.push(u); if (u.includes("trycloudflare")) throw new Error("down"); return new Response("direct"); };
  const env = { DB: db, RELAY_KEY: KEY };
  assert.equal(await (await upstreamFetch(DG, {}, env, { fetcher })).text(), "direct");
  assert.equal(await (await upstreamFetch(DG, {}, env, { fetcher })).text(), "direct");
  // 중계에 두 번(새 연결로 한 번 더) 물은 뒤 바로, 다음 요청은 중계를 건너뜀
  assert.deepEqual(seen.map((u) => u.includes("trycloudflare")), [true, true, false, false]);
});

test("중계 자체 오류(X-Relay-Upstream 없음)도 바로 묻기로", async () => {
  const db = fakeDb(); db.set({ url: "https://abc.trycloudflare.com", at: Date.now() });
  const fetcher = async (u) => (u.includes("trycloudflare") ? new Response("bad", { status: 502 }) : new Response("direct"));
  assert.equal(await (await upstreamFetch(DG, {}, { DB: db, RELAY_KEY: KEY }, { fetcher })).text(), "direct");
});

test("기관이 낸 오류는 중계 답 그대로", async () => {
  const db = fakeDb(); db.set({ url: "https://abc.trycloudflare.com", at: Date.now() });
  const fetcher = async () => new Response("limit", { status: 429, headers: { "X-Relay-Upstream": "429" } });
  const r = await upstreamFetch(DG, {}, { DB: db, RELAY_KEY: KEY }, { fetcher });
  assert.equal(r.status, 429);
});

test("다른 호스트·POST·꺼진 중계(15분 넘게 소식 없음)는 바로", async () => {
  const db = fakeDb(); db.set({ url: "https://abc.trycloudflare.com", at: Date.now() });
  const seen = [];
  const fetcher = async (u) => { seen.push(u); return new Response("x"); };
  const env = { DB: db, RELAY_KEY: KEY };
  await upstreamFetch("https://bus.jeju.go.kr/a", {}, env, { fetcher });
  await upstreamFetch(DG, { method: "POST" }, env, { fetcher });
  _reset(); db.set({ url: "https://abc.trycloudflare.com", at: Date.now() - 16 * 60 * 1000 });
  await upstreamFetch(DG, {}, env, { fetcher });
  assert.ok(seen.every((u) => !u.includes("trycloudflare")));
  assert.equal(await relayUrl({ DB: db }), null);   // 열쇠가 없으면 중계 안 씀
});

test("주소 알리기: 열쇠·주소 모양을 봄, 상태에는 주소를 내보내지 않음", async () => {
  const db = fakeDb();
  const env = { DB: db, RELAY_KEY: KEY };
  const post = (key, url) => handleRelay(new Request("https://t/w/relay/register", { method: "POST", headers: key ? { "X-Relay-Key": key } : {}, body: JSON.stringify({ url }) }), new URL("https://t/w/relay/register"), env);
  assert.equal((await post("wrong", "https://a.trycloudflare.com")).status, 403);
  assert.equal((await post(null, "https://a.trycloudflare.com")).status, 403);
  assert.equal((await post(KEY, "https://evil.example.com")).status, 400);
  assert.equal((await post(KEY, "https://a-b.trycloudflare.com/")).status, 200);
  assert.equal(await relayUrl(env), "https://a-b.trycloudflare.com");
  const st = await (await handleRelay(new Request("https://t/w/relay/status"), new URL("https://t/w/relay/status"), env)).json();
  assert.equal(st.on, true);
  assert.equal(JSON.stringify(st).includes("trycloudflare"), false);
});

test("PC 중계: 열쇠·허락한 호스트만, 기관 답에 X-Relay-Upstream", async () => {
  const req = (u, key) => ({ url: "/f?u=" + encodeURIComponent(u), method: "GET", headers: key ? { "x-relay-key": key } : {} });
  const fetcher = async () => new Response("<ok/>", { status: 200, headers: { "content-type": "application/xml" } });
  assert.equal((await handle(req(DG, "nope"), KEY, fetcher)).status, 403);
  assert.equal((await handle(req("https://evil.example.com/x", KEY), KEY, fetcher)).status, 400);
  assert.equal((await handle(req("http://apis.data.go.kr/x", KEY), KEY, fetcher)).status, 400);
  const ok = await handle(req(DG, KEY), KEY, fetcher);
  assert.equal(ok.status, 200);
  assert.equal(ok.headers["X-Relay-Upstream"], "200");
  assert.equal(ok.body.toString(), "<ok/>");
  const fail = await handle(req(DG, KEY), KEY, async () => { throw new Error("x"); });
  assert.equal(fail.status, 502);
  assert.equal(fail.headers["X-Relay-Upstream"], "0");   // 중계는 살아 있음 (기관 쪽 문제)
  assert.equal(allowedTarget("https://apis.data.go.kr:8443/x"), false);
});

test("기관이 늦으면 2.5초 뒤 중계에 한 번 더 묻고 빠른 답을 씀", async () => {
  const db = fakeDb(); db.set({ url: "https://abc.trycloudflare.com", at: Date.now() });
  let n = 0;
  const fetcher = async (u, init) => {
    const i = n++;
    await new Promise((r, j) => { const t = setTimeout(r, i === 0 ? 2000 : 5); init.signal.addEventListener("abort", () => { clearTimeout(t); j(new Error("abort")); }); });
    return new Response("n" + i, { headers: { "X-Relay-Upstream": "200" } });
  };
  const r = await upstreamFetch(DG, {}, { DB: db, RELAY_KEY: KEY }, { fetcher, hedgeMs: 30, totalMs: 1000 });
  assert.equal(await r.text(), "n1");
  assert.equal(r.headers.get("X-Via"), "kr-relay");
});

test("중계가 기관 오류(X-Relay-Upstream 0)를 내도 중계는 살아 있는 것으로", async () => {
  const db = fakeDb(); db.set({ url: "https://abc.trycloudflare.com", at: Date.now() });
  const seen = [];
  const fetcher = async (u) => { seen.push(u); return new Response("x", { status: 504, headers: { "X-Relay-Upstream": "0" } }); };
  const env = { DB: db, RELAY_KEY: KEY };
  assert.equal((await upstreamFetch(DG, {}, env, { fetcher, hedgeMs: 10 })).status, 504);
  await upstreamFetch(DG, {}, env, { fetcher, hedgeMs: 10 });
  assert.ok(seen.every((u) => u.includes("trycloudflare")));   // 바로 묻기로 넘어가지 않음
});

test("중계가 전혀 답이 없으면 504 로 끝내고(바로 묻기로 더 기다리지 않음) 1분 건너뜀", async () => {
  const db = fakeDb(); db.set({ url: "https://abc.trycloudflare.com", at: Date.now() });
  const seen = [];
  const fetcher = (u, init) => { seen.push(u); return new Promise((r, j) => init.signal.addEventListener("abort", () => j(new Error("abort")))); };
  const env = { DB: db, RELAY_KEY: KEY };
  const r = await upstreamFetch(DG, {}, env, { fetcher, hedgeMs: 10, totalMs: 60 });
  assert.equal(r.status, 504);
  assert.ok(seen.every((u) => u.includes("trycloudflare")));
  const fast = async (u) => { seen.push(u); return new Response("direct"); };
  assert.equal(await (await upstreamFetch(DG, {}, env, { fetcher: fast })).text(), "direct");
});
