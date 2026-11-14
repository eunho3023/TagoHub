import { test } from "node:test";
import assert from "node:assert/strict";
import { checkShape } from "../src/appshape.js";
import { encode } from "../src/shape.js";

test("앱 모양 검사: 정류장을 지나는 선만 받음", () => {
  const line = encode([[35.80, 127.10], [35.80, 127.12], [35.82, 127.12]], 5);
  const stops = [[35.8001, 127.105], [35.8001, 127.115], [35.81, 127.1201]];
  assert.equal(checkShape(line, stops), null);
  assert.equal(checkShape(line, [[35.9, 127.3], [35.95, 127.35]]), "not on stops");   // 엉뚱한 선
  assert.equal(checkShape(encode([[10, 127], [11, 127]], 5), null), "out of range");
  assert.equal(checkShape("", null), "bad poly");
  assert.equal(checkShape(line, null), null);                                          // 정류장을 모르면 범위만
});
