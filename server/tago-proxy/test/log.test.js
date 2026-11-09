import { test } from "node:test";
import assert from "node:assert/strict";
import { handleLog, parseUpload } from "../src/log.js";

const now = 1_800_000_000;

test("올린 기록 검사: 이상한 ID·시각은 버리고 같은 분은 한 번", () => {
  const up = parseUpload({
    routeId: "JJB306000104",
    hits: [
      ["JJB306000490", now - 30],
      ["JJB306000490", now - 20],        // 같은 분
      ["JJB306000491", now - 90000],     // 하루보다 오래됨
      ["JJB306000492", now + 3600],      // 미래
      ["bad id!", now],
      ["JJB306000493", "x"],
      "junk",
    ],
  }, now);
  assert.deepEqual(up, { routeId: "JJB306000104", hits: [{ nodeId: "JJB306000490", minute: Math.floor((now - 30) / 60) }] });
  assert.equal(parseUpload({ routeId: "a b", hits: [] }, now), null);
  assert.equal(parseUpload({ routeId: "R1" }, now), null);
  assert.equal(parseUpload(null, now), null);
});

/** D1 흉내: 저장하고 노선·정류장으로 꺼내기만 */
function fakeDb() {
  const rows = new Set();
  const stmt = (sql) => ({
    sql, args: [],
    bind(...a) { return { ...this, args: a }; },
    async run() { if (sql.startsWith("INSERT")) rows.add(this.args.join("|")); return {}; },
    async all() {
      const [r, n, oldest] = this.args;
      const results = [...rows].map((s) => s.split("|")).filter(([a, b, m]) => a === r && b === n && +m >= oldest)
        .map(([, , m]) => ({ minute: +m })).sort((x, y) => x.minute - y.minute);
      return { results };
    },
  });
  return { rows, prepare: stmt, async batch(list) { for (const s of list) await s.run(); } };
}

test("올리고 꺼내기", async () => {
  const env = { DB: fakeDb() };
  const ctx = { waitUntil() {} };
  const t = Math.floor(Date.now() / 1000) - 120;
  const post = new Request("https://x/log/hits", { method: "POST", body: JSON.stringify({ routeId: "R1", hits: [["N1", t], ["N2", t]] }) });
  const r1 = await handleLog(post, new URL(post.url), env, ctx);
  assert.deepEqual(await r1.json(), { saved: 2 });
  const get = new Request("https://x/log/stop?route=R1&node=N1");
  const r2 = await handleLog(get, new URL(get.url), env, ctx);
  assert.deepEqual(await r2.json(), { minutes: [Math.floor(t / 60)] });
  const bad = new Request("https://x/log/stop?route=R1&node=");
  assert.equal((await handleLog(bad, new URL(bad.url), env, ctx)).status, 400);
  const noDb = await handleLog(get, new URL(get.url), {}, ctx);
  assert.equal(noDb.status, 503);
});

import { hitsFromBusLc, handleWebLog } from "../src/log.js";
test("버스 위치 응답 두 번 → 정류장을 옮겨 간 버스는 지금 정류장 도착으로 기록 (차량 번호는 기록 안 함)", async () => {
  const store = new Map();
  const cache = { match: async (k) => (store.has(k.url) ? new Response(store.get(k.url)) : undefined), put: async (k, r) => { store.set(k.url, await r.text()); } };
  const written = [];
  const db = { prepare: (sql) => ({ bind: (...a) => ({ sql, a }), run: async () => {} }), batch: async (s) => { written.push(...s.filter((x) => x.sql.startsWith("INSERT OR IGNORE INTO hits"))); } };
  const xml = (ord, node) => `<items><item><vehicleno>대구70자1234</vehicleno><nodeord>${ord}</nodeord><nodeid>${node}</nodeid></item></items>`;
  const t0 = 1_800_000_000_000;
  assert.equal(await hitsFromBusLc(db, cache, "DGB3000402001", xml(10, "DGB7011006700"), t0), 0);   // 처음은 비교할 것 없음
  assert.equal(await hitsFromBusLc(db, cache, "DGB3000402001", xml(11, "DGB7011006800"), t0 + 60000), 1);
  assert.deepEqual(written[0].a, ["DGB3000402001", "DGB7011006800", Math.floor((t0 + 60000) / 60000)]);
  assert.ok(!JSON.stringify(written).includes("1234"));
  // 한 번에 7정류장 넘게 건너뛰면 버림
  assert.equal(await hitsFromBusLc(db, cache, "DGB3000402001", xml(20, "DGB7011009999"), t0 + 120000), 0);
});

test("웹 정류장 기록 읽기: 노선별로 묶음", async () => {
  const db = { prepare: (sql) => ({ run: async () => {}, bind: (...a) => ({ all: async () => ({ results: [{ route_id: "R1", minute: 10 }, { route_id: "R2", minute: 20 }, { route_id: "R1", minute: 30 }] }) }) }) };
  const r = await handleWebLog(new URL("https://x/w/log/stop?node=N1&node=N2"), { DB: db });
  assert.deepEqual((await r.json()).routes, { R1: [10, 30], R2: [20] });
  assert.equal((await handleWebLog(new URL("https://x/w/log/stop?node=bad%20id"), { DB: db })).status, 400);
});

import { prevStore } from "../src/log.js";
test("바로 앞 버스 위치는 D1 에 (예약 작업이 다른 지역 서버에서 돌아도 이어짐), 차량 번호는 저장 안 함", async () => {
  const rows = new Map(), written = [];
  const db = {
    prepare: (sql) => ({
      run: async () => {},
      bind: (...a) => ({
        sql, a,
        first: async () => (rows.has(a[0]) ? { v: rows.get(a[0]) } : null),
        run: async () => { if (sql.startsWith("INSERT INTO bus_prev")) rows.set(a[0], a[1]); },
      }),
    }),
    batch: async (s) => { written.push(...s.filter((x) => x.sql.startsWith("INSERT OR IGNORE INTO hits"))); },
  };
  const xml = (ord, node) => `<items><item><vehicleno>전북70자1234</vehicleno><nodeord>${ord}</nodeord><nodeid>${node}</nodeid></item></items>`;
  const t0 = 1_800_000_000_000;
  assert.equal(await hitsFromBusLc(db, prevStore(db), "JUB305001", xml(5, "JUB1"), t0), 0);
  assert.equal(await hitsFromBusLc(db, prevStore(db), "JUB305001", xml(6, "JUB2"), t0 + 120000), 1);
  assert.equal(written[0].a[1], "JUB2");
  assert.ok(!rows.get("JUB305001").includes("1234"));
});
