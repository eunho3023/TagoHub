import { test } from "node:test";
import assert from "node:assert/strict";
import { bikeDots } from "../site/js/bikes.js";

test("따릉이 점: 화면 안 것만, 가운데에서 가까운 순, 글자는 대수, 0대는 회색", () => {
  const list = [
    { id: "ST-1", name: "먼 곳", lat: 37.509, lng: 127.009, bikes: 3, racks: 10 },
    { id: "ST-2", name: "가운데", lat: 37.5, lng: 127.0, bikes: 0, racks: 10 },
    { id: "ST-3", name: "화면 밖", lat: 37.6, lng: 127.0, bikes: 5, racks: 10 },
  ];
  const b = { s: 37.49, n: 37.51, w: 126.99, e: 127.01 };
  const dots = bikeDots(list, b);
  assert.deepEqual(dots.map((d) => [d.id, d.label, d.color]), [["bike:ST-2", "0", "#9e9e9e"], ["bike:ST-1", "3", "#2e7d32"]]);
  assert.equal(bikeDots(list, b, 1).length, 1);
  assert.deepEqual(bikeDots(list, null), []);
});
