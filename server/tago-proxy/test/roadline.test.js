import { test } from "node:test";
import assert from "node:assert/strict";
import { despike, roundCorners, tidyRoad } from "../site/js/roadline.js";

const a = [35.80, 127.10], b = [35.80, 127.1010], c = [35.80, 127.1020];

test("큰길 → 골목 정류장 → 큰길로 되돌아온 가시는 뺌 (앱 Shapes.despike 와 같음)", () => {
  const tip = [35.8004, 127.1010]; // 큰길(동쪽)에서 북쪽으로 44m
  assert.deepEqual(despike([a, b, tip, b, c]), [a, b, c]);
});

test("같은 점으로 되돌아오는 작은 고리는 곧게, 마을을 크게 도는 고리는 남김", () => {
  const l1 = [35.8003, 127.1010], l2 = [35.8003, 127.1013], l3 = [35.8000, 127.1013]; // 한 바퀴 약 120m
  assert.deepEqual(despike([a, b, l1, l2, l3, b, c]), [a, b, c]);
  const v1 = [35.8008, 127.1010], v2 = [35.8008, 127.1015], v3 = [35.8003, 127.1015]; // 약 190m, 큰길이 아닌 쪽에서 돌아옴
  const loop = [a, b, v1, v2, v3, b, c];
  assert.deepEqual(despike(loop), loop);
});

test("꺾이는 곳은 모서리 15m 안에서 둥글게, 곧은 길은 그대로", () => {
  const corner = [35.8010, 127.1010]; // b 에서 북쪽으로 꺾음
  const r = roundCorners([a, b, corner]);
  assert.ok(r.length > 3);
  assert.deepEqual(r[0], a);
  assert.deepEqual(r[r.length - 1], corner);
  const m = (p, q) => Math.hypot((q[0] - p[0]) * 110540, (q[1] - p[1]) * 111320 * Math.cos((p[0] * Math.PI) / 180));
  for (const p of r.slice(1, -1)) assert.ok(m(p, b) <= 15.01);
  assert.deepEqual(roundCorners([a, b, c]), [a, b, c]);
  assert.deepEqual(tidyRoad([a, c]), [a, c]);
});
