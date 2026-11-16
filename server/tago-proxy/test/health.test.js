import { test } from "node:test";
import assert from "node:assert/strict";
import { reason, checkOne, healthCron, handleHealth, CHECKS } from "../src/health.js";

test("고장 이유: 키 문제·한도 초과·제공처 오류를 사람이 읽을 말로", () => {
  assert.equal(reason(401, "Unauthorized"), "인증 실패 (키 확인 필요)");
  assert.equal(reason(200, "<returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>"), "인증 실패 (키 확인 필요)");
  assert.equal(reason(200, "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"), "하루 요청 한도 초과");
  assert.equal(reason(502, ""), "제공처 서버 오류 (HTTP 502)");
  assert.equal(reason(200, '{"response":{"header":{"resultCode":"00"}}}'), null);
});

test("한 곳 확인: 정상, 내용이 다르면 실패, 연결 실패, 키는 기록에 안 남김", async () => {
  const c = CHECKS.find((x) => x.name === "seoul_bus");
  const env = { DATA_GO_KR_KEY: "SECRET+KEY/1==" };
  assert.deepEqual({ ...(await checkOne(c, env, async () => new Response('{"msgHeader":{"headerCd":"0"}}'), () => 0)), ms: 0 }, { ok: true, msg: "", ms: 0 });
  assert.equal((await checkOne(c, env, async () => new Response('{"msgHeader":{"headerCd":"7"}}'))).msg, "응답 내용이 예상과 다름");
  assert.equal((await checkOne(c, env, async () => new Response("Unauthorized", { status: 401 }))).msg, "인증 실패 (키 확인 필요)");
  const failed = await checkOne(c, env, async (u) => { throw new Error("connect to " + u); });
  assert.equal(failed.ok, false);
  assert.ok(!failed.msg.includes("SECRET") && !failed.msg.includes("SECRET%2BKEY"), failed.msg);
  assert.equal(await checkOne(c, {}, async () => new Response("")), null); // 키 없으면 건너뜀
});

test("예약 작업이 기록하고 /w/health 가 이름표와 함께 돌려줌", async () => {
  const rows = new Map();
  const db = {
    prepare: (sql) => ({
      run: async () => ({}),
      bind: (...a) => ({ sql, a }),
      all: async () => ({ results: [...rows.values()] }),
    }),
    batch: async (stmts) => { for (const s of stmts) { const [name, ok, msg, ms, at] = s.a; rows.set(name, { name, ok, msg, ms, at, last_ok: ok ? at : null, fail_since: ok ? null : at }); } },
  };
  const env = { DB: db, DATA_GO_KR_KEY: "k" };   // 서울·카카오 키 없음 → 그 확인은 건너뜀
  const fetcher = async (u) => new Response(u.includes("ws.bus.go.kr") ? "Unauthorized" : '{"resultCode":"00","resultCode2":0}', { status: u.includes("ws.bus.go.kr") ? 401 : 200 });
  const r = await healthCron(env, fetcher);
  assert.ok(r.failed.includes("seoul_bus"));
  const j = await (await handleHealth(env)).json();
  const seoul = j.checks.find((x) => x.name === "seoul_bus");
  assert.equal(seoul.ok, false);
  assert.equal(seoul.label, "서울 버스");
  assert.equal(seoul.msg, "인증 실패 (키 확인 필요)");
  assert.equal(j.checks.find((x) => x.name === "kakao").ok, null);   // 확인 안 한 곳
});
