// 지하철 열차 자리 (화면 없음: subway.js 노선 화면이 쓰고, 테스트도 불러옴)
// 서울 실시간 열차 위치(realtimePosition) 한 행 → 노선 모양(data/files/subway_lines.json) 위 자리

/** 열차 상태 (realtimePosition trainSttus) */
export const TRAIN_STATUS = { 0: "진입", 1: "도착", 2: "출발", 3: "전역 출발" };
/** 역 이름 맞추기 (subway.js stationNorm·앱 StationName.norm 과 같음): 괄호·빈칸 빼고, 3글자 넘는 'OO역' 은 '역' 뺌 */
const norm = (s) => { const t = String(s || "").replace(/\(.*?\)/g, "").replace(/ /g, "").trim(); return t.length >= 3 && t.endsWith("역") ? t.slice(0, -1) : t; };

/** 노선 이음 → 역마다 이웃 역 목록 */
export function adjacency(line) {
  const adj = line.stations.map(() => []);
  for (const [i, j] of line.edges) { adj[i].push(j); adj[j].push(i); }
  return adj;
}
/** [from] 역에서 모든 역까지 몇 역 (이어지지 않으면 Infinity) */
function hops(adj, from) {
  const d = adj.map(() => Infinity), q = [from];
  d[from] = 0;
  while (q.length) { const i = q.shift(); for (const j of adj[i]) if (d[j] === Infinity) { d[j] = d[i] + 1; q.push(j); } }
  return d;
}
/**
 * 열차 한 대(realtimePosition 행)를 지도 위 자리로. 서울 API 는 지금 역·상태·행선지만 주므로
 * 행선지 쪽 이웃이 다음 역, 반대쪽 이웃이 앞 역: 도착은 그 역, 출발은 다음 역 쪽 30%, 진입은 앞 역에서 70%, 전역 출발은 앞 역에서 30%.
 * 노선에서 역을 못 찾으면 null → {lat, lng, at(가까운 역), dest, status, express, last, no, upDown}
 */
export function placeTrain(line, adj, r) {
  const idx = new Map(line.stations.map((s, i) => [s[0], i]));
  const at = idx.get(norm(String(r.statnNm || "")));
  if (at == null) return null;
  // 행선지: 2호선은 "성수종착"·"신도림종착"처럼 옴 → "성수"
  const destName = String(r.statnTnm || "").trim().replace(/종착$/, "").replace(/행$/, "");
  const dest = idx.get(norm(destName));
  const status = parseInt(r.trainSttus, 10);
  let next = null, prev = null;
  if (dest != null && dest !== at) {
    const d = hops(adj, dest);
    next = adj[at].find((j) => d[j] < d[at]) ?? null;
    prev = adj[at].find((j) => d[j] > d[at]) ?? null;
  }
  const P = (i) => [line.stations[i][1], line.stations[i][2]];
  const mid = (a, b, k) => [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k];
  const here = P(at);
  const xy = status === 2 && next != null ? mid(here, P(next), 0.3)
    : status === 0 && prev != null ? mid(P(prev), here, 0.7)
    : status === 3 && prev != null ? mid(P(prev), here, 0.3)
    : here;
  return { lat: xy[0], lng: xy[1], at, dest: dest != null ? line.stations[dest][0] : destName || null, status,
    express: r.directAt === "1", last: r.lstcarAt === "1", no: String(r.trainNo || ""), upDown: String(r.updnLine || "") };
}

