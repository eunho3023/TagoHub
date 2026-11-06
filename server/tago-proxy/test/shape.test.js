import { test } from "node:test";
import assert from "node:assert/strict";
import { bucket, build, buildKakao, chunks, decode, encode, fitsStops, handleShape, kakaoRidePath, removeDetours, signature, tagoStops } from "../src/shape.js";

test("노선 묶음 번호는 앱·자료 만들기와 같음", () => {
  assert.equal(bucket("BSB5200111000"), 992);
  assert.equal(bucket("JJB306000104"), 871);
});

test("폴리라인 인코딩·디코딩 (정밀도 5·6)", () => {
  const pts = [[38.5, -120.2], [40.7, -120.95], [43.252, -126.453]];
  assert.equal(encode(pts, 5), "_p~iF~ps|U_ulLnnqC_mqNvxq`@");
  assert.deepEqual(decode(encode(pts, 6), 6), pts);
});

test("구간 나누기는 끝 정류장을 겹침", () => {
  const s = Array.from({ length: 30 }, (_, i) => [36 + i * 0.001, 127]);
  const c = chunks(s, 25);
  assert.equal(c.length, 2);
  assert.deepEqual(c[0][24], c[1][0]);
});

test("정류장 사이 블록을 한 바퀴 도는 구간은 직선", () => {
  const a = [36.0, 127.0], b = [36.001, 127.0];   // 약 110m
  // a → 동쪽 500m 돌아서 → b (1km 넘게 돌아감)
  const loop = [a, [36.0, 127.0055], [36.001, 127.0055], b];
  assert.deepEqual(removeDetours(loop, [a, b]), [a, b]);
  const straightish = [a, [36.0005, 127.0001], b];
  assert.deepEqual(removeDetours(straightish, [a, b]), straightish);
});

test("구간 실패는 직선, 전부 실패면 null", async () => {
  const stops = [[36.0, 127.0], [36.01, 127.0], [36.02, 127.0]];
  const road = encode([[36.0, 127.0], [36.005, 127.0002], [36.01, 127.0], [36.02, 127.0]], 6).toString();
  const ok = async () => ({ ok: true, json: async () => ({ code: "Ok", routes: [{ geometry: road }] }) });
  const shape = await build(stops, ok);
  assert.ok(shape.length >= 4);
  assert.equal(await build(stops, async () => ({ ok: false })), null);
  assert.equal(signature(stops), "v6:3:36.0000,127.0000:36.0200,127.0000");
});

test("정류장 좌표가 큰길 옆 골목에 찍혀 길이 돌아 들어가면 그 정류장을 빼고 큰길로", async () => {
  const a = [36.0, 127.0], c = [36.001, 127.0];
  const side = [36.0005, 127.00055];   // 큰길(경도 127.0)에서 동쪽 약 50m
  const deep = [36.0005, 127.0015, "전북대학병원내"];    // 동쪽 약 135m, 이름이 '○○내' (실제로 안으로 들어가는 정류장)
  const wrong = [36.0005, 127.0015, "조촌초교.제일아파트"]; // 같은 자리지만 보통 이름 → 좌표가 골목 쪽으로 잘못 찍힌 것
  const detour = (p) => [a, [p[0], p[1] + 0.0015], p, c];   // 골목으로 돌아 들어가는 길
  const fetcherFor = (b) => async (u) => {
    const via = u.includes(b[1].toFixed(6));
    const g = encode(via ? detour(b) : [a, [36.0005, 127.0], c], 6);
    return { ok: true, json: async () => ({ code: "Ok", routes: [{ geometry: g }] }) };
  };
  const main = await build([a, side, c], fetcherFor(side));
  assert.ok(main.every((p) => Math.abs(p[1] - 127.0) < 1e-6), "큰길로만");
  const inside = await build([a, deep, c], fetcherFor(deep));
  assert.ok(inside.some((p) => p[1] > 127.001), "'○○내' 정류장은 실제로 들어가는 길이라 그대로");
  const fixed = await build([a, wrong, c], fetcherFor(wrong));
  assert.ok(fixed.every((p) => Math.abs(p[1] - 127.0) < 1e-6), "250m 안 보통 정류장은 큰길로");
});

test("카카오 경로: 이 노선 번호 버스 구간만, 시작·끝이 정류장 가까이", async () => {
  const a = [36.0, 127.0], b = [36.01, 127.0];
  const step = (no, pts) => ({ properties: { type: "BUS", vehicles: [{ name: no }] }, path: { points: pts.map(([la, lo]) => [String(lo), String(la)]) } });
  const body = { routes: [{ steps: [{ properties: { type: "WALKING" } }, step("101", [a, [36.005, 127.001], b]), step("104번", [a, [36.005, 127.0005], b])] }] };
  assert.deepEqual(kakaoRidePath(body, "104", a, b), [a, [36.005, 127.0005], b]);
  assert.equal(kakaoRidePath(body, "999", a, b), null);
  assert.equal(kakaoRidePath(body, "104", a, [36.05, 127.0]), null);
  // 정류장 6개씩 카카오로 이어 붙임
  const stops = Array.from({ length: 11 }, (_, i) => [36.0 + i * 0.001, 127.0]);
  const fetcher = async (u) => {
    const q = new URL(u).searchParams;
    const s0 = [+q.get("start_y"), +q.get("start_x")], e0 = [+q.get("end_y"), +q.get("end_x")];
    return { ok: true, json: async () => ({ routes: [{ steps: [step("104", [s0, [(s0[0] + e0[0]) / 2, 127.0], e0])] }] }) };
  };
  const shape = await buildKakao(stops, "104", "k", fetcher);
  assert.ok(shape && fitsStops(shape, stops));
});

test("만든 모양은 저장하고 다음엔 저장한 것을 줌", async () => {
  globalThis.caches = { default: { match: async () => undefined, put: async () => {} } };
  const route = { stops: [[1, "A", "가", 36.0, 127.0, "0"], [2, "B", "나", 36.01, 127.0, "0"]] };
  const store = new Map();
  const db = {
    prepare: (sql) => ({
      bind: (...a) => ({
        first: async () => store.get(a[0]) || null,
        run: async () => { if (sql.startsWith("INSERT")) store.set(a[0], { sig: a[1], poly: a[2] }); },
      }),
      run: async () => {},
    }),
  };
  const env = { DB: db, ASSETS: { fetch: async () => ({ ok: true, json: async () => ({ R1: route }) }) } };
  const waits = [];
  const ctx = { waitUntil: (p) => waits.push(p) };
  let calls = 0;
  const osrm = async () => { calls++; return { ok: true, json: async () => ({ code: "Ok", routes: [{ geometry: encode([[36.0, 127.0], [36.01, 127.0]], 6) }] }) }; };
  const r1 = await handleShape(null, new URL("https://x/w/shape/R1"), env, ctx, osrm);
  await Promise.all(waits);
  assert.equal(r1.status, 200);
  assert.ok((await r1.json()).poly.length > 0);
  const r2 = await handleShape(null, new URL("https://x/w/shape/R1"), env, ctx, osrm);
  assert.equal(r2.status, 200);
  assert.equal(calls, 1);
  assert.equal((await handleShape(null, new URL("https://x/w/shape/bad%20id"), env, ctx, osrm)).status, 404);
});

test("노선 조각에 없는 노선은 도시코드로 TAGO 정류장을 받아 모양을 만듦", async () => {
  globalThis.caches = { default: { match: async () => undefined, put: async () => {} } };
  const xml = "<response><body><items><item><nodeord>2</nodeord><gpslati>36.01</gpslati><gpslong>127.0</gpslong></item>" +
    "<item><nodeord>1</nodeord><gpslati>36.0</gpslati><gpslong>127.0</gpslong></item></items></body></response>";
  assert.deepEqual(tagoStops(xml), [[36.0, 127.0], [36.01, 127.0]]);
  const env = { DATA_GO_KR_KEY: "k", ASSETS: { fetch: async () => ({ ok: true, json: async () => ({}) }) } };
  const ctx = { waitUntil: () => {} };
  const asked = [];
  const fetcher = async (u) => {
    asked.push(u);
    if (u.includes("apis.data.go.kr")) return { ok: true, text: async () => xml };
    return { ok: true, json: async () => ({ code: "Ok", routes: [{ geometry: encode([[36.0, 127.0], [36.005, 127.001], [36.01, 127.0]], 6) }] }) };
  };
  const r = await handleShape(null, new URL("https://x/w/shape/JUB305001089?city=35010"), env, ctx, fetcher);
  assert.equal(r.status, 200);
  assert.ok(asked[0].includes("cityCode=35010&routeId=JUB305001089"));
  // 도시코드가 없으면 만들지 않음
  assert.equal((await handleShape(null, new URL("https://x/w/shape/JUB305001090"), env, ctx, fetcher)).status, 404);
});

test("경기 노선은 GBIS 정류장 목록으로 모양을 만듦", async () => {
  globalThis.caches = { default: { match: async () => undefined, put: async () => {} } };
  const env = { DATA_GO_KR_KEY: "k", ASSETS: { fetch: async () => ({ ok: true, json: async () => ({}) }) } };
  const fetcher = async (u) => {
    if (u.includes("6410000")) return { ok: true, json: async () => ({ response: { msgBody: { busRouteStationList: [
      { stationSeq: 2, x: 127.0, y: 37.01 }, { stationSeq: 1, x: 127.0, y: 37.0 }] } } }) };
    return { ok: true, json: async () => ({ code: "Ok", routes: [{ geometry: encode([[37.0, 127.0], [37.005, 127.001], [37.01, 127.0]], 6) }] }) };
  };
  const r = await handleShape(null, new URL("https://x/w/shape/GGB234000315"), env, { waitUntil: () => {} }, fetcher);
  assert.equal(r.status, 200);
});
