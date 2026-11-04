import { test } from "node:test";
import assert from "node:assert/strict";
import { handleLog, parseUpload } from "../src/log.js";

const now = 1_800_000_000;

test("올린 기록 검사: 이상한 ID·시각은 버리고 같은 분은 한 번", () => {
  const up = parseUpload({
    routeId: "JJB306000104",
    hits: [
      ["JJB306000490", now - 30],
      ["JJB306000490", now - 20],        // 같은 분
      ["JJB306000491", now - 90000],     // 하루보다 오래됨
      ["JJB306000492", now + 3600],      // 미래
      ["bad id!", now],
      ["JJB306000493", "x"],
      "junk",
    ],
  }, now);
  assert.deepEqual(up, { routeId: "JJB306000104", hits: [{ nodeId: "JJB306000490", minute: Math.floor((now - 30) / 60) }] });
  assert.equal(parseUpload({ routeId: "a b", hits: [] }, now), null);
  assert.equal(parseUpload({ routeId: "R1" }, now), null);
  assert.equal(parseUpload(null, now), null);
});

/** D1 흉내: 저장하고 노선·정류장으로 꺼내기만 */
function fakeDb() {
  const rows = new Set();
  const stmt = (sql) => ({
    sql, args: [],
    bind(...a) { return { ...this, args: a }; },
    async run() { if (sql.startsWith("INSERT")) rows.add(this.args.join("|")); return {}; },
    async all() {
      const [r, n, oldest] = this.args;
      const results = [...rows].map((s) => s.split("|")).filter(([a, b, m]) => a === r && b === n && +m >= oldest)
        .map(([, , m]) => ({ minute: +m })).sort((x, y) => x.minute - y.minute);
      return { results };
    },
  });
  return { rows, prepare: stmt, async batch(list) { for (const s of list) await s.run(); } };
}

test("올리고 꺼내기", async () => {
  const env = { DB: fakeDb() };
  const ctx = { waitUntil() {} };
  const t = Math.floor(Date.now() / 1000) - 120;
  const post = new Request("https://x/log/hits", { method: "POST", body: JSON.stringify({ routeId: "R1", hits: [["N1", t], ["N2", t]] }) });
  const r1 = await handleLog(post, new URL(post.url), env, ctx);
  assert.deepEqual(await r1.json(), { saved: 2 });
  const get = new Request("https://x/log/stop?route=R1&node=N1");
  const r2 = await handleLog(get, new URL(get.url), env, ctx);
  assert.deepEqual(await r2.json(), { minutes: [Math.floor(t / 60)] });
  const bad = new Request("https://x/log/stop?route=R1&node=");
  assert.equal((await handleLog(bad, new URL(bad.url), env, ctx)).status, 400);
  const noDb = await handleLog(get, new URL(get.url), {}, ctx);
  assert.equal(noDb.status, 503);
});
