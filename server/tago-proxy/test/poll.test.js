import { test } from "node:test";
import assert from "node:assert/strict";
import { pollCron, badWhy, pruneMeta } from "../src/poll.js";
import { hitsFromBusLc } from "../src/log.js";

const xml = (ord, node) => `<response><header><resultCode>00</resultCode></header><body><items><item><vehicleno>70자1234</vehicleno><nodeord>${ord}</nodeord><nodeid>${node}</nodeid><gpslati>35.87</gpslati><gpslong>128.6</gpslong></item></items></body></response>`;
function memCache() {
  const store = new Map();
  return { match: async (k) => (store.has(k.url) ? new Response(store.get(k.url)) : undefined), put: async (k, r) => { store.set(k.url, await r.text()); } };
}

test("사이 정류장도 두 응답 사이 시각을 나눠 매김 (순번이 맞을 때만)", async () => {
  const written = [];
  const db = { prepare: (sql) => ({ bind: (...a) => ({ sql, a }), run: async () => {} }), batch: async (s) => { written.push(...s.map((x) => x.a)); } };
  const cache = memCache();
  const ords = new Map([[10, "N10"], [11, "N11"], [12, "N12"], [13, "N13"]]);
  const t0 = 1_800_000_000_000 - (1_800_000_000_000 % 60000);
  await hitsFromBusLc(db, cache, "R1", xml(10, "N10"), t0, ords);
  assert.equal(await hitsFromBusLc(db, cache, "R1", xml(13, "N13"), t0 + 180000, ords), 3);
  assert.deepEqual(written.map((w) => [w[1], w[2] - t0 / 60000]), [["N11", 1], ["N12", 2], ["N13", 3]]);
});

test("예약 작업: 관심 노선을 2분 간격으로, 하루 한도 안에서만, 밤에는 쉼", async () => {
  globalThis.caches = { default: memCache() };
  const interest = [{ route_id: "DGB3000402001", city: "22", polled: 0 }];
  const meta = new Map();
  const db = {
    prepare: (sql) => ({
      run: async () => {},
      bind: (...a) => ({
        first: async () => (sql.startsWith("SELECT v FROM poll_meta") ? (meta.has(a[0]) ? { v: meta.get(a[0]) } : null) : null),
        all: async () => ({ results: interest }),
        run: async () => {
          if (sql.startsWith("UPDATE route_interest SET polled")) interest[0].polled = a[0];
          if (sql.startsWith("INSERT INTO poll_meta")) meta.set(a[0], (meta.get(a[0]) || 0) + a[1]);
        },
      }),
    }),
    batch: async () => {},
  };
  const env = { DB: db, DATA_GO_KR_KEY: "k", POLL_PER_DAY: "2", ASSETS: { fetch: async () => ({ ok: false }) } };
  const asked = [];
  const fetcher = async (u) => { asked.push(u); return { status: 200, text: async () => xml(10, "N10") }; };
  const noon = Date.UTC(2026, 10, 9, 3, 0);   // 한국 12시
  assert.deepEqual(await pollCron(env, fetcher, Date.UTC(2026, 10, 9, 17, 0)), { skip: "night" });   // 한국 새벽 2시
  assert.equal((await pollCron(env, fetcher, noon)).polled, 1);
  assert.ok(asked[0].includes("getRouteAcctoBusLcList") && asked[0].includes("routeId=DGB3000402001"));
  assert.equal((await pollCron(env, fetcher, noon + 60000)).polled, 0);   // 2분 안 됨
  assert.equal((await pollCron(env, fetcher, noon + 120000)).polled, 1);
  assert.equal((await pollCron(env, fetcher, noon + 240000)).skip, "daily limit");   // 하루 2번 한도
});

test("실패 까닭 이름: 한도·키·잠시 바쁨(99)·HTTP 상태", () => {
  assert.equal(badWhy(200, "<returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg>"), "quota");
  assert.equal(badWhy(200, "SERVICE_KEY_IS_NOT_REGISTERED_ERROR"), "key");
  assert.equal(badWhy(200, "<resultCode>99</resultCode>"), "busy99");
  assert.equal(badWhy(200, '{"response":{"header":{"resultCode":99}}}'), "busy99");
  assert.equal(badWhy(522, "error code: 522"), "http522");
  assert.equal(badWhy(200, "<resultCode>995</resultCode>"), "other");
});

test("날짜별 숫자는 7일 지난 것만 지움 (cron:last 는 그대로)", async () => {
  const { DatabaseSync } = await import("node:sqlite").catch(() => ({}));
  if (!DatabaseSync) return;   // node:sqlite 가 없는 Node 에서는 건너뜀
  const mem = new DatabaseSync(":memory:");
  mem.exec("CREATE TABLE poll_meta (k TEXT PRIMARY KEY, v INTEGER NOT NULL)");
  for (const k of ["cron:last", "day:2026-11-10", "why:2026-11-10:http522", "ok:2026-11-16", "bad:2026-11-17", "day:2026-11-23"]) mem.prepare("INSERT INTO poll_meta VALUES (?, 1)").run(k);
  const db = { prepare: (sql) => ({ bind: (...a) => ({ run: async () => mem.prepare(sql).run(...a) }) }) };
  await pruneMeta(db, Date.UTC(2026, 10, 23, 3));   // 한국 11/23 12시 → 11/16 이전 지움
  assert.deepEqual(mem.prepare("SELECT k FROM poll_meta ORDER BY k").all().map((r) => r.k), ["bad:2026-11-17", "cron:last", "day:2026-11-23", "ok:2026-11-16"]);
});
