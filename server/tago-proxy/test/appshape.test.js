import { test } from "node:test";
import assert from "node:assert/strict";
import { checkShape } from "../src/appshape.js";
import { encode } from "../src/shape.js";

test("앱 모양 검사: 정류장을 지나는 선만 받음", () => {
  const line = encode([[35.80, 127.10], [35.80, 127.12], [35.82, 127.12]], 5);
  const stops = [[35.8001, 127.105], [35.8001, 127.115], [35.81, 127.1201]];
  assert.equal(checkShape(line, stops), null);
  assert.equal(checkShape(line, [[35.9, 127.3], [35.95, 127.35]]), "not on stops");   // 엉뚱한 선
  assert.equal(checkShape(encode([[10, 127], [11, 127]], 5), null), "out of range");
  assert.equal(checkShape("", null), "bad poly");
  assert.equal(checkShape(line, null), null);                                          // 정류장을 모르면 범위만
});

test("앱이 모양을 물으면 버스가 실제로 지나간 자리로 만든 모양(sig 끝 :g)을 먼저 줌", async () => {
  const { handleAppShape } = await import("../src/appshape.js");
  const rows = { shapes: { sig: "v7:3:1,2:3,4:g", poly: "GPS" }, app: { poly: "APP" } };
  const db = {
    prepare: (sql) => ({
      bind: () => ({ first: async () => (sql.includes("FROM shapes") ? rows.shapes : sql.includes("app_shapes") ? rows.app : null), run: async () => ({}) }),
      run: async () => ({}),
    }),
  };
  const get = async () => (await handleAppShape(new Request("https://x/appshape/JUB305001088"), new URL("https://x/appshape/JUB305001088"), { DB: db })).json();
  assert.deepEqual(await get(), { poly: "GPS", src: "gps" });
  rows.shapes = { sig: "v7:3:1,2:3,4:k", poly: "KAKAO" };   // 버스 자리 모양이 아니면 앱이 올린 모양
  assert.deepEqual(await get(), { poly: "APP" });
});

test("서울 노선은 서울시 공식 경로를 앱이 올린 모양보다 먼저 줌 (서울 API 401 때 올라간 엉뚱한 모양 덮기)", async () => {
  const { handleAppShape } = await import("../src/appshape.js");
  const m = new Map();
  globalThis.caches = { default: { match: async (r) => (m.has(r.url) ? new Response(m.get(r.url)) : undefined), put: async (r, res) => { m.set(r.url, await res.text()); } } };
  const db = {
    prepare: (sql) => ({
      bind: () => ({ first: async () => (sql.includes("app_shapes") ? { poly: "APP" } : null), run: async () => ({}) }),
      run: async () => ({}),
    }),
  };
  let asked = 0;
  const path = { msgBody: { itemList: [{ no: "1", gpsY: "37.55", gpsX: "127.02" }, { no: "2", gpsY: "37.56", gpsX: "127.03" }] } };
  const fetcher = async (u) => { asked++; assert.ok(u.includes("getRoutePath?busRouteId=103900011")); return new Response(JSON.stringify(path)); };
  const env = { DB: db, DATA_GO_KR_KEY: "k" };
  const get = async (id) => (await handleAppShape(new Request("https://x/appshape/" + id), new URL("https://x/appshape/" + id), env, fetcher)).json();
  assert.deepEqual(await get("SEB103900011"), { poly: encode([[37.55, 127.02], [37.56, 127.03]], 5), src: "official" });
  await get("SEB103900011");
  assert.equal(asked, 1, "하루 저장");
  // 서울이 아니면 그대로 앱 모양
  assert.deepEqual(await get("JUB305001088"), { poly: "APP" });
  delete globalThis.caches;
});
