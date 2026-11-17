import { test } from "node:test";
import assert from "node:assert/strict";
import { PLACE_KINDS, placeTilesIn, placeList, placeDots } from "../site/js/amenity.js";
import { KINDS, placeRow, placeTile, span } from "../../../pipeline/build_amenities.mjs";

test("편의시설 칸: 0.2도, 가운데 칸부터", () => {
  assert.equal(placeTile(37.5336, 126.9727), "187_634");
  assert.deepEqual(placeTilesIn({ s: 37.39, n: 37.41, w: 126.99, e: 127.01 }).sort(), ["186_634", "186_635", "187_634", "187_635"]);
  assert.equal(placeTilesIn({ s: 37.0, n: 37.79, w: 126.6, e: 127.39 })[0], "186_634");
});

test("편의시설 점·카드: 종류마다 글자·색, 화면 안 것만", () => {
  const list = placeList("toilet", "187_634", [["삼각지역", 37.5345, 126.9731, "공중화장실 · 24시간", ""], ["밖", 37.7, 126.97, "", ""]]);
  assert.deepEqual(list[0].lines, ["공중화장실 · 24시간"]);
  const dots = placeDots(list, { s: 37.5, n: 37.55, w: 126.95, e: 127.0 });
  assert.deepEqual(dots.map((d) => [d.id, d.label, d.color]), [["pl:toilet:187_634:0", "화", PLACE_KINDS.toilet.color]]);
  for (const k of Object.keys(KINDS)) assert.ok(PLACE_KINDS[k], k);
});

test("표준데이터 한 줄 → 카드 두 줄", () => {
  assert.equal(span("0000", "2359"), "24시간");
  assert.equal(span("00:00", "00:00"), "");
  assert.equal(span("9:00", "18:00"), "09:00~18:00");
  assert.deepEqual(placeRow("toilet", { TOILET_NM: "서문로119안전센터", LATITUDE: "35.87157637", LONGITUDE: "128.58998480", TOILET_TYPE: "개방화장실", OPEN_TIME: "00:00~24:00", UNISEX_TOILET_YN: "Y", MEN_HANDICAP_TOILET_BOWL_NUMBER: "1" }),
    ["서문로119안전센터", 35.871576, 128.589985, "개방화장실 · 24시간 · 남녀공용", "장애인용 있음"]);
  assert.deepEqual(placeRow("drowsy", { SHLTR_NM: "태인", LATITUDE: "35.66350643", LONGITUDE: "126.9322504", ROAD_ROUTE_NM: "호남선", ROAD_ROUTE_DRC: "순천기점 + 천안종점", PRKPLCE_CO: "14", TOILET_YN: "Y", PHONE_NUMBER: "063-714-6000" }),
    ["태인 졸음쉼터", 35.663506, 126.93225, "호남선 순천기점 + 천안종점 · 14면 · 화장실", "063-714-6000"]);
  const m = placeRow("mobility", { TFCWKER_MVMN_CNTER_NM: "군산시 센터", LATITUDE: "35.97", LONGITUDE: "126.68", RCEPT_PHONE_NUMBER: "063-227-0002", WEEKDAY_RCEPT_OPEN_HHMM: "00:00", WEEKDAY_RCEPT_COLSE_HHMM: "23:59", USE_CHARGE: "기본 700원", CAR_HOLD_CO: "33" });
  assert.deepEqual(m.slice(3), ["예약 063-227-0002 · 접수 24시간", "기본 700원 · 차량 33대"]);
  assert.equal(placeRow("transfer", { TRNSITLC_CNTER_NM: "x", LATITUDE: "36.8", LONGITUDE: "126.9", OPER_YN: "N" }), null);
  assert.equal(placeRow("wheel", { FCLTY_NM: "x", LATITUDE: "0", LONGITUDE: "0" }), null);
});
