import test from "node:test";
import assert from "node:assert/strict";
import { twoWayMask, offsetRight, MAX_SIDE_M } from "../site/js/sideline.js";

// 동쪽으로 400m 갔다가 같은 길로 돌아오는 노선 (오가는 구간), 이어서 골목을 한 바퀴 도는 노선
const step = 0.0005; // 경도 약 44m
const east = Array.from({ length: 10 }, (_, i) => [37.5, 127 + i * step]);
const outBack = [...east, ...[...east].reverse().slice(1)];
const loop = [[37.5, 127], [37.5, 127.001], [37.501, 127.001], [37.501, 127], [37.5, 127.00001]];

test("같은 길을 오가는 구간만 표시", () => {
  const m = twoWayMask(outBack);
  // 반환점(9번) 앞뒤 3구간 안은 자기 선이라 보지 않음 → 그 밖의 오가는 구간
  assert.ok([...m.slice(1, 5), ...m.slice(14, 18)].every(Boolean), "오가는 구간");
  assert.ok(twoWayMask(loop).every((v) => !v), "한 바퀴 도는 골목은 비키지 않음");
});

test("상·하행 따로 그릴 때 다른 선과 견줌", () => {
  const back = [...east].reverse();
  assert.ok(twoWayMask(east, [back]).every(Boolean));
  assert.ok(twoWayMask(east).every((v) => !v));
});

test("꼭짓점마다 다른 거리로 비키기", () => {
  const out = offsetRight(east, east.map((_, i) => (i < 5 ? 0 : MAX_SIDE_M)));
  assert.equal(out[0][0], east[0][0]);                       // 0 이면 그대로
  const dLat = (east[7][0] - out[7][0]) * 110540;            // 동쪽으로 가면 오른쪽은 남쪽
  assert.ok(Math.abs(dLat - MAX_SIDE_M) < 0.01, String(dLat));
  assert.equal(offsetRight(east, east.map(() => 0)), east);
  assert.ok(MAX_SIDE_M <= 3);
});
