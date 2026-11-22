import { test } from "node:test";
import assert from "node:assert/strict";
import { reason, checkOne, healthCron, handleHealth, CHECKS } from "../src/health.js";

test("고장 이유: 키 문제·한도 초과·제공처 오류를 사람이 읽을 말로", () => {
  assert.equal(reason(401, "Unauthorized"), "인증 실패 (키 확인 필요: HTTP 401)");
  assert.equal(reason(200, "<returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>"), "인증 실패 (키 확인 필요: SERVICE_KEY_IS_NOT_REGISTERED_ERROR)");
  // 기관 서버 시간 초과도 returnAuthMsg 에 담겨 오지만 키 문제가 아님 (울산 도로정보, 2026-11-22)
  assert.equal(reason(504, "<cmmMsgHeader><errMsg>SERVICETIMEOUT_ERROR</errMsg><returnAuthMsg>서비스 연결실패 에러</returnAuthMsg><returnReasonCode>05</returnReasonCode></cmmMsgHeader>"),
    "제공처 기관 서버에 닿지 못함 (SERVICETIMEOUT_ERROR)");
  assert.equal(reason(200, "<cmmMsgHeader><errMsg>APPLICATION_ERROR</errMsg><returnAuthMsg>APPLICATION_ERROR</returnAuthMsg></cmmMsgHeader>"), "제공처 오류 응답 (APPLICATION_ERROR)");
  assert.equal(reason(200, "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"), "하루 요청 한도 초과");
  assert.equal(reason(502, ""), "제공처 서버 오류 (HTTP 502)");
  assert.equal(reason(200, '{"response":{"header":{"resultCode":"00"}}}'), null);
});

test("한 곳 확인: 정상, 내용이 다르면 실패, 연결 실패, 키는 기록에 안 남김", async () => {
  const c = CHECKS.find((x) => x.name === "seoul_bus");
  const env = { DATA_GO_KR_KEY: "SECRET+KEY/1==" };
  assert.deepEqual({ ...(await checkOne(c, env, async () => new Response('{"msgHeader":{"headerCd":"0"}}'), () => 0)), ms: 0 }, { ok: true, msg: "", ms: 0 });
  assert.equal((await checkOne(c, env, async () => new Response('{"msgHeader":{"headerCd":"7"}}'))).msg, '응답 내용이 예상과 다름: {"msgHeader":{"headerCd":"7"}}');
  assert.equal((await checkOne(c, env, async () => new Response("Unauthorized", { status: 401 }))).msg, "인증 실패 (키 확인 필요: HTTP 401)");
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
  assert.equal(seoul.msg, "인증 실패 (키 확인 필요: HTTP 401)");
  assert.equal(j.checks.find((x) => x.name === "kakao").ok, null);   // 확인 안 한 곳
});

test("울산 도로정보: 결과 코드가 정상이어도 도로가 0건이면 실패 (2026-11-17 빈 자료)", async () => {
  const { CHECKS } = await import("../src/health.js");
  const c = CHECKS.find((x) => x.name === "ulsan_road");
  assert.equal(c.ok("<rfcOpenApi><header><resultCode>00</resultCode></header><body><totalCount>0</totalCount><data><list></list></data></body></rfcOpenApi>"), false);
  assert.equal(c.ok("<rfcOpenApi><header><resultCode>00</resultCode></header><body><data><list><RoadID>2</RoadID><RoadName>문수로</RoadName></list></data></body></rfcOpenApi>"), true);
});
