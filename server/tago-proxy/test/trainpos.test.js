import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { adjacency, placeTrain } from "../site/js/trainpos.js";

const lines = JSON.parse(readFileSync(new URL("../../../app/src/main/assets/subway_lines.json", import.meta.url), "utf8"));

test("지하철 노선 모양: 서울 실시간 노선 19개, 이음이 이어짐 (GTX-A 는 따로 운영되는 두 구간)", () => {
  assert.ok(Object.keys(lines).length >= 19);
  for (const [id, l] of Object.entries(lines)) {
    const p = l.stations.map((_, i) => i);
    const f = (i) => (p[i] === i ? i : (p[i] = f(p[i])));
    l.edges.forEach(([i, j]) => { p[f(i)] = f(j); });
    const parts = new Set(l.stations.map((_, i) => f(i))).size;
    assert.equal(parts, id === "1032" ? 2 : 1, `${l.name} 조각 ${parts}`);
  }
  // 2호선은 순환 (이음 수 = 역 수), 6호선 응암 순환: 구산–응암·응암–새절
  assert.equal(lines["1002"].edges.length, lines["1002"].stations.length);
  const six = lines["1006"], at = (n) => six.stations.findIndex((s) => s[0] === n);
  const has = (a, b) => six.edges.some(([i, j]) => (i === at(a) && j === at(b)) || (i === at(b) && j === at(a)));
  assert.ok(has("구산", "응암") && has("응암", "새절") && !has("구산", "새절"));
});

test("열차 자리: 도착은 그 역, 출발은 행선지 쪽 다음 역으로 30%, 진입은 앞 역에서 70%", () => {
  const line = { stations: [["가", 37.0, 127.0], ["나", 37.1, 127.0], ["다", 37.2, 127.0]], edges: [[0, 1], [1, 2]] };
  const adj = adjacency(line);
  const row = (st, s, dest = "다") => ({ statnNm: st, statnTnm: dest, trainSttus: String(s), trainNo: "1", updnLine: "0", directAt: "0", lstcarAt: "0" });
  assert.deepEqual([placeTrain(line, adj, row("나", 1)).lat, placeTrain(line, adj, row("나", 1)).at], [37.1, 1]);
  assert.ok(Math.abs(placeTrain(line, adj, row("나", 2)).lat - 37.13) < 1e-9);    // 다 쪽으로 30%
  assert.ok(Math.abs(placeTrain(line, adj, row("나", 0)).lat - 37.07) < 1e-9);    // 가에서 70%
  assert.ok(Math.abs(placeTrain(line, adj, row("나", 2, "가")).lat - 37.07) < 1e-9); // 반대 방향
  const one = lines["1001"], a1 = adjacency(one);                                  // '역'·괄호 붙은 이름
  assert.equal(one.stations[placeTrain(one, a1, row("서울역", 1, "청량리(서울시립대입구)")).at][0], "서울");
  assert.equal(placeTrain(line, adj, row("없는역", 1)), null);
  const real = lines["1002"], a2 = adjacency(real);
  const t = placeTrain(real, a2, { statnNm: "신설동", statnTnm: "성수", trainSttus: "1", trainNo: "1675", updnLine: "1", directAt: "0", lstcarAt: "0" });
  assert.equal(real.stations[t.at][0], "신설동");
  // 2호선 행선지 "성수종착" → 성수, 출발하면 성수 쪽 다음 역으로 (역 위가 아님)
  const s2 = placeTrain(real, a2, { statnNm: "을지로입구", statnTnm: "성수종착", trainSttus: "2", trainNo: "2", updnLine: "0", directAt: "0", lstcarAt: "0" });
  assert.equal(s2.dest, "성수");
  assert.notDeepEqual([s2.lat, s2.lng], real.stations[s2.at].slice(1, 3));
  // 이름이 다른 역·행선지: 성수종착(역), 신도림지선·응암순환(상선)(행선지), 지제·뚝섬유원지·세종왕릉
  const R = (st, de) => ({ statnNm: st, statnTnm: de, trainSttus: "1", trainNo: "3", updnLine: "0", directAt: "0", lstcarAt: "0" });
  const at = (id, r) => { const l = lines[id], t2 = placeTrain(l, adjacency(l), r); return t2 && [l.stations[t2.at][0], t2.dest]; };
  assert.deepEqual(at("1002", R("성수종착", "신도림지선")), ["성수", "신도림"]);
  assert.deepEqual(at("1006", R("역촌", "응암순환(상선)")), ["역촌", "응암"]);
  assert.deepEqual(at("1001", R("지제", "신창")), ["평택지제", "신창"]);
  assert.deepEqual(at("1007", R("뚝섬유원지", "장암")), ["자양", "장암"]);
  assert.deepEqual(at("1081", R("세종왕릉", "판교")), ["세종대왕릉", "판교"]);
  assert.deepEqual(at("1001", R("지행", "광운대행")), ["지행", "광운대"]);   // 역 이름 끝 '행'은 그대로
});
