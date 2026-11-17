import { test } from "node:test";
import assert from "node:assert/strict";

// busdata.js → core.js 가 브라우저 전역을 씀: 최소한으로 흉내 (crowd.test.js 와 같게)
const noop = { addEventListener() {}, removeEventListener() {} };
globalThis.location = { search: "", href: "http://localhost/" };
globalThis.document = { hidden: false, ...noop };
globalThis.window = noop;
const realInterval = globalThis.setInterval;
globalThis.setInterval = (...a) => { const t = realInterval(...a); t.unref?.(); return t; };
const { daejeonArrivalList, daejeonBusList, daejeonRows, busAtStops } = await import("../site/js/busdata.js");

const head = (cd) => `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><ServiceResult><msgHeader><currentPage>1</currentPage><headerCd>${cd}</headerCd><headerMsg>정상적으로 처리되었습니다.</headerMsg></msgHeader><msgBody>`;

test("대전 도착: 남은 초·정류장·방면·노선 종류, 출발 전 차(MSG_TP 07, 초 자리에 출발 시각)는 뺌 (2026-11-17 실제 응답 모양)", () => {
  const xml = head(0) + `<itemList><BUS_NODE_ID>8001378</BUS_NODE_ID><DESTINATION>탄방역종점</DESTINATION><EXTIME_MIN>3</EXTIME_MIN><EXTIME_SEC>128</EXTIME_SEC><MSG_TP>03</MSG_TP><ROUTE_CD>30300039</ROUTE_CD><ROUTE_NO>104</ROUTE_NO><ROUTE_TP>2  </ROUTE_TP><STATUS_POS>2</STATUS_POS></itemList>`
    + `<itemList><DESTINATION>탑립동</DESTINATION><EXTIME_MIN>1</EXTIME_MIN><EXTIME_SEC>1448</EXTIME_SEC><MSG_TP>07</MSG_TP><ROUTE_CD>30300096</ROUTE_CD><ROUTE_NO>918</ROUTE_NO><ROUTE_TP>2</ROUTE_TP><STATUS_POS>2</STATUS_POS></itemList></msgBody></ServiceResult>`;
  assert.deepEqual(daejeonArrivalList(xml), [{ routeId: "DJB30300039", routeNo: "104", routeType: "간선버스", vehicleType: null, prevStops: 2, arrSeconds: 128, city: "25", towards: "탄방역종점" }]);
  assert.deepEqual(daejeonRows(head(4) + "</msgBody></ServiceResult>"), []);
  assert.throws(() => daejeonRows(head(2) + "</msgBody></ServiceResult>"));
});

test("대전 버스 위치: 정류장 ID 로 자리 찾기, 같은 정류장이 두 번이면 오는 길(dir 1)은 뒤쪽", () => {
  const xml = head(0) + `<itemList><BUS_NODE_ID>8002589</BUS_NODE_ID><DIR>0</DIR><GPS_LATI>36.359108</GPS_LATI><GPS_LONG>127.309164</GPS_LONG><PLATE_NO>대전75자9263</PLATE_NO></itemList>`
    + `<itemList><BUS_NODE_ID>8002589</BUS_NODE_ID><DIR>1</DIR><GPS_LATI>36.359108</GPS_LATI><GPS_LONG>127.309164</GPS_LONG><PLATE_NO>대전75자9217</PLATE_NO></itemList></msgBody></ServiceResult>`;
  const buses = daejeonBusList(xml);
  assert.deepEqual(buses[0], { nodeId: "DJB8002589", ord: null, vehicleNo: "대전75자9263", lat: 36.359108, lng: 127.309164, dir: 0 });
  const stops = [{ id: "DJB8002589", seq: 7 }, { id: "DJB8001000", seq: 30 }, { id: "DJB8002589", seq: 64 }];
  const at = busAtStops(stops, buses);
  assert.deepEqual([...at.entries()].map(([i, l]) => [i, l.map((b) => b.vehicleNo)]), [[0, ["대전75자9263"]], [2, ["대전75자9217"]]]);
});
