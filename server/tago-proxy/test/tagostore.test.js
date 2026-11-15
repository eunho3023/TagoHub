import { test } from "node:test";
import assert from "node:assert/strict";
import { storeKey, getStored, putStored, DAY_MS } from "../src/tagostore.js";

/** D1 흉내 (tago_store 표 하나) */
function fakeDb() {
  const rows = new Map();
  return {
    rows,
    prepare: (sql) => ({
      run: async () => ({}),
      bind: (...a) => ({
        run: async () => { if (sql.startsWith("INSERT OR REPLACE INTO tago_store")) rows.set(a[0], { body: a[1], at: a[2] }); return {}; },
        first: async () => (sql.startsWith("SELECT body, at FROM tago_store") ? rows.get(a[0]) || null : null),
      }),
    }),
  };
}

test("TAGO 노선 정류장 목록·노선 정보만 저장 열쇠를 만듦", () => {
  assert.equal(storeKey("/1613000/BusRouteInfoInqireService/getRouteAcctoThrghSttnList", "cityCode=35010&routeId=JUB305001818&numOfRows=1000"), "stops:35010:JUB305001818");
  assert.equal(storeKey("/1613000/BusRouteInfoInqireService/getRouteInfoIem", "routeId=JUB1&cityCode=35010"), "info:35010:JUB1");
  assert.equal(storeKey("/1613000/BusLcInfoInqireService/getRouteAcctoBusLcList", "cityCode=35010&routeId=JUB1"), null);   // 버스 위치는 저장 안 함
  assert.equal(storeKey("/1613000/BusRouteInfoInqireService/getRouteAcctoThrghSttnList", "cityCode=x&routeId=JUB1"), null);
});

test("저장한 응답은 정한 기간 안에서만 줌 (서버 안 사흘·웹 하루·실패하면 30일)", async () => {
  const db = fakeDb();
  const now = Date.now();
  await putStored(db, "stops:35010:R", "<xml/>", now - 2 * DAY_MS);
  assert.equal(await getStored(db, "stops:35010:R", DAY_MS, now), null);
  assert.equal(await getStored(db, "stops:35010:R", 3 * DAY_MS, now), "<xml/>");
  assert.equal(await getStored(db, "stops:35010:R", 30 * DAY_MS, now), "<xml/>");
  assert.equal(await getStored(null, "stops:35010:R", DAY_MS, now), null);
});
