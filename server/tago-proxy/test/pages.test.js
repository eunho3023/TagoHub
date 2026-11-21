import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import worker, { isApiPath, API_PREFIXES, USER_WEB } from "../src/index.js";

test("서버 길과 화면 길 나누기", () => {
  for (const p of ["/health", "/w/edits", "/w/bus/seoul/arrival/1", "/log/x", "/appshape/1", "/h/abc", "/dg/1613000/x"]) assert.ok(isApiPath(p), p);
  for (const p of ["/", "/index.html", "/js/app.js", "/legal/terms.html", "/data/manifest.json", "/healthz", "/wx"]) assert.ok(!isApiPath(p), p);
});

test("Pages _routes.json 이 서버 길과 같음", () => {
  const r = JSON.parse(readFileSync(new URL("../pages/_routes.json", import.meta.url), "utf8"));
  assert.deepEqual(r.include, API_PREFIXES.map((x) => (x.endsWith("/") ? x + "*" : x)));
});

test("예전 서버 주소로 화면을 열면 타볼까로, 서버 길은 그대로", async () => {
  const env = {}, ctx = { waitUntil() {} };
  const r = await worker.fetch(new Request("https://tagohub-proxy.eunho3023.workers.dev/legal/terms.html?a=1"), env, ctx);
  assert.equal(r.status, 301);
  assert.equal(r.headers.get("Location"), USER_WEB + "/legal/terms.html?a=1");
  const h = await worker.fetch(new Request("https://tagohub-proxy.eunho3023.workers.dev/health"), env, ctx);
  assert.equal(await h.text(), "ok");
  // 타볼까 주소에서는 넘기지 않음
  const t = await worker.fetch(new Request("https://tabolkka.pages.dev/health"), env, ctx);
  assert.equal(t.status, 200);
});

test("예약 작업 입구는 열쇠가 맞아야 함", async () => {
  const ctx = { waitUntil() {} };
  const no = await worker.fetch(new Request("https://tabolkka.pages.dev/w/cron", { method: "POST", headers: { "X-Cron-Key": "x" } }), { CRON_KEY: "right-key" }, ctx);
  assert.equal(no.status, 403);
  const none = await worker.fetch(new Request("https://tabolkka.pages.dev/w/cron", { method: "POST" }), {}, ctx);
  assert.equal(none.status, 403);
  const get = await worker.fetch(new Request("https://tabolkka.pages.dev/w/cron"), { CRON_KEY: "right-key" }, ctx);
  assert.equal(get.status, 403);
});
