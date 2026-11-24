import { test } from "node:test";
import assert from "node:assert/strict";
import { reason, checkOne, healthCron, healthDue, handleHealth, CHECKS, HEALTH_EVERY_MS, quotaService, noteQuota, extraChecks, kstMidnight, _resetQuotaMemo } from "../src/health.js";

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
  const r = await healthCron(env, fetcher, 0);
  assert.ok(r.failed.includes("seoul_bus"));
  const j = await (await handleHealth(env)).json();
  const seoul = j.checks.find((x) => x.name === "seoul_bus");
  assert.equal(seoul.ok, false);
  assert.equal(seoul.label, "서울 버스");
  assert.equal(seoul.msg, "인증 실패 (키 확인 필요: HTTP 401)");
  assert.equal(j.checks.find((x) => x.name === "kakao").ok, null);   // 확인 안 한 곳
});

test("고장 확인은 정각이 아니어도 마지막 확인이 29분 지났으면 함 (GitHub 예약이 몇 시간씩 건너뛰어서)", async () => {
  let last = null;
  const env = { DB: { prepare: () => ({ run: async () => ({}), first: async () => ({ at: last }) }) } };
  const now = Date.UTC(2026, 10, 23, 7, 13);   // 13분: 예전 방식이면 건너뜀
  assert.equal(await healthDue(env, now), true);   // 기록 없음
  last = now - HEALTH_EVERY_MS - 1000;
  assert.equal(await healthDue(env, now), true);
  last = now - 5 * 60 * 1000;
  assert.equal(await healthDue(env, now), false);   // 5분 전에 했음
  assert.equal(await healthDue({}, now), false);   // DB 없음
});

test("한도 초과 서비스 이름: 공공데이터포털 주소·경로만", () => {
  assert.equal(quotaService("https://apis.data.go.kr/1613000/SubwayInfoService/getSubwaySttnAcctoSchdulList?serviceKey=x"), "1613000/SubwayInfoService");
  assert.equal(quotaService("6410000/busrouteservice/v2/getBusRouteStationListv2"), "6410000/busrouteservice");
  assert.equal(quotaService("http://ws.bus.go.kr/api/rest/x"), null);
  assert.equal(quotaService("https://dapi.kakao.com/v2/local/search"), null);
  // 한국 자정: 11/23 16:00 UTC(= 11/24 01:00 KST) → 11/23 15:00 UTC
  assert.equal(kstMidnight(Date.UTC(2026, 10, 23, 16)), Date.UTC(2026, 10, 23, 15));
});

test("실제 응답이 한도 초과면 적어 두고, 고장 확인에 중계·예약 작업·오늘 한도와 함께 보임", async () => {
  _resetQuotaMemo();
  const now = Date.UTC(2026, 10, 23, 3);   // 12:00 KST
  const quota = new Map(), meta = new Map([["cron:last", now - 30 * 1000]]);
  let relay = { at: now - 20 * 60 * 1000 };   // 20분째 소식 없음
  const DB = {
    prepare: (sql) => ({
      bind: (...a) => ({
        run: async () => { if (/INSERT INTO api_quota/.test(sql)) { const [svc, at, mid] = a; const o = quota.get(svc); quota.set(svc, { svc, at, first: o && o.at >= mid ? o.first : at }); } return {}; },
        all: async () => ({ results: [...quota.values()].filter((r) => r.at >= a[0]) }),
        first: async () => null,
      }),
      run: async () => ({}),
      first: async () => (/kr_relay/.test(sql) ? relay : /poll_meta/.test(sql) ? { v: meta.get("cron:last") } : null),
    }),
  };
  const env = { DB, RELAY_KEY: "k" };
  assert.equal(await noteQuota(env, "1613000/SubwayInfoService/getX", "<returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg>", now), true);
  assert.equal(await noteQuota(env, "1613000/SubwayInfoService/getX", "LIMITED_NUMBER_OF_SERVICE", now + 1000), false);   // 1분에 한 번만
  assert.equal(await noteQuota(env, "1613000/BusRouteInfoInqireService/getX", "<resultCode>00</resultCode>", now), false);   // 정상 응답
  const list = await extraChecks(env, now);
  const by = Object.fromEntries(list.map((c) => [c.name, c]));
  assert.equal(by.kr_relay.ok, false);
  assert.match(by.kr_relay.msg, /20분째 소식 없음/);
  assert.equal(by.cron.ok, true);
  assert.equal(by["quota:1613000/SubwayInfoService"].label, "TAGO 지하철 정보 하루 한도");
  assert.equal(by["quota:1613000/SubwayInfoService"].ok, false);
  // 다음 날이면 어제 한도 초과는 안 보임, 중계가 다시 안부하면 정상
  relay = { at: now + 86400000 - 60000 };
  meta.set("cron:last", now + 86400000 - 20 * 60 * 1000);
  const next = Object.fromEntries((await extraChecks(env, now + 86400000)).map((c) => [c.name, c]));
  assert.equal(next.kr_relay.ok, true);
  assert.equal(next.cron.ok, false);   // 20분째 안 돎
  assert.equal(Object.keys(next).some((k) => k.startsWith("quota:")), false);
});

/** node:sqlite 로 만든 D1 흉내 (실제 SQL 을 돌림) */
async function sqliteD1() {
  const { DatabaseSync } = await import("node:sqlite");
  const db = new DatabaseSync(":memory:");
  const wrap = (sql, a = []) => ({
    bind: (...b) => wrap(sql, b),
    run: async () => { db.prepare(sql).run(...a); return {}; },
    all: async () => ({ results: db.prepare(sql).all(...a) }),
    first: async () => db.prepare(sql).get(...a) || null,
  });
  return { prepare: (sql) => wrap(sql), batch: async (st) => { for (const x of st) await x.run(); } };
}

test("고장 표시는 두 번 이어서 실패해야 (한 번 실패는 다시 물어 보고, 그래도 실패면 기록만)", async () => {
  const env = { DB: await sqliteD1(), DATA_GO_KR_KEY: "k" };
  const tago = (j) => j.checks.find((x) => x.name === "tago_route");
  let mode = "ok", calls = 0;
  const fetcher = async (u) => {
    if (u.includes("getRouteInfoIem")) { calls++; if (mode === "bad" || (mode === "flaky" && calls % 2 === 1)) return new Response('{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"HTTP_ERROR"}}}'); }
    return new Response('{"resultCode":"00"}');
  };
  await healthCron(env, fetcher, 0);
  assert.equal(tago(await (await handleHealth(env)).json()).ok, true);
  mode = "flaky"; calls = 0;                 // 한 번 실패 → 바로 다시 물어 됨
  await healthCron(env, fetcher, 0);
  let t = tago(await (await handleHealth(env)).json());
  assert.equal(t.ok, true); assert.equal(t.failSince, null);
  mode = "bad";                               // 처음 실패: 아직 정상 표시, 실패 시각만
  await healthCron(env, fetcher, 0);
  t = tago(await (await handleHealth(env)).json());
  assert.equal(t.ok, true); assert.ok(t.failSince > 0); assert.ok(t.msg.length > 0);
  await healthCron(env, fetcher, 0);          // 두 번째 실패: 고장
  assert.equal(tago(await (await handleHealth(env)).json()).ok, false);
  mode = "ok";
  await healthCron(env, fetcher, 0);
  t = tago(await (await handleHealth(env)).json());
  assert.equal(t.ok, true); assert.equal(t.failSince, null);
});
