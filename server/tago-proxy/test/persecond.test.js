import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { isPerSecondBody, isQuotaBody, reason, perSecondWait } from "../src/health.js";
import { badWhy } from "../src/poll.js";

// 2026-11-26 07:00 전주 풍남중: TAGO 버스 위치가 이렇게 답했고, 바로 다시 물으면 받아졌음
const PER_SECOND = `<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_PER_SECOND_EXCEEDS_ERROR</errMsg><returnAuthMsg>초당 서비스 요청제한 횟수 초과 에러</returnAuthMsg><returnReasonCode>23</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>`;
const DAILY = "<returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg><returnReasonCode>22</returnReasonCode>";
const OK = `<response><header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header><body><items><item><nodeid>JUB1</nodeid><vehicleno>1556</vehicleno></item></items></body></response>`;

test("초당 한도와 하루 한도를 가름", () => {
  assert.equal(isPerSecondBody(PER_SECOND), true);
  assert.equal(isQuotaBody(PER_SECOND), false);   // 하루 한도로 적지 않음
  assert.equal(isPerSecondBody(DAILY), false);
  assert.equal(isQuotaBody(DAILY), true);
  assert.match(reason(200, PER_SECOND), /초당/);
  assert.equal(reason(200, DAILY), "하루 요청 한도 초과");
  assert.equal(badWhy(200, PER_SECOND), "persec");
  assert.equal(badWhy(200, DAILY), "quota");
  assert.ok(perSecondWait(0, 0) >= 300 && perSecondWait(0, 0.99) < 600);
  assert.ok(perSecondWait(1, 0) >= 1000 && perSecondWait(1, 0.99) < 1500);
});

/** 저장 없음(caches)·바깥 요청을 흉내 내고 /w/dg 를 한 번 부름 */
async function callDg(replies) {
  const oldFetch = globalThis.fetch, oldCaches = globalThis.caches;
  let n = 0;
  globalThis.caches = { default: { match: async () => undefined, put: async () => {} } };
  globalThis.fetch = async () => new Response(replies[Math.min(n++, replies.length - 1)], { status: 200, headers: { "Content-Type": "application/xml" } });
  try {
    const res = await worker.fetch(new Request("https://tabolkka.pages.dev/w/dg/1613000/BusLcInfoInqireService/getRouteAcctoBusLcList?cityCode=35010&routeId=JUB305001144"),
      { DATA_GO_KR_KEY: "test-key" }, { waitUntil() {} });
    return { body: await res.text(), calls: n };
  } finally {
    globalThis.fetch = oldFetch;
    globalThis.caches = oldCaches;
  }
}

test("초당 한도에 걸리면 잠깐 쉬고 다시 물어 받은 것을 줌", async () => {
  const r = await callDg([PER_SECOND, OK]);
  assert.equal(r.calls, 2);
  assert.match(r.body, /vehicleno/);
});

test("하루 한도·정상 응답은 다시 묻지 않음", async () => {
  assert.equal((await callDg([OK])).calls, 1);
  assert.equal((await callDg([DAILY, OK])).calls, 1);
});
