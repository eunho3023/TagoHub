import { test } from "node:test";
import assert from "node:assert/strict";
import { hedgedFetch } from "../src/hedge.js";

const sleep = (ms, signal) => new Promise((r, j) => {
  const t = setTimeout(r, ms);
  signal?.addEventListener("abort", () => { clearTimeout(t); j(new Error("aborted")); });
});
/** 차례마다 [ms 뒤, 상태] 로 답하는 가짜 fetch */
function fake(plan) {
  const calls = [];
  const fetcher = async (url, init) => {
    const i = calls.length;
    calls.push({ aborted: false });
    init.signal.addEventListener("abort", () => { calls[i].aborted = true; });
    const [ms, status] = plan[i];
    await sleep(ms, init.signal);
    if (status === "throw") throw new Error("network");
    return new Response("n" + i, { status });
  };
  return { fetcher, calls };
}

test("첫 연결이 매달리면 새로 물은 빠른 답을 씀", async () => {
  const { fetcher, calls } = fake([[2000, 522], [10, 200]]);
  const t0 = Date.now();
  const r = await hedgedFetch("u", {}, { hedgeMs: 50, fetcher });
  assert.equal(r.status, 200);
  assert.equal(await r.text(), "n1");
  assert.ok(Date.now() - t0 < 500);
  assert.equal(calls[0].aborted, true);   // 매달린 연결은 끊음
});

test("빠르게 오면 한 번만 물음", async () => {
  const { fetcher, calls } = fake([[5, 200]]);
  const r = await hedgedFetch("u", {}, { hedgeMs: 50, fetcher });
  assert.equal(r.status, 200);
  await sleep(80);
  assert.equal(calls.length, 1);
});

test("522 로 끝나면 기다리지 않고 바로 다시", async () => {
  const { fetcher, calls } = fake([[5, 522], [5, 200]]);
  const r = await hedgedFetch("u", {}, { hedgeMs: 1000, fetcher });
  assert.equal(r.status, 200);
  assert.equal(calls.length, 2);
});

test("기관이 답한 오류(500·403 등)는 그대로", async () => {
  const { fetcher, calls } = fake([[5, 403]]);
  const r = await hedgedFetch("u", {}, { hedgeMs: 50, fetcher });
  assert.equal(r.status, 403);
  assert.equal(calls.length, 1);
});

test("모두 실패하면 마지막 응답, 응답이 없으면 오류", async () => {
  const a = fake([[5, 522], [5, 522], [5, 502]]);
  assert.equal((await hedgedFetch("u", {}, { hedgeMs: 50, fetcher: a.fetcher })).status, 502);
  const b = fake([[5, "throw"], [5, "throw"], [5, "throw"]]);
  await assert.rejects(hedgedFetch("u", {}, { hedgeMs: 50, fetcher: b.fetcher }));
});

test("POST 는 한 번만, 전체 시간이 지나면 504", async () => {
  const p = fake([[5, 522], [5, 200]]);
  assert.equal((await hedgedFetch("u", { method: "POST" }, { hedgeMs: 10, fetcher: p.fetcher })).status, 522);
  assert.equal(p.calls.length, 1);
  const slow = fake([[1000, 200], [1000, 200]]);
  const r = await hedgedFetch("u", {}, { hedgeMs: 20, tries: 2, totalMs: 60, fetcher: slow.fetcher });
  assert.equal(r.status, 504);
});
