import { test } from "node:test";
import assert from "node:assert/strict";
import { reconcile, dotKey, lineKey } from "../site/js/mapdiff.js";

test("지도 그림 맞추기: 같은 모양은 그대로 두고, 새것만 만들고 없어진 것만 지움", () => {
  let made = 0;
  const removed = [];
  const make = (d) => ({ obj: ++made, id: d.id });
  const remove = (o) => removed.push(o.id);
  const a = { id: "s1", lat: 37.5, lng: 127, color: "#00f", ring: "#fff", r: 5 };
  const b = { id: "s2", lat: 37.6, lng: 127, color: "#00f", ring: "#fff", r: 5 };
  let cur = reconcile(new Map(), [a, b], dotKey, make, remove);
  assert.equal(made, 2);
  const first = cur.get(dotKey(a));
  // 지도를 옮겨 다시 그려도 그대로인 점은 다시 만들지 않음, 사라진 b 만 지움, 새 c 만 만듦
  const c = { ...b, id: "s3", lat: 37.7 };
  cur = reconcile(cur, [{ ...a }, c], dotKey, make, remove);
  assert.equal(made, 3);
  assert.deepEqual(removed, ["s2"]);
  assert.equal(cur.get(dotKey(a)), first);
  // 모양이 바뀌면(고른 정류장 빨강 + 이름표) 새로 만듦
  cur = reconcile(cur, [{ ...a, color: "#D7262E", label: "시청" }, c], dotKey, make, remove);
  assert.equal(made, 4);
  assert.deepEqual(removed, ["s2", "s1"]);
  // 같은 것 두 번은 한 번만
  cur = reconcile(cur, [c, { ...c }], dotKey, make, remove);
  assert.equal(cur.size, 1);
  // 선: 꼭짓점·색이 같으면 같은 열쇠
  const l = { pts: [[37.5, 127], [37.6, 127.1]], color: "#0f0", width: 4 };
  assert.equal(lineKey(l), lineKey({ ...l, pts: l.pts.map((p) => [...p]) }));
  assert.notEqual(lineKey(l), lineKey({ ...l, color: "#f00" }));
  assert.notEqual(lineKey(l), lineKey({ ...l, pts: [[37.5, 127], [37.6, 127.2]] }));
});
