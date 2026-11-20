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
  assert.equal(signature(stops), "v7:3:36.0000,127.0000:36.0200,127.0000");
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

test("노선 조각에 없는 노선은 도시코드로 TAGO 정류장을 받아 모양을 만듦 (전주는 전주시 그림·정류장이 먼저라 익산으로)", async () => {
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
  const r = await handleShape(null, new URL("https://x/w/shape/IKB305001089?city=35020"), env, ctx, fetcher);
  assert.equal(r.status, 200);
  assert.ok(asked[0].includes("cityCode=35020&routeId=IKB305001089"));
  // 도시코드가 없으면 만들지 않음
  assert.equal((await handleShape(null, new URL("https://x/w/shape/IKB305001090"), env, ctx, fetcher)).status, 404);
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

test("예약 작업: 노선 묶음을 줄에 넣고, 1분마다 하나씩 만들어 저장", async () => {
  const { shapeCron } = await import("../src/shape.js");
  const shapes = new Map(), queue = new Map(), meta = new Map();
  const db = {
    prepare: (sql) => {
      const exec = (a) => ({
        first: async () => {
          if (sql.startsWith("SELECT sig, poly FROM shapes")) return shapes.get(a[0]) || null;
          if (sql.startsWith("SELECT v FROM shape_meta")) { const k = /k = '(\w+)'/.exec(sql)[1]; return meta.has(k) ? { v: meta.get(k) } : null; }
          if (sql.startsWith("SELECT route_id, city, no, tries FROM shape_queue")) {
            const all = [...queue.values()].sort((x, y) => y.prio - x.prio || x.tries - y.tries);
            return all[0] || null;
          }
          return null;
        },
        all: async () => ({ results: a.filter((id) => shapes.has(id)).map((id) => ({ route_id: id, sig: shapes.get(id).sig })) }),
        run: async () => {
          if (sql.startsWith("INSERT INTO shape_queue")) { const [id, city, no, prio] = a; if (!queue.has(id)) queue.set(id, { route_id: id, city, no, prio, tries: 0 }); }
          else if (sql.startsWith("INSERT OR REPLACE INTO shape_meta")) meta.set(/VALUES \('(\w+)'/.exec(sql)[1], a[0]);
          else if (sql.startsWith("INSERT OR REPLACE INTO shapes")) shapes.set(a[0], { sig: a[1], poly: a[2] });
          else if (sql.startsWith("DELETE FROM shape_queue")) queue.delete(a[0]);
          else if (sql.startsWith("UPDATE shape_queue")) queue.get(a[1]).tries++;
        },
      });
      return { bind: (...a) => exec(a), run: async () => {}, first: async () => exec([]).first() };
    },
  };
  const route = { no: "104", city: "35010", stops: [[1, "A", "가", 36.0, 127.0, "0"], [2, "B", "나", 36.01, 127.0, "0"]] };
  const env = { DB: db, ASSETS: { fetch: async (req) => ({ ok: true, json: async () => (req.url.endsWith(`/routes/${bucket("R1")}.json`) || req.url.endsWith("/routes/0.json") ? { R1: route } : {}) }) } };
  const osrm = async () => ({ ok: true, json: async () => ({ code: "Ok", routes: [{ geometry: encode([[36.0, 127.0], [36.01, 127.0]], 6) }] }) });
  const r1 = await shapeCron(env, osrm);           // 줄이 비어 있어 묶음 0 을 줄에 넣음
  assert.equal(r1.bucket, 0);
  assert.ok(queue.has("R1"));
  const r2 = await shapeCron(env, osrm);           // R1 을 만들어 저장
  assert.equal(r2.saved, true);
  assert.ok(shapes.get("R1").sig.startsWith("v7:"));
  assert.ok(!queue.has("R1"));
  assert.equal(meta.get("ver"), "11");
  // 모양 만드는 방식이 바뀌면(판) 노선 묶음을 처음부터 다시 훑고, 지금 방식이 아닌(v8) 모양은 다시 만듦
  meta.set("bucket", "1024"); meta.set("ver", "8");
  shapes.set("R1", { sig: "v8:2:36.0000,127.0000:36.0100,127.0000:k", poly: "x" });
  const r3 = await shapeCron(env, osrm);
  assert.equal(r3.bucket, 0);
  assert.equal(meta.get("ver"), "11");
  assert.ok(queue.has("R1"));
});

test("지역 공식 노선 모양: 서울·경기·전주", async () => {
  const { officialShape, jeonjuDrawing } = await import("../src/shape.js");
  assert.deepEqual(jeonjuDrawing('<RFC30><code>000</code><list class="object"><vtxx>127.1</vtxx><vtxy>35.8</vtxy></list><list><vtxx>127.2</vtxx><vtxy>35.9</vtxy></list></RFC30>'), [[35.8, 127.1], [35.9, 127.2]]);
  const env = { DATA_GO_KR_KEY: "k" };
  const asked = [];
  const fetcher = async (u) => {
    asked.push(u);
    if (u.includes("getBusRouteLineListv2")) return { ok: true, json: async () => ({ response: { msgBody: { busRouteLineList: [{ lineSeq: 2, x: 127.2, y: 37.2 }, { lineSeq: 1, x: 127.1, y: 37.1 }] } } }) };
    if (u.includes("getRoutePath")) return { ok: true, json: async () => ({ msgBody: { itemList: [{ no: "1", gpsX: "126.9", gpsY: "37.5" }, { no: "2", gpsX: "126.91", gpsY: "37.51" }] } }) };
    return { ok: false };
  };
  assert.deepEqual(await officialShape(env, "GGB234000015", fetcher), [[37.1, 127.1], [37.2, 127.2]]);
  assert.deepEqual(await officialShape(env, "SEB100100118", fetcher), [[37.5, 126.9], [37.51, 126.91]]);
  assert.equal(await officialShape(env, "BSB5200111000", fetcher), null);
  assert.ok(asked.some((u) => u.includes("routeId=234000015")) && asked.some((u) => u.includes("busRouteId=100100118")));
});

test("긴 노선: 카카오 경로를 여러 번에 나눠 이어 만듦", async () => {
  const { kakaoStep, longForKakao } = await import("../src/shape.js");
  const stops = Array.from({ length: 131 }, (_, i) => [36.0 + i * 0.001, 127.0]);   // 구간 26개 (> 22)
  assert.ok(longForKakao(stops));
  const work = new Map();
  const db = { prepare: (sql) => ({ bind: (...a) => ({
    first: async () => work.get(a[0]) || null,
    run: async () => { if (sql.startsWith("INSERT")) work.set(a[0], { sig: a[1], next: a[2], poly: a[3], got: a[4], straight: a[5] }); else work.delete(a[0]); },
  }) }) };
  const step = (no, pts) => ({ properties: { type: "BUS", vehicles: [{ name: no }] }, path: { points: pts.map(([la, lo]) => [String(lo), String(la)]) } });
  const fetcher = async (u) => {
    const q = new URL(u).searchParams;
    const s0 = [+q.get("start_y"), +q.get("start_x")], e0 = [+q.get("end_y"), +q.get("end_x")];
    return { ok: true, json: async () => ({ routes: [{ steps: [step("101", [s0, e0])] }] }) };
  };
  const r1 = await kakaoStep(db, "R9", "sig", stops, "101", "k", fetcher);
  assert.deepEqual([r1.done, r1.next, r1.of], [false, 20, 26]);
  assert.equal(work.get("R9").next, 20);
  const r2 = await kakaoStep(db, "R9", "sig", stops, "101", "k", fetcher);
  assert.equal(r2.done, true);
  assert.ok(r2.shape && Math.abs(r2.shape[r2.shape.length - 1][0] - 36.13) < 1e-5);
  assert.ok(!work.has("R9"));
});

test("회차 정류장에서 조각을 끊어 가는 길·돌아오는 길을 따로 물음 (임실 운암 입석 → 용운입구 → 입석)", async () => {
  const { turnsAt } = await import("../src/shape.js");
  const a = [35.6377, 127.1551], turn = [35.6373, 127.1360], b = [35.6378, 127.1550], c = [35.6442, 127.1549], d = [35.6537, 127.1548];
  const stops = [[35.6300, 127.1550], a, turn, b, c, d];
  assert.equal(turnsAt(stops, 2), true);
  assert.equal(turnsAt(stops, 3), false);
  const parts = chunks(stops, 6);
  assert.deepEqual(parts.map((p) => p.length), [3, 4]);
  assert.deepEqual(parts[0][2], turn);
  assert.deepEqual(parts[1][0], turn);
});

test("정류장 사이 길: 굽이굽이 긴 산길은 그대로, 같은 자리로 되돌아오는 고리만 잘라냄", async () => {
  const { cutLoops } = await import("../src/shape.js");
  const a = [36.0, 127.0], b = [36.0, 127.01];   // 직선 약 900m
  // 북쪽으로 크게 돌아가는 산길 (직선의 약 2배)
  const mountain = [a, [36.004, 127.0025], [36.006, 127.005], [36.004, 127.0075], b];
  assert.deepEqual(removeDetours(mountain, [a, b]), mountain);
  // 출발하자마자 블록을 한 바퀴 돌아 같은 자리로 온 뒤 곧게
  const loop = [a, [36.001, 127.0], [36.001, 127.001], [36.0, 127.001], [36.0, 127.00002], b];
  assert.deepEqual(cutLoops(loop), [a, [36.0, 127.00002], b]);
});

test("다시 만들 모양: 회차가 있고 500m 넘는 직선이 든 것만", async () => {
  const { turnStraight } = await import("../src/shape.js");
  const st = (p) => [0, "x", "n", p[0], p[1], "0"];
  const turnStops = [[35.6300, 127.1550], [35.6377, 127.1551], [35.6373, 127.1360], [35.6378, 127.1550]].map(st);
  const straight = encode([[35.6373, 127.1360], [35.6378, 127.1550]], 5);
  const road = encode(Array.from({ length: 40 }, (_, i) => [35.6373 + i * 0.00001, 127.1360 + i * 0.0005]), 5);
  assert.equal(turnStraight(turnStops, straight), true);
  assert.equal(turnStraight(turnStops, road), false);
  assert.equal(turnStraight([[35.60, 127.15], [35.61, 127.15], [35.62, 127.15]].map(st), straight), false);
});

test("OSRM 이 정류장에서 직진만 하다 빙 돌아가면 U턴을 허락해 다시 물음 (여수 365-5 초도 대동항)", async () => {
  // 예교 → 대동항(되돌아 나옴) → 복지회관: 직진만 허락하면 60km, U턴을 허락하면 정류장 길 그대로
  const stops = [[34.245655, 127.250049], [34.241584, 127.244288], [34.239566, 127.247163]];
  const far = [stops[0], [34.5, 127.6], [34.0, 127.0], stops[2]];
  const near = [stops[0], [34.2435, 127.2470], stops[1], [34.2405, 127.2460], stops[2]];
  const asked = [];
  const fetcher = async (u) => {
    const straight = new URL(u).searchParams.get("continue_straight");
    asked.push(straight);
    return { ok: true, json: async () => ({ code: "Ok", routes: [{ geometry: encode(straight === "true" ? far : near, 6) }] }) };
  };
  const shape = await build(stops, fetcher);
  assert.deepEqual(asked.slice(0, 2), ["true", "false"]);
  assert.ok(shape && shape.every((p) => p[0] < 34.25 && p[0] > 34.23), "60km 돌아가는 길이 아님");
  assert.ok(shape.length > stops.length, "직선이 아니라 길");
});

test("예전 방식으로 만든 긴 직선 모양은 다시 만들 것으로 봄 (버스 자리 모양·새로 만든 모양은 아님)", async () => {
  const { longStraight, staleStraight, STRAIGHT_FIX_AT } = await import("../src/shape.js");
  const straight = encode([[34.2382, 127.2499], [34.2244, 127.2522]], 5);   // 상촌 → 의성 1.5km 직선
  const road = encode(Array.from({ length: 20 }, (_, i) => [34.2382 - i * 0.0007, 127.2499 + i * 0.0001]), 5);
  assert.equal(longStraight(straight), true);
  assert.equal(longStraight(road), false);
  assert.equal(staleStraight({ sig: "v7:a:k", poly: straight, made: STRAIGHT_FIX_AT - 100 }), true);
  assert.equal(staleStraight({ sig: "v7:a:k", poly: straight, made: STRAIGHT_FIX_AT + 100 }), false);   // 고친 뒤 만들었는데도 직선: 길이 없음
  assert.equal(staleStraight({ sig: "v7:a:k:g", poly: straight, made: 0 }), false);
  assert.equal(staleStraight({ sig: "v7:a:k", poly: road, made: 0 }), false);
});
