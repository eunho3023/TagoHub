import { test } from "node:test";
import assert from "node:assert/strict";
// site/js 가 브라우저 것(주소·문서·창·타이머)을 씀 (crowd.test.js 와 같은 흉내)
const noop = { addEventListener() {}, removeEventListener() {} };
globalThis.location = { search: "", href: "http://localhost/" };
globalThis.document = { hidden: false, ...noop };
globalThis.window = noop;
const realInterval = globalThis.setInterval;
globalThis.setInterval = (...a) => { const t = realInterval(...a); t.unref?.(); return t; };
const { passStops, toSeg, dist } = await import("../site/js/edits.js");

// 전주 104번 송천동종점 (2026-11-26): 동부대로 선(위도 35.86998~35.87007)에서 35m 아래에 찍힌 정류장
const road = [[35.86998, 127.11138], [35.87007, 127.10926], [35.87005, 127.10873]];
const stop = { lat: 35.86974, lng: 127.10925 };

test("길에서 떨어진 정류장은 선 위 가장 가까운 자리를 넣어 선이 꺾이지 않음", () => {
  const out = passStops(road, [stop]);
  // 넣은(또는 옮긴) 점이 모두 원래 길 선 위 (3m 안)
  for (const p of out) {
    const d = Math.min(...road.slice(0, -1).map((a, i) => toSeg(p, a, road[i + 1]).d));
    assert.ok(d < 3, `선 밖 점 ${p} (${d.toFixed(1)}m)`);
  }
  // 정류장 좌표 그대로는 들어가지 않음
  assert.ok(!out.some((p) => dist(p, [stop.lat, stop.lng]) < 3));
});

test("선 위(3m 안)에 있는 정류장은 정류장 좌표로 붙임", () => {
  const line = [[35.0, 127.0], [35.0, 127.01]];
  const out = passStops(line, [{ lat: 35.00001, lng: 127.005 }]);
  assert.equal(out.length, 3);
  assert.deepEqual(out[1], [35.00001, 127.005]);
});

test("80m 넘게 떨어진 정류장은 넣지 않음", () => {
  const line = [[35.0, 127.0], [35.0, 127.01]];
  assert.equal(passStops(line, [{ lat: 35.001, lng: 127.005 }]).length, 2);
});
