// 지하철 열차 자리 (화면 없음: subway.js 노선 화면이 쓰고, 테스트도 불러옴)
// 서울 실시간 열차 위치(realtimePosition) 한 행 → 노선 모양(data/files/subway_lines.json) 위 자리

/** 열차 상태 (realtimePosition trainSttus) */
export const TRAIN_STATUS = { 0: "진입", 1: "도착", 2: "출발", 3: "전역 출발" };
/** 역 이름 맞추기 (subway.js stationNorm·앱 StationName.norm 과 같음): 괄호·빈칸 빼고, 3글자 넘는 'OO역' 은 '역' 뺌 */
const norm = (s) => { const t = String(s || "").replace(/\(.*?\)/g, "").replace(/ /g, "").trim(); return t.length >= 3 && t.endsWith("역") ? t.slice(0, -1) : t; };

/** 노선 이음 → 역마다 이웃 역 목록 */
/** 서울 실시간 API 역 이름 ↔ 노선 자료(한국철도공단) 이름이 다른 곳 (2026-11 19개 노선 실제 응답으로 찾음) */
const ALIASES = { 지제: "평택지제", 뚝섬유원지: "자양", 세종왕릉: "세종대왕릉" };
/** API 역·행선지 이름 → 노선 자료 이름: "성수종착"·"신도림지선"·"응암순환(상선)" → 성수·신도림·응암 */
const apiName = (s, dest = false) => {
  const raw = String(s || "").trim();
  const n = norm(dest ? raw.replace(/행$/, "") : raw).replace(/(종착|지선|순환)$/, "");   // '행'은 행선지에서만 (역 이름 '지행')
  return ALIASES[n] || n;
};

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
/** polyline 5자리 → [[위도, 경도]] (pipeline/build_subway_lines.py encode) */
export function decodePath(str) {
  const out = [];
  let i = 0, la = 0, ln = 0;
  while (i < str.length) {
    for (let k = 0; k < 2; k++) {
      let r = 0, sh = 0, b;
      do { b = str.charCodeAt(i++) - 63; r |= (b & 31) << sh; sh += 5; } while (b >= 32 && i < str.length);
      const v = r & 1 ? ~(r >> 1) : r >> 1;
      if (k) ln += v; else la += v;
    }
    out.push([la / 1e5, ln / 1e5]);
  }
  return out;
}

/** 역 i → 역 j 선로 모양 (subway_lines.json paths, 없으면 두 역 직선) */
export function edgePath(line, i, j) {
  const P = (k) => [line.stations[k][1], line.stations[k][2]];
  const e = line.edges.findIndex(([a, b]) => (a === i && b === j) || (a === j && b === i));
  const s = e >= 0 && line.paths ? line.paths[e] : "";
  if (!s) return [P(i), P(j)];
  if (!line._dec) line._dec = [];
  const pts = line._dec[e] || (line._dec[e] = decodePath(s));
  return line.edges[e][0] === i ? pts : [...pts].reverse();
}

/** 선 위 [k] (0~1) 비율 지점 */
export function alongPath(pts, k) {
  const m = (a, b) => Math.hypot((b[0] - a[0]) * 110540, (b[1] - a[1]) * 111320 * Math.cos((a[0] * Math.PI) / 180));
  const seg = pts.slice(1).map((p, t) => m(pts[t], p));
  let want = seg.reduce((x, y) => x + y, 0) * k;
  for (let t = 0; t < seg.length; t++) {
    if (want <= seg[t] || t === seg.length - 1) {
      const f = seg[t] ? Math.min(1, want / seg[t]) : 0;
      return [pts[t][0] + (pts[t + 1][0] - pts[t][0]) * f, pts[t][1] + (pts[t + 1][1] - pts[t][1]) * f];
    }
    want -= seg[t];
  }
  return pts[0];
}

export function placeTrain(line, adj, r) {
  const idx = new Map(line.stations.map((s, i) => [s[0], i]));
  const at = idx.get(apiName(r.statnNm));
  if (at == null) return null;
  // 행선지: 2호선은 "성수종착"·"신도림종착"처럼 옴 → "성수"
  const destName = apiName(r.statnTnm, true);
  const dest = idx.get(destName);
  const status = parseInt(r.trainSttus, 10);
  let next = null, prev = null;
  if (dest != null && dest !== at) {
    const d = hops(adj, dest);
    next = adj[at].find((j) => d[j] < d[at]) ?? null;
    prev = adj[at].find((j) => d[j] > d[at]) ?? null;
  }
  const P = (i) => [line.stations[i][1], line.stations[i][2]];
  // 두 역 사이는 선로 모양을 따라 (없으면 직선)
  const mid = (a, b, k) => alongPath(edgePath(line, a, b), k);
  const here = P(at);
  const xy = status === 2 && next != null ? mid(at, next, 0.3)
    : status === 0 && prev != null ? mid(prev, at, 0.7)
    : status === 3 && prev != null ? mid(prev, at, 0.3)
    : here;
  return { lat: xy[0], lng: xy[1], at, dest: dest != null ? line.stations[dest][0] : destName || null, status,
    express: r.directAt === "1", last: r.lstcarAt === "1", no: String(r.trainNo || ""), upDown: String(r.updnLine || "") };
}

