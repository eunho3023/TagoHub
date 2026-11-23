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
const { crowdSeoul, crowdGyeonggi, arrivalParts, atOrigin, markWaiting, passed, jeonjuType, jeonjuNo, inService, kstMinute } = await import("../site/js/busdata.js");

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

test("전주 노선 종류: 기점과 종점이 같으면 순환 (2번 평화동종점→평화동종점, 앱 JeonjuMappers 와 같게)", () => {
  assert.equal(jeonjuType("0", "2", "평화동종점", "평화동종점"), "순환");
  assert.equal(jeonjuType("0", "104", "평화동종점", "송천동종점"), "일반");
  assert.equal(jeonjuNo("3", "1"), "3-1");   // 가지 번호 붙임
  assert.equal(jeonjuNo(3, 2), "3-2");
  assert.equal(jeonjuNo("2", "0"), "2");
  assert.equal(jeonjuNo("104", undefined), "104");
  assert.equal(jeonjuType("1", "7"), "일반");   // brtClass 1 은 가지 번호(7-1)
  assert.equal(jeonjuType("0", "6001", "비전대학교", "비전대학교"), "간선");   // 간선이 먼저
  assert.equal(jeonjuType("0", "4000"), "간선");
});

test("위치로 지나감: 3정류장 지난 버스의 낡은 '도착'은 뺌", async () => {
  const { verdict } = await import("../site/js/busdata.js");
  assert.equal(verdict(18, [21], 0), "passed");
  assert.equal(verdict(18, [21, 16], 2), "keep");
  assert.equal(verdict(18, [30], 0), "keep");
});

test("도착 없음 → 운행종료·첫차까지·다음 출발 (앱 NoLiveText 와 같음)", async () => {
  const { noLive, waitText } = await import("../site/js/busdata.js");
  assert.deepEqual(noLive("06:10", "19:30", 290, 101), { main: "운행종료", sub: "첫차 06:10 · 4시간 29분 후", ended: true });
  assert.equal(noLive("06:10", "19:30", null, 21 * 60 + 30).sub, "내일 첫차 06:10 · 8시간 40분 후");
  assert.equal(noLive("06:00", "22:00", 20, 14 * 60 + 25).main, "다음 출발 약 14:40");
  assert.equal(noLive("05:30", "00:30", null, 23 * 60 + 50).main, "막차 00:30");
  assert.equal(noLive(null, "22:00", 10, 600), null);
  assert.equal(waitText(60), "1시간");
});

test("시간표상 운행 시간 안인지 (실시간 위치가 안 오는 노선 문구, 앱 TagoTime.inService 와 같음)", () => {
  assert.equal(inService("07:40", "18:00", 17 * 60 + 58), true);    // 여수 365-5 오후 5시 58분
  assert.equal(inService("07:40", "18:00", 18 * 60 + 30), false);
  assert.equal(inService("07:40", "18:00", 7 * 60), false);
  assert.equal(inService("05:30", "00:30", 23 * 60 + 50), true);    // 막차가 자정 넘어
  assert.equal(inService("05:30", "00:30", 10), true);
  assert.equal(inService("05:30", "00:30", 4 * 60), false);
  assert.equal(inService(null, "18:00", 600), false);
  assert.equal(kstMinute(Date.UTC(2026, 10, 20, 8, 58)), 17 * 60 + 58);
});

test("하루 한 번만 다니는 노선(첫차 = 막차): 하루 1번 시각으로", async () => {
  const { noLive, planText } = await import("../site/js/busdata.js");
  assert.deepEqual(noLive("22:40", "22:40", null, 20 * 60 + 43), { main: "하루 1번 22:40", sub: "1시간 57분 후 · 기점", ended: false });
  assert.deepEqual(noLive("06:00", "06:00", null, 7 * 60), { main: "출발함", sub: "내일 06:00 · 하루 1번 · 23시간 후", ended: false });
  assert.equal(noLive("06:00", "06:00", null, 20 * 60 + 43).main, "운행종료");
  assert.equal(planText({ first: "06:00", last: "06:00", interval: 15 }, 1), "하루 1번 06:00");
  assert.equal(planText({ first: "06:00", last: "22:20", interval: 15 }, 1), "첫차 06:00 · 막차 22:20 · 배차 15분");
});

test("차 종류: 서울 busType·경기 lowPlate (모르는 값은 null)", async () => {
  const { seoulKind, ggKind } = await import("../site/js/busdata.js");
  assert.equal(seoulKind("1"), "저상");
  assert.equal(seoulKind("0"), "일반");
  assert.equal(seoulKind("2"), "굴절");
  assert.equal(seoulKind(undefined), null);
  assert.equal(ggKind(1), "저상");
  assert.equal(ggKind(0), "일반");
  assert.equal(ggKind(2), "2층");
  assert.equal(ggKind(9), null);
});
