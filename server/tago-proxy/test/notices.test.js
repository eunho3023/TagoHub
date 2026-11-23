import { test } from "node:test";
import assert from "node:assert/strict";
import { cleanNotice, merge, fileKey, handleNotices } from "../src/notices.js";

test("공지 검사: 날짜·제목은 꼭, 길이 제한", () => {
  assert.deepEqual(cleanNotice({ date: "2026-11-23", title: " 제목 ", body: "가\r\n나" }), { date: "2026-11-23", title: "제목", body: "가\n나" });
  assert.equal(cleanNotice({ date: "2026-13-40", title: "x" }), null);
  assert.equal(cleanNotice({ date: "11/23", title: "x" }), null);
  assert.equal(cleanNotice({ date: "2026-11-23", title: "" }), null);
  assert.equal(cleanNotice({ date: "2026-11-23", title: "가".repeat(81) }), null);
  assert.equal(cleanNotice({ date: "2026-11-23", title: "x", body: "가".repeat(4001) }), null);
});

test("합치기: 파일 공지 + 쓴 공지, 지운 것·고쳐 쓴 파일 공지는 빠짐, 최근 먼저", () => {
  const file = [{ date: "2026-11-20", title: "옛 공지", body: "a" }, { date: "2026-11-22", title: "숨길 공지", body: "b" }, { date: "2026-11-21", title: "고칠 공지" }];
  const rows = [
    { id: 1, date: "2026-11-23", title: "새 공지", body: "c", at: 5, replaces: null, deleted: 0 },
    { id: 2, date: "2026-11-23", title: "지운 공지", body: "d", at: 6, replaces: null, deleted: 1 },
    { id: 3, date: "", title: "", body: "", at: 7, replaces: fileKey(file[1]), deleted: 1 },
    { id: 4, date: "2026-11-21", title: "고친 공지", body: "e", at: 8, replaces: fileKey(file[2]), deleted: 0 },
    { id: 5, date: "2026-11-23", title: "같은 날 나중", body: "f", at: 9, replaces: null, deleted: 0 },
  ];
  const out = merge(file, rows);
  assert.deepEqual(out.map((n) => n.title), ["같은 날 나중", "새 공지", "고친 공지", "옛 공지"]);
  assert.equal(out[0].id, 5);
  assert.equal(out[3].key, "f:2026-11-20|옛 공지");
  assert.equal("at" in out[0], false);
});

/** 시험용 D1 (notices 표만) */
function fakeDb() {
  const rows = [];
  let seq = 0;
  const run = (sql, a) => {
    if (sql.startsWith("CREATE")) return { meta: { changes: 0 } };
    if (sql.startsWith("INSERT")) {
      const del = /1\)$/.test(sql.trim()) && sql.includes("'', '', ''");
      const r = del ? { id: ++seq, date: "", title: "", body: "", at: a[0], replaces: a[1], deleted: 1 } : { id: ++seq, date: a[0], title: a[1], body: a[2], at: a[3], replaces: a[4], deleted: 0 };
      rows.push(r);
      return { meta: { changes: 1 } };
    }
    if (sql.startsWith("UPDATE notices SET deleted")) { const r = rows.find((x) => x.id === a[1]); if (r) { r.deleted = 1; r.at = a[0]; } return { meta: { changes: r ? 1 : 0 } }; }
    if (sql.startsWith("UPDATE")) { const r = rows.find((x) => x.id === a[4] && !x.deleted); if (r) Object.assign(r, { date: a[0], title: a[1], body: a[2], at: a[3] }); return { meta: { changes: r ? 1 : 0 } }; }
    throw new Error(sql);
  };
  return {
    rows,
    prepare(sql) {
      const st = { a: [], bind(...a) { st.a = a; return st; }, async run() { return run(sql, st.a); }, async all() { return { results: rows.map((r) => ({ ...r })) }; } };
      return st;
    },
  };
}
const KEY = "k".repeat(20);
const file = [{ date: "2026-11-20", title: "파일 공지", body: "a" }];
const fetcher = async () => new Response(JSON.stringify(file));
const post = (body, key = KEY) => new Request("https://x/w/notices", { method: "POST", headers: key ? { "X-TagoHub-Admin": key } : {}, body: JSON.stringify(body) });
const opts = { fetcher, devCheck: async () => false };

test("공지 쓰기: 관리자만, 쓰고·고치고·지우기, 파일 공지 고치기·숨기기", async () => {
  const env = { DB: fakeDb(), EDIT_ADMIN_KEY: KEY };
  assert.equal((await handleNotices(post({ date: "2026-11-23", title: "x" }, "w".repeat(20)), env, opts)).status, 403);
  assert.equal((await handleNotices(post({ date: "2026-11-23", title: "x" }, null), env, opts)).status, 403);
  assert.equal((await handleNotices(post({ date: "bad", title: "x" }), env, opts)).status, 400);

  let r = await (await handleNotices(post({ date: "2026-11-23", title: "새 공지", body: "내용" }), env, opts)).json();
  assert.deepEqual(r.notices.map((n) => n.title), ["새 공지", "파일 공지"]);
  const id = r.notices[0].id;

  r = await (await handleNotices(post({ id, date: "2026-11-24", title: "고친 공지", body: "내용2" }), env, opts)).json();
  assert.equal(r.notices[0].title, "고친 공지");
  assert.equal(r.notices[0].date, "2026-11-24");

  r = await (await handleNotices(post({ key: "f:2026-11-20|파일 공지", date: "2026-11-20", title: "파일 공지(고침)", body: "b" }), env, opts)).json();
  assert.deepEqual(r.notices.map((n) => n.title), ["고친 공지", "파일 공지(고침)"]);

  r = await (await handleNotices(post({ id, delete: true }), env, opts)).json();
  assert.deepEqual(r.notices.map((n) => n.title), ["파일 공지(고침)"]);

  const env2 = { DB: fakeDb(), EDIT_ADMIN_KEY: KEY };
  r = await (await handleNotices(post({ key: "f:2026-11-20|파일 공지", delete: true }), env2, opts)).json();
  assert.deepEqual(r.notices, []);

  const got = await handleNotices(new Request("https://x/w/notices"), env, opts);
  assert.equal(got.status, 200);
  assert.deepEqual((await got.json()).map((n) => n.title), ["파일 공지(고침)"]);
  assert.equal((await handleNotices(post({ id: 999, date: "2026-11-23", title: "x" }), env, opts)).status, 404);
});
