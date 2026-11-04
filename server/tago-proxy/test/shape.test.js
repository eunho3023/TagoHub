import { test } from "node:test";
import assert from "node:assert/strict";
import { bucket, build, chunks, decode, encode, handleShape, removeDetours, signature } from "../src/shape.js";

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
  assert.equal(signature(stops), "3:36.0000,127.0000:36.0200,127.0000");
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
