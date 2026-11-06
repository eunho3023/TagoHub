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

test("노선을 85% 넘게 덮어야 지도 맞추기", async () => {
  const base = [[35.80, 128.60], [35.81, 128.60]];   // 약 1.1km
  const half = Array.from({ length: 10 }, (_, i) => ({ lat: 35.80 + i * 0.0005, lng: 128.6001, n: 1 }));
  assert.match((await gpsShape(half, base, async () => ({ ok: false }))).why, /coverage/);
  const full = Array.from({ length: 30 }, (_, i) => ({ lat: 35.80 + i * 0.00034, lng: 128.6001, n: 1 }));
  let asked = 0;
  const osrm = async (u) => { asked++; assert.ok(u.includes("/match/v1/driving/") && u.includes("radiuses=30")); return { ok: true, json: async () => ({ code: "Ok", matchings: [{ geometry: encode([[35.80, 128.60], [35.81, 128.60]], 6) }] }) }; };
  const r = await gpsShape(full, base, osrm);
  assert.ok(r.shape && r.shape.length >= 2 && asked === 1);
});
