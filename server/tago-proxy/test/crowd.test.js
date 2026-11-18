import { test } from "node:test";
import assert from "node:assert/strict";

// busdata.js → core.js 가 브라우저 전역을 씀: 최소한으로 흉내
const noop = { addEventListener() {}, removeEventListener() {} };
globalThis.location = { search: "", href: "http://localhost/" };
globalThis.document = { hidden: false, ...noop };
globalThis.window = noop;
// edits.js 가 불러올 때 거는 주기 확인(setInterval)이 테스트를 끝나지 못하게 붙잡지 않게
const realInterval = globalThis.setInterval;
globalThis.setInterval = (...a) => { const t = realInterval(...a); t.unref?.(); return t; };
const { crowdSeoul, crowdGyeonggi, arrivalParts, atOrigin, markWaiting, passed } = await import("../site/js/busdata.js");

test("혼잡·빈자리: 서울 congestion·isFullFlag, 경기 crowded·remainSeatCnt (앱 Crowd 와 같게)", () => {
  assert.equal(crowdSeoul(3, 0), "여유");
  assert.equal(crowdSeoul("6", null), "매우혼잡");
  assert.equal(crowdSeoul(5, "1"), "만차");
  assert.equal(crowdSeoul(0, 0), null);
  assert.equal(crowdGyeonggi(1, 22), "빈자리 22");
  assert.equal(crowdGyeonggi(1, 0), "여유"); // 좌석 없는 시내버스는 0
  assert.equal(crowdGyeonggi(null, -1), null);
  assert.deepEqual(arrivalParts({ arrSeconds: 420, prevStops: 3, vehicleType: "저상버스", crowd: "혼잡" }), ["7분", "3정류장 전", "저상", "혼잡"]);
  assert.deepEqual(arrivalParts({ arrSeconds: 10, prevStops: 0, crowd: "빈자리 5" }), ["정류장 도착", "빈자리 5"]);
});

test("기점에 서 있는 버스는 '정류장 도착'이 아니라 '출발 대기' (앱 OriginWait 와 같게)", () => {
  assert.equal(atOrigin("전주역", "전주역(종점)", "송천동"), true);
  assert.equal(atOrigin("전주역", "전주역", "전주역"), false); // 회차 노선은 닿는 버스일 수도 있어 그대로
  assert.equal(atOrigin("한성여객", "전주역", "송천동"), false);
  const [w, far] = markWaiting("전주역", "전주역", "송천동", [{ prevStops: 0, arrSeconds: 0 }, { prevStops: 4, arrSeconds: 500 }]);
  assert.deepEqual(arrivalParts(w), ["출발 대기"]);
  assert.equal(passed(w, 600), false);
  assert.equal(far.waiting, undefined);
});
