import { test } from "node:test";
import assert from "node:assert/strict";
import { despike, roundCorners, tidyRoad, straighten } from "../site/js/roadline.js";

// 큰길: 서쪽에서 동쪽으로 (a → b → c)
const a = [35.80, 127.10], b = [35.80, 127.1010], c = [35.80, 127.1020], e = [35.80, 127.1035];
const l1 = [35.8003, 127.1010], l2 = [35.8003, 127.1013], l3 = [35.8000, 127.1013];

test("골목 들르기: 같은 자리로 나와 가던 방향으로 계속 가면 뺌 (앱 Shapes.despike 와 같음)", () => {
  const tip = [35.8004, 127.1010]; // 큰길에서 북쪽으로 44m
  assert.deepEqual(despike([a, b, tip, b, c]), [a, b, c]);
  assert.deepEqual(despike([a, b, l1, l2, l3, b, c]), [a, b, c]);
  // 교차로에서 꺾여 나가도(들어온 방향과 90도) 회차가 아님 → 뺌
  const south = [35.7990, 127.1010];
  assert.deepEqual(despike([a, b, l1, l2, l3, b, south]), [a, b, south]);
});

test("회차: 고리를 돌거나 막다른 길 끝까지 갔다가 왔던 쪽으로 되돌아가면 남김", () => {
  const back = [35.79995, 127.1000];
  const loop = [a, b, l1, l2, l3, b, back];
  assert.deepEqual(despike(loop), loop);
  const stub = [a, b, [35.8004, 127.1010], b, back];
  assert.deepEqual(despike(stub), stub);
});

test("회전교차로를 도는 길은 남김", () => {
  // b 동쪽 15m 를 중심으로 반지름 15m: 동쪽으로 오다가 b(서쪽 끝)에서 들어가 시계 반대 방향으로 270도 돌고 북쪽으로 나감 (좌회전)
  const k = 111320 * Math.cos((35.8 * Math.PI) / 180), cLng = 127.1010 + 15 / k;
  const ring = [];
  for (let d = 180; d <= 450; d += 15) {
    const t = (d * Math.PI) / 180;
    ring.push([35.80 + (15 * Math.sin(t)) / 110540, cLng + (15 * Math.cos(t)) / k]);
  }
  const exit = ring[ring.length - 1], north = [exit[0] + 0.0004, exit[1]];
  const path = [a, ...ring, north];
  assert.deepEqual(despike(path), path);
});

test("큰길 옆에 찍힌 정류장 때문에 옆길로 빠졌다 들어온 우회는 곧게, 정류장 없는 굽은 길·터미널 들르기는 그대로", () => {
  const s1 = [35.8005, 127.1012], s2 = [35.8005, 127.1020], back = [35.80, 127.1022];
  const path = [a, b, s1, s2, back, e];
  const stray = [35.8004, 127.1015, "풍남문"];
  assert.deepEqual(despike(path, [stray]), [a, b, back, e]);
  assert.deepEqual(despike(path), path);
  assert.deepEqual(despike(path, [[35.8004, 127.1015, "강진터미널"]]), path);
  // 같은 자리로 돌아오는 고리도 터미널을 들르면 그대로
  const loop = [a, b, l1, l2, l3, b, c];
  assert.deepEqual(despike(loop, [[35.8003, 127.1011, "임실터미널"]]), loop);
});

test("꺾이는 곳은 모서리 15m 안에서 둥글게, 곧은 길은 그대로", () => {
  const corner = [35.8010, 127.1010];
  const r = roundCorners([a, b, corner]);
  assert.ok(r.length > 3);
  assert.deepEqual(r[0], a);
  assert.deepEqual(r[r.length - 1], corner);
  const m = (p, q) => Math.hypot((q[0] - p[0]) * 110540, (q[1] - p[1]) * 111320 * Math.cos((p[0] * Math.PI) / 180));
  for (const p of r.slice(1, -1)) assert.ok(m(p, b) <= 15.01);
  assert.deepEqual(roundCorners([a, b, c]), [a, b, c]);
  assert.deepEqual(tidyRoad([a, c]), [a, c]);
});

test("노선 정류장 차례가 되돌아가는 곳(길 건너 같은 정류장에 이어 섬)은 꺾어 나가도 회차로 남김 (기린대로 병무청)", () => {
  const south = [35.7990, 127.1010];
  const path = [a, b, l1, l2, l3, b, south];
  assert.deepEqual(despike(path), [a, b, south]);   // 정류장을 모르면 꺾어 나가는 고리는 뺌
  const pair = [[35.8003, 127.10105, "기린대로 병무청"], [35.8003, 127.10125, "기린대로 병무청"]];   // 약 18m 마주 봄
  assert.deepEqual(despike(path, [[35.79, 127.09, "앞"], ...pair, [35.79, 127.11, "뒤"]]), path);
});

test("정류장 사이 길 고치기: 이어지는 두 정류장(id) 사이를 고친 길로 바꿔 끼움", async () => {
  const { applySegFixes } = await import("../site/js/roadline.js");
  const line = [[35.80, 127.10], [35.80, 127.101], [35.80, 127.102], [35.80, 127.103], [35.80, 127.104]];
  const fix = { from: "A", to: "B", path: [[35.8001, 127.101], [35.801, 127.1015], [35.8001, 127.1022]] };
  const stops = [[35.80, 127.10, "처음", "S"], [35.8001, 127.101, "가", "A"], [35.8001, 127.102, "나", "B"], [35.80, 127.104, "끝", "E"]];
  const out = applySegFixes(line, stops, [fix]);
  assert.deepEqual(out, [[35.80, 127.10], [35.80, 127.101], ...fix.path, [35.80, 127.102], [35.80, 127.103], [35.80, 127.104]]);
  // 차례가 다르거나(B → A) 선에서 멀면 그대로
  assert.deepEqual(applySegFixes(line, [stops[2], stops[1]], [fix]), line);
  assert.deepEqual(applySegFixes(line, [[36.0, 127.0, "", "A"], [36.0, 127.001, "", "B"]], [fix]), line);
});

// 곧은 길 펴기: 동쪽으로 가는 큰길 (x m 동쪽, y m 북쪽)
const KX = 111320 * Math.cos((35.8 * Math.PI) / 180), KY = 110540;
const at = (x, y) => [35.8 + y / KY, 127.1 + x / KX];
const yOf = (p) => (p[0] - 35.8) * KY;

test("곧은 길 펴기: 큰길에서 차로 쪽으로 들쭉날쭉 틀어졌다 돌아오는 혹(전주 104 거마평로)·3m 떨림을 곧게", () => {
  const line = [];
  for (let x = 0; x <= 600; x += 15) {
    // 250~340m: 4번 꺾이며 동쪽으로 12m 불룩 (카카오 길이 차로를 바꿨다 돌아옴), 나머지는 ±2m 떨림
    const y = x >= 250 && x <= 340 ? [0, 6, 12, 5, 11, 4, 12][Math.round((x - 250) / 15)] ?? 0 : (x / 15) % 2 ? 2 : -1;
    line.push(at(x, y));
  }
  const s = straighten(line);
  assert.ok(s.length < line.length / 3, `점 ${s.length}개`);
  // 처음·끝 30m 를 뺀 가운데는 모두 큰길 3m 안
  for (const p of s.slice(1, -1)) assert.ok(Math.abs(yOf(p)) <= 3, `y ${yOf(p).toFixed(1)}`);
});

test("곧은 길 펴기: 시골길의 완만한 굽이·회전교차로·터미널 들르기는 그대로", () => {
  // 들어온·나간 방향이 같은 C자 굽이 (꼭짓점 듬성듬성, 15m 벗어남): 그대로
  const bend = [at(-100, 0), at(0, 0), at(70, 8), at(140, 15), at(210, 8), at(280, 0), at(380, 0)];
  assert.deepEqual(straighten(bend), bend);
  // 작은 회전교차로를 반 바퀴만 돌고 같은 방향으로 나감 (반지름 12m, 도아삼거리): 그대로
  const ring = [at(-100, 0), at(-50, 0), at(-12, 0)];
  for (let d = 180; d >= 0; d -= 20) ring.push(at(12 * Math.cos((d * Math.PI) / 180), -12 * Math.sin((d * Math.PI) / 180)));
  ring.push(at(50, 0), at(100, 0));
  const r = straighten(ring);
  assert.ok(r.some((p) => yOf(p) < -8), "회전교차로를 돎");
  // 터미널 곁으로 들르는 혹: 그대로
  const visit = [];
  for (let x = 0; x <= 400; x += 15) visit.push(at(x, x >= 180 && x <= 240 ? [0, 8, 12, 8, 0][Math.round((x - 180) / 15)] : 0));
  assert.ok(straighten(visit, [[...at(210, 18), "임실터미널"]]).some((p) => yOf(p) > 10));
});
