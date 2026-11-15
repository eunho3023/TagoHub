import { test } from "node:test";
import assert from "node:assert/strict";
import { busPoints, gpsShape, recordGps } from "../src/gps.js";
import { encode } from "../src/shape.js";

const xml = "<response><body><items>" +
  "<item><gpslati>35.8</gpslati><gpslong>128.6</gpslong><nodeord>3</nodeord><vehicleno>대구70자1234</vehicleno></item>" +
  "<item><gpslati>0</gpslati><gpslong>0</gpslong><nodeord>4</nodeord></item>" +
  "</items></body></response>";

test("버스 위치 응답에서 좌표·순번만 (차량 번호는 버림, 한국 밖 좌표 버림)", () => {
  assert.deepEqual(busPoints(xml), [{ lat: 35.8, lng: 128.6, ord: 3 }]);
});

test("노선별 약 5m 칸에 더함 (한 번 묶어 쓰기)", async () => {
  const batches = [];
  const db = { prepare: (sql) => ({ bind: (...a) => ({ sql, a }), run: async () => {} }), batch: async (s) => { batches.push(s); } };
  assert.equal(await recordGps(db, "DGB3000814000", "22", xml), 1);
  const ins = batches[0].find((x) => x.sql.startsWith("INSERT INTO gps_cells"));
  assert.deepEqual(ins.a.slice(0, 2), ["DGB3000814000", "716000_2572000"]);
  assert.ok(!JSON.stringify(batches).includes("1234"), "차량 번호는 저장하지 않음");
  assert.equal(await recordGps(db, "bad id!", "22", xml), 0);
});

test("노선을 85% 넘게 덮어야 만들고, 버스가 지나간 자리(오른쪽 차로)를 그대로 이음", () => {
  const base = [[35.80, 128.60], [35.81, 128.60]];   // 북쪽으로 약 1.1km
  const half = Array.from({ length: 10 }, (_, i) => ({ lat: 35.80 + i * 0.0005, lng: 128.6001, n: 1 }));
  assert.match(gpsShape(half, base).why, /coverage/);
  // 선에서 동쪽 9m(오른쪽 차로)로 지나간 자리
  const full = Array.from({ length: 30 }, (_, i) => ({ lat: 35.80 + i * 0.00034, lng: 128.6001, n: 3 }));
  const r = gpsShape(full, base);
  assert.ok(r.shape && r.shape.length > 20);
  const mid = r.shape.slice(3, -3);
  assert.ok(mid.every((p) => Math.abs(p[1] - 128.6001) < 0.00002), "자리 그대로 (선으로 끌어오지 않음)");
  for (let i = 1; i < r.shape.length; i++) assert.ok(r.shape[i][0] >= r.shape[i - 1][0] - 1e-9, "노선 차례대로");
});

test("같은 길을 오가는 노선: 정류장 순번으로 가는 길·오는 길 자리를 가름, 빈 곳은 지금 선으로", () => {
  const base = [[35.80, 128.60], [35.81, 128.60], [35.80, 128.60]];   // 북쪽으로 갔다 같은 길로 돌아옴
  const stops = [[35.80, 128.60], [35.81, 128.60], [35.80, 128.60]];
  const up = Array.from({ length: 30 }, (_, i) => ({ lat: 35.80 + i * 0.00034, lng: 128.6001, ord: 1, n: 2 }));   // 가는 길: 동쪽 차로
  const down = Array.from({ length: 30 }, (_, i) => ({ lat: 35.81 - i * 0.00034, lng: 128.5999, ord: 2, n: 2 })); // 오는 길: 서쪽 차로
  const r = gpsShape([...up, ...down], base, stops);
  assert.ok(r.shape);
  const n = r.shape.length, first = r.shape.slice(3, n / 2 - 3), second = r.shape.slice(n / 2 + 3, -3);
  assert.ok(first.every((p) => p[1] > 128.60005), "가는 길은 동쪽");
  assert.ok(second.every((p) => p[1] < 128.59995), "오는 길은 서쪽");
  // 오는 길 앞 절반만 지나간 자리가 있으면, 나머지는 지금 선으로 채움 (85% 는 넘게)
  const some = down.slice(0, 26);
  const r2 = gpsShape([...up, ...some], base, stops);
  assert.ok(r2.shape);
  assert.ok(r2.shape.slice(-4, -1).every((p) => Math.abs(p[1] - 128.60) < 0.00002), "빈 곳은 지금 선");
});
