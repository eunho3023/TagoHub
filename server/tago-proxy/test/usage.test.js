import { test } from "node:test";
import assert from "node:assert/strict";
import { useKey, noteUse, flushUse, useReport, pruneUse, _resetUse } from "../src/usage.js";
import { klidWebRequest } from "../src/proxy.js";

/** api_use 표만 흉내 내는 D1 */
function fakeDb() {
  const rows = new Map();
  const stmt = (sql, args = []) => ({
    bind: (...a) => stmt(sql, a),
    run: async () => {
      if (/^CREATE/.test(sql)) return {};
      if (/^INSERT/.test(sql)) { rows.set(args[0], (rows.get(args[0]) || 0) + args[1]); return {}; }
      if (/^DELETE/.test(sql)) { for (const k of [...rows.keys()]) if (k < args[0]) rows.delete(k); return {}; }
      throw new Error(sql);
    },
    all: async () => ({ results: [...rows].filter(([k]) => k >= args[0] && k < args[1]).map(([k, n]) => ({ k, n })) }),
  });
  return { rows, prepare: (sql) => stmt(sql), batch: async (list) => { for (const s of list) await s.run(); } };
}

// 2026-11-25 07:05 한국 시각
const AT = Date.UTC(2026, 10, 24, 22, 5);

test("요청 수 이름: 한국 시각 날짜·시·서비스·쓴 곳", () => {
  assert.equal(useKey("1613000/BusLcInfoInqireService", "web", AT), "2026-11-25|07|1613000/BusLcInfoInqireService|web");
});

test("기관에 물은 수를 모아 두었다가 한꺼번에 쓰고, 날짜별로 보여 줌", async () => {
  _resetUse();
  const db = fakeDb();
  const path = "1613000/BusLcInfoInqireService/getRouteAcctoBusLcList";
  await noteUse(db, path, "web", AT);              // 처음은 바로 씀
  await noteUse(db, path, "web", AT + 1000);       // 20초 안: 모아 둠
  await noteUse(db, path, "cron", AT + 2000, true); // 예약 작업은 바로
  assert.equal(await noteUse(db, "https://example.com/x", "web", AT), false); // 공공데이터포털 아니면 안 셈
  await noteUse(db, "1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", "app", AT + 3600000);
  await flushUse(db);
  const r = await useReport(db, "2026-11-25");
  assert.deepEqual(r.total, { "1613000/BusLcInfoInqireService": 3, "1613000/ArvlInfoInqireService": 1 });
  assert.deepEqual(r.bySrc["1613000/BusLcInfoInqireService"], { web: 2, cron: 1 });
  assert.equal(r.hours[r.hours.length - 1].h, 8);
  assert.deepEqual((await useReport(db, "2026-11-24")).total, {});
});

test("7일 넘은 요청 수는 지움", async () => {
  _resetUse();
  const db = fakeDb();
  db.rows.set("2026-11-10|07|1613000/BusLcInfoInqireService|web", 5);
  db.rows.set("2026-11-24|07|1613000/BusLcInfoInqireService|web", 5);
  await pruneUse(db, AT);
  assert.deepEqual([...db.rows.keys()], ["2026-11-24|07|1613000/BusLcInfoInqireService|web"]);
});

test("초정밀 버스 위치는 30쪽까지 (충주 19쪽)", () => {
  assert.equal(klidWebRequest("/w/klid/pos/4313000000/19").page, 19);
  assert.equal(klidWebRequest("/w/klid/pos/4313000000/30").page, 30);
  assert.equal(klidWebRequest("/w/klid/pos/4313000000/31"), null);
  assert.equal(klidWebRequest("/w/klid/pos/4313000000/0"), null);
});
