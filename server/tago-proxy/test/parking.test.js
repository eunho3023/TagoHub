import { test } from "node:test";
import assert from "node:assert/strict";
import { parkingTile, tilesIn, parkingList, parkingDots, parkingLines } from "../site/js/parking.js";
import { parkingRow } from "../../../pipeline/build_parking.mjs";

test("주차장 칸: 0.2도, 화면에 걸친 칸 모두", () => {
  assert.equal(parkingTile(37.5336, 126.9727), "187_634");
  assert.deepEqual(tilesIn({ s: 37.39, n: 37.41, w: 126.99, e: 127.01 }), ["186_634", "186_635", "187_634", "187_635"]);
});

test("주차장 점·카드: 화면 안 것만 가운데부터, 글자 P", () => {
  const list = parkingList("187_634", [["삼각지임시", 37.533578, 126.972694, 169, "공영 노외", "5분 250원", "24시간", "02-749-5314"], ["밖", 37.6, 126.97, 0, "", "", "", ""]]);
  const dots = parkingDots(list, { s: 37.5, n: 37.55, w: 126.95, e: 127.0 });
  assert.deepEqual(dots.map((d) => [d.id, d.label]), [["park:187_634:0", "P"]]);
  assert.deepEqual(parkingLines(list[0]), ["공영 노외 · 169면", "5분 250원 · 24시간 · 02-749-5314"]);
  assert.deepEqual(parkingLines(list[1]), []);
});

test("표준데이터 한 줄 → 요금·운영시간 요약 (잘못 적힌 요금은 뺌)", () => {
  const base = { PRKPLCE_NM: "이촌1동", LATITUDE: "37.520667", LONGITUDE: "126.974302", PRKCMPRT: "12", PRKPLCE_SE: "공영", PRKPLCE_TYPE: "노상", PHONE_NUMBER: "02-749-5314" };
  assert.deepEqual(parkingRow({ ...base, PARKINGCHRGE_INFO: "유료", BASIC_TIME: "5", BASIC_CHARGE: "250", ADD_UNIT_TIME: "2", ADD_UNIT_CHARGE: "51000", WEEKDAY_OPER_OPEN_HHMM: "09:00", WEEKDAY_OPER_COLSE_HHMM: "21:00" }),
    ["이촌1동", 37.520667, 126.974302, 12, "공영 노상", "5분 250원", "평일 09:00~21:00", "02-749-5314"]);
  assert.equal(parkingRow({ ...base, PARKINGCHRGE_INFO: "유료", BASIC_TIME: "60", BASIC_CHARGE: "1000", DAY_CMMTKT: "10000", WEEKDAY_OPER_OPEN_HHMM: "00:00", WEEKDAY_OPER_COLSE_HHMM: "23:59" })[5], "1시간 1,000원, 1일 10,000원");
  assert.equal(parkingRow({ ...base, PARKINGCHRGE_INFO: "무료", WEEKDAY_OPER_OPEN_HHMM: "00:00", WEEKDAY_OPER_COLSE_HHMM: "24:00" })[6], "24시간");
  assert.equal(parkingRow({ ...base, LATITUDE: "0" }), null);
});
