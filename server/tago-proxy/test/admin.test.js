import { test } from "node:test";
import assert from "node:assert/strict";
import { admin } from "../src/admin.js";

/** D1 대신 쓰는 작은 메모리 표 (admin.js 가 쓰는 SQL 만 알아본다) */
class FakeDb {
  constructor() {
    this.tables = { layouts: new Map(), route_shapes: new Map() };
    this.created = 0;
  }
  prepare(sql) {
    return new FakeStmt(this, sql);
  }
}

class FakeStmt {
  constructor(db, sql) {
    this.db = db;
    this.sql = sql;
    this.args = [];
  }
  bind(...args) {
    this.args = args;
    return this;
  }
  async run() {
    const s = this.sql;
    if (/^CREATE TABLE/i.test(s)) {
      this.db.created++;
      const name = /CREATE TABLE IF NOT EXISTS (\w+)/i.exec(s)[1];
      if (!this.db.tables[name]) this.db.tables[name] = new Map();
      return { success: true };
    }
    let m = /^INSERT INTO (\w+)/i.exec(s);
    if (m) {
      const [id, tsv, at] = this.args;
      this.db.tables[m[1]].set(id, { route_id: id, tsv, updated_at: at });
      return { success: true };
    }
    m = /^DELETE FROM (\w+)/i.exec(s);
    if (m) {
      this.db.tables[m[1]].delete(this.args[0]);
      return { success: true };
    }
    throw new Error("unexpected run: " + s);
  }
  async all() {
    const s = this.sql;
    let m = /^SELECT tsv FROM (\w+) ORDER BY route_id/i.exec(s);
    if (m) return { results: [...this.db.tables[m[1]].values()].sort((a, b) => (a.route_id < b.route_id ? -1 : 1)) };
    m = /^SELECT route_id id, updated_at at, length\(tsv\) len FROM (\w+) ORDER BY updated_at DESC LIMIT 200/i.exec(s);
    if (m) {
      const rows = [...this.db.tables[m[1]].values()].map((r) => ({ id: r.route_id, at: r.updated_at, len: r.tsv.length }));
      return { results: rows.sort((a, b) => b.at - a.at) };
    }
    throw new Error("unexpected all: " + s);
  }
  async first() {
    const s = this.sql;
    let m = /^SELECT COUNT\(\*\) n, COALESCE\(MAX\(updated_at\), 0\) v FROM (\w+)/i.exec(s);
    if (m) {
      const rows = [...this.db.tables[m[1]].values()];
      return { n: rows.length, v: rows.reduce((x, r) => Math.max(x, r.updated_at), 0) };
    }
    m = /^SELECT tsv, updated_at at FROM (\w+) WHERE route_id = \?/i.exec(s);
    if (m) return this.db.tables[m[1]].get(this.args[0]) || null;
    throw new Error("unexpected first: " + s);
  }
}

const call = (path, init, env) => admin(new Request("https://x" + path, init), new URL("https://x" + path), env);

async function loginCookie(env) {
  const res = await call("/w/admin/login", { method: "POST", body: JSON.stringify({ token: "secret" }) }, env);
  assert.equal(res.status, 200);
  return res.headers.get("Set-Cookie").split(";")[0];
}

test("처음 요청에 배치·선형 표를 만들고, 로그인해야 관리자 API 를 쓴다", async () => {
  const db = new FakeDb();
  const env = { DB: db, ADMIN_TOKEN: "secret" };
  const no = await call("/w/admin/layouts", {}, env);
  assert.equal(no.status, 401);
  const bad = await call("/w/admin/login", { method: "POST", body: JSON.stringify({ token: "nope" }) }, env);
  assert.equal(bad.status, 401);
  const cookie = await loginCookie(env);
  assert.match(cookie, /^th_admin=/);
  const ok = await call("/w/admin/session", { headers: { Cookie: cookie } }, env);
  assert.equal(ok.status, 200);
  // 표를 쓰는 첫 요청에서 만든다
  const list = await call("/w/admin/layouts", { headers: { Cookie: cookie } }, env);
  assert.equal(list.status, 200);
  // 처음 한 번만 표를 만든다 (layouts, route_shapes)
  assert.equal(db.created, 2);
});

test("배치를 저장하면 /w/layouts.tsv 에 나오고, ETag 가 같으면 304", async () => {
  const db = new FakeDb();
  const env = { DB: db, ADMIN_TOKEN: "secret" };
  const cookie = await loginCookie(env);
  const empty = await call("/w/layouts.tsv", {}, env);
  assert.equal(empty.status, 200);
  assert.equal(await empty.text(), "");
  const emptyTag = empty.headers.get("ETag");

  const put = await call(
    "/w/admin/layouts/100100091",
    { method: "PUT", headers: { Cookie: cookie }, body: JSON.stringify({ tsv: "100100091\t210000246:0:0,210000214:3:0" }) },
    env
  );
  assert.equal(put.status, 200);
  const saved = await put.json();
  assert.equal(saved.ok, true);
  assert.equal(saved.id, "100100091");

  const tsv = await call("/w/layouts.tsv", {}, env);
  assert.equal(tsv.status, 200);
  assert.equal(await tsv.text(), "100100091\t210000246:0:0,210000214:3:0\n");
  const tag = tsv.headers.get("ETag");
  assert.ok(tag && tag !== emptyTag);
  assert.equal(tsv.headers.get("X-Layout-Version"), tag.replaceAll('"', ""));
  assert.equal(tsv.headers.get("Access-Control-Allow-Origin"), "*");

  const again = await call("/w/layouts.tsv", { headers: { "If-None-Match": tag } }, env);
  assert.equal(again.status, 304);
  assert.equal(await again.text(), "");

  const one = await call("/w/admin/layouts/100100091", { headers: { Cookie: cookie } }, env);
  assert.equal((await one.json()).tsv, "100100091\t210000246:0:0,210000214:3:0");
  const list = await call("/w/admin/layouts", { headers: { Cookie: cookie } }, env);
  assert.equal((await list.json()).items.length, 1);

  const del = await call("/w/admin/layouts/100100091", { method: "DELETE", headers: { Cookie: cookie } }, env);
  assert.equal(del.status, 200);
  assert.equal(await (await call("/w/layouts.tsv", {}, env)).text(), "");
});

test("배치 형식이 틀리면 저장하지 않는다", async () => {
  const db = new FakeDb();
  const env = { DB: db, ADMIN_TOKEN: "secret" };
  const cookie = await loginCookie(env);
  for (const body of ["", "no-tab", "id\tbad", "id\t1:2"]) {
    const res = await call("/w/admin/layouts/100", { method: "PUT", headers: { Cookie: cookie }, body }, env);
    assert.equal(res.status, 400, body);
  }
});

test("노선 선형을 저장하면 /w/shapes.tsv 에 나오고 ETag 가 바뀐다", async () => {
  const db = new FakeDb();
  const env = { DB: db, ADMIN_TOKEN: "secret" };
  const cookie = await loginCookie(env);
  const before = await call("/w/shapes.tsv", {}, env);
  const beforeTag = before.headers.get("ETag");
  assert.equal(await before.text(), "");

  const put = await call(
    "/w/admin/shapes/100100091",
    { method: "PUT", headers: { Cookie: cookie }, body: JSON.stringify({ tsv: "SEB100100091\t37.50000,127.00000\t10,20,-5,5" }) },
    env
  );
  assert.equal(put.status, 200);
  assert.equal((await put.json()).ok, true);

  const after = await call("/w/shapes.tsv", {}, env);
  assert.equal(await after.text(), "100100091\t37.50000,127.00000\t10,20,-5,5\n");
  const tag = after.headers.get("ETag");
  assert.notEqual(tag, beforeTag);
  assert.equal(after.headers.get("X-Shape-Version"), tag.replaceAll('"', ""));

  const again = await call("/w/shapes.tsv", { headers: { "If-None-Match": tag } }, env);
  assert.equal(again.status, 304);

  const one = await call("/w/admin/shapes/100100091", { headers: { Cookie: cookie } }, env);
  assert.equal((await one.json()).tsv, "100100091\t37.50000,127.00000\t10,20,-5,5");
  const del = await call("/w/admin/shapes/100100091", { method: "DELETE", headers: { Cookie: cookie } }, env);
  assert.equal(del.status, 200);
  assert.equal(await (await call("/w/shapes.tsv", {}, env)).text(), "");
});

test("선형 형식이 틀리면 저장하지 않는다", async () => {
  const db = new FakeDb();
  const env = { DB: db, ADMIN_TOKEN: "secret" };
  const cookie = await loginCookie(env);
  const bad = [
    "",
    "100100091\t37.5,127.0", // 변화량 없음
    "100100091\t37.5,127.0\t1", // 홀수
    "100100091\t0,0\t10,20", // 한반도 밖
    "100100091\t37.5,127.0\t1.5,2", // 정수 아님
    "100100091\t37.5,127.0\t1,2,3,4,5", // 홀수
  ];
  for (const tsv of bad) {
    const res = await call("/w/admin/shapes/100100091", { method: "PUT", headers: { Cookie: cookie }, body: JSON.stringify({ tsv }) }, env);
    assert.equal(res.status, 400, tsv);
  }
});

test("DB 가 없으면 503, CORS preflight 는 204", async () => {
  const env = { ADMIN_TOKEN: "secret" };
  const res = await call("/w/layouts.tsv", {}, env);
  assert.equal(res.status, 503);
  const pre = await call("/w/shapes.tsv", { method: "OPTIONS" }, env);
  assert.equal(pre.status, 204);
  assert.equal(pre.headers.get("Access-Control-Allow-Methods").includes("PUT"), true);
});
