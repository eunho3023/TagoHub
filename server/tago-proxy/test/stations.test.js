import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { parseStations, stationsIn, stationNorm, lineLabel, pickSearchRow } from "../site/js/stations.js";

const tsv = readFileSync(new URL("../../../app/src/main/assets/subway_stations.tsv", import.meta.url), "utf8");

test("역 이름 맞추기", () => {
  assert.equal(stationNorm("교대(법원, 검찰청)"), "교대");
  assert.equal(stationNorm("서울역"), "서울");
  assert.equal(stationNorm("시청역"), "시청");
});

test("부산·대구 같은 도시 노선 이름", () => {
  assert.equal(lineLabel("1호선", "부산교통공사"), "부산 1호선");
  assert.equal(lineLabel("1호선", "코레일"), "1호선");
  assert.equal(lineLabel("동해선", "코레일"), "동해선");
});

test("환승역은 점 하나로, 같은 이름 먼 역은 따로", () => {
  const gs = parseStations(tsv);
  assert.ok(gs.length > 700 && gs.length < 1124);
  const seoul = gs.filter((g) => g.name === "서울");
  assert.equal(seoul.length, 1);
  assert.ok(seoul[0].lines.includes("1호선") && seoul[0].lines.includes("4호선"));
  // 부산 서면 (부산 1·2호선)
  const seomyeon = gs.find((g) => g.name === "서면");
  assert.deepEqual(seomyeon.lines.sort(), ["부산 1호선", "부산 2호선"]);
  // 양평: 경의중앙선 양평(경기)과 5호선 양평(서울)은 따로
  assert.ok(gs.filter((g) => g.name === "양평").length >= 2);
});

test("화면 안 역", () => {
  const gs = parseStations(tsv);
  const inView = stationsIn(gs, 37.55, 126.96, 37.57, 126.99);
  assert.ok(inView.some((g) => g.name === "시청"));
  assert.ok(inView.every((g) => g.lat >= 37.55 && g.lat <= 37.57 && g.lng >= 126.96 && g.lng <= 126.99));
});

test("역 찾기 결과에서 노선 고르기", () => {
  const rows = [{ id: "A", name: "서면", line: "부산 도시철도 2호선" }, { id: "B", name: "서면", line: "부산 도시철도 1호선" }];
  assert.equal(pickSearchRow(rows, "서면", "부산 1호선").id, "B");
  const s = [{ id: "S1", name: "서울역", line: "서울 1호선" }, { id: "S4", name: "서울역", line: "서울 4호선" }, { id: "A", name: "서울역", line: "공항철도" }];
  assert.equal(pickSearchRow(s, "서울", "4호선").id, "S4");
  assert.equal(pickSearchRow(s, "서울", "공항철도선").id, "A");
  assert.equal(pickSearchRow([], "서울", "1호선"), null);
});
