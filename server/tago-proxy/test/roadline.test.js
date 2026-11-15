import { test } from "node:test";
import assert from "node:assert/strict";
import { despike, roundCorners, tidyRoad } from "../site/js/roadline.js";

// 큰길: 서쪽에서 동쪽으로 (a → b → c)
const a = [35.80, 127.10], b = [35.80, 127.1010], c = [35.80, 127.1020];

test("골목 들르기: 큰길 → 골목 정류장 → 큰길로 나와 가던 방향으로 계속 가면 뺌 (앱 Shapes.despike 와 같음)", () => {
  const tip = [35.8004, 127.1010]; // 큰길에서 북쪽으로 44m
  assert.deepEqual(despike([a, b, tip, b, c]), [a, b, c]);
  // 블록을 한 바퀴 돌아 같은 자리로 나와 계속 감 (한 바퀴 약 120m)
  const l1 = [35.8003, 127.1010], l2 = [35.8003, 127.1013], l3 = [35.8000, 127.1013];
  assert.deepEqual(despike([a, b, l1, l2, l3, b, c]), [a, b, c]);
});

test("회차: 고리를 돌거나 막다른 길 끝까지 갔다가 왔던 쪽으로 되돌아가면 크기와 상관없이 남김", () => {
  const back = [35.79995, 127.1000]; // 고리를 돌고 다시 서쪽으로 (반대 차로)
  const l1 = [35.8003, 127.1010], l2 = [35.8003, 127.1013], l3 = [35.8000, 127.1013];
  const loop = [a, b, l1, l2, l3, b, back];
  assert.deepEqual(despike(loop), loop);
  const end = [35.8004, 127.1010];
  const stub = [a, b, end, b, back];
  assert.deepEqual(despike(stub), stub);
  // 마을을 크게 도는 고리 (약 190m) 도 남김
  const v1 = [35.8008, 127.1010], v2 = [35.8008, 127.1015], v3 = [35.8003, 127.1015];
  const big = [a, b, v1, v2, v3, b, c];
  assert.deepEqual(despike(big), big);
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
