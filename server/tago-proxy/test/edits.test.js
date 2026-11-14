import { test } from "node:test";
import assert from "node:assert/strict";
import { clean, bundle, points, handleEdits, isAdmin } from "../src/edits.js";

test("고침 검사: 종류·id·좌표 범위", () => {
  assert.equal(clean("bus", "JUB305001764", [[35.8, 127.1], [35.81, 127.12]]) != null, true);
  assert.equal(clean("bus", "JUB1", [[35.8, 127.1]]), null);                 // 점 하나짜리 선
  assert.equal(clean("bus", "JUB1", [[10, 127.1], [35.8, 127.1]]), null);    // 나라 밖
  assert.equal(clean("hack", "x", [35.8, 127.1]), null);                      // 없는 종류
  assert.equal(clean("stop", "a;drop", [35.8, 127.1]), null);                  // 이상한 id
  assert.equal(clean("stop", "JJB1", [35.8123456, 127.1]), "[35.812346,127.1]");
  assert.equal(clean("station", "2호선 강남", null), "");                      // 원래대로
  assert.equal(clean("rail", "2호선", [[37.5, 127.0], [37.51, 127.03]]) != null, true);
});

test("선 고침은 폴리라인으로 저장해 그대로 돌려받음", () => {
  const poly = clean("bus", "R1", [[35.8, 127.1], [35.81, 127.12], [35.82, 127.13]]);
  assert.deepEqual(points(poly), [[35.8, 127.1], [35.81, 127.12], [35.82, 127.13]]);
  const b = bundle([{ kind: "bus", id: "R1", data: poly, at: 5 }, { kind: "stop", id: "S1", data: "[35.8,127.1]", at: 9 }, { kind: "x", id: "?", data: "", at: 99 }]);
  assert.equal(b.bus.R1, poly);
  assert.deepEqual(b.stop.S1, [35.8, 127.1]);
  assert.equal(b.v, 9);
});

/** 메모리 D1 흉내 (edits·edit_log 만) */
function fakeDb() {
  const edits = new Map(), log = [];
  const stmt = (sql, args = []) => ({
    sql, args,
    bind: (...a) => stmt(sql, a),
    async run() { return exec(sql, args); },
    async first() { return exec(sql, args)[0] ?? null; },
    async all() { return { results: exec(sql, args) }; },
  });
  function exec(sql, a) {
    if (sql.startsWith("CREATE")) return [];
    if (sql.startsWith("SELECT kind")) return [...edits.values()];
    if (sql.startsWith("SELECT data")) return edits.has(a[0] + "|" + a[1]) ? [edits.get(a[0] + "|" + a[1])] : [];
    if (sql.startsWith("DELETE")) { edits.delete(a[0] + "|" + a[1]); return []; }
    if (sql.startsWith("INSERT OR REPLACE")) { edits.set(a[0] + "|" + a[1], { kind: a[0], id: a[1], data: a[2], at: a[3] }); return []; }
    if (sql.startsWith("INSERT INTO edit_log")) { log.push(a); return []; }
    throw new Error(sql);
  }
  return { prepare: (sql) => stmt(sql), batch: async (list) => { for (const s of list) await s.run(); }, log };
}

test("저장 → 목록에 보임 → 원래대로 지우면 사라지고 기록은 남음", async () => {
  globalThis.caches = { default: { match: async () => null, put: async () => {}, delete: async () => true } };
  const KEY = "k".repeat(32);
  const env = { DB: fakeDb(), EDIT_ADMIN_KEY: KEY };
  const ctx = { waitUntil() {} };
  const post = (body, key = KEY) => handleEdits(new Request("https://x/w/edits", { method: "POST", headers: { "X-TagoHub-Admin": key }, body: JSON.stringify(body) }), env, ctx);
  // 관리자 키가 없거나 틀리면 못 고침
  assert.equal((await post({ kind: "stop", id: "JJB100", data: [35.18, 128.08] }, "")).status, 403);
  assert.equal((await post({ kind: "stop", id: "JJB100", data: [35.18, 128.08] }, "x".repeat(32))).status, 403);
  assert.equal((await handleEdits(new Request("https://x/w/edits/check", { headers: { "X-TagoHub-Admin": KEY } }), env, ctx, "/w/edits/check")).status, 200);
  const get = async () => (await handleEdits(new Request("https://x/w/edits"), env, ctx)).json();
  assert.equal((await post({ kind: "stop", id: "JJB100", data: [35.18, 128.08] })).status, 200);
  assert.deepEqual((await get()).stop.JJB100, [35.18, 128.08]);
  assert.equal((await post({ kind: "stop", id: "JJB100", data: [0, 0] })).status, 400);
  assert.equal((await post({ kind: "stop", id: "JJB100", data: null })).status, 200);
  assert.equal((await get()).stop.JJB100, undefined);
  assert.equal(env.DB.log.length, 2);
  assert.equal(env.DB.log[1][3], "[35.18,128.08]"); // 지우기 전 값이 기록에
});

test("관리자 키: 서버에 키가 없거나 짧으면 아무도 못 고침", () => {
  const req = (k) => new Request("https://x/w/edits", { headers: { "X-TagoHub-Admin": k } });
  assert.equal(isAdmin(req("a".repeat(32)), {}), false);
  assert.equal(isAdmin(req("short"), { EDIT_ADMIN_KEY: "short" }), false);
  assert.equal(isAdmin(req("a".repeat(32)), { EDIT_ADMIN_KEY: "a".repeat(32) }), true);
});
