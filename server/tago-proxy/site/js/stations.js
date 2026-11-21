// 지도에 찍는 지하철역 (화면 없음: app.js 가 쓰고, 테스트도 불러옴).
// 자료: data/files/subway_stations.tsv (앱 assets 와 같은 파일: 역 코드, 이름, 노선, 위도, 경도, 운영 기관)
// 같은 이름의 역(환승역: 노선마다 한 줄)은 가까우면(700m 안) 점 하나로 묶음 — 폰 앱 StationIndex.groups 처럼

const GROUP_M = 700;
// 서울 말고 "1호선" 같은 이름을 쓰는 도시: 운영 기관으로 도시 이름을 붙임 (부산 1호선 ↔ 서울 1호선)
const CITY_OPS = [["부산", "부산"], ["대구", "대구"], ["광주", "광주"], ["대전", "대전"]];

/** 역 이름 맞추기: 괄호·빈칸 빼고, 3글자 넘는 "OO역" 은 "역" 뺌 (pipeline build_subway_lines.norm, 앱 StationName.norm) */
export function stationNorm(s) {
  let t = "", depth = 0;
  for (const ch of String(s ?? "")) {
    if (ch === "(") depth++;
    else if (ch === ")") depth = Math.max(0, depth - 1);
    else if (depth === 0 && ch !== " ") t += ch;
  }
  return t.length >= 3 && t.endsWith("역") ? t.slice(0, -1) : t;
}

/** 노선 이름 (부산교통공사 "1호선" → "부산 1호선") */
export function lineLabel(line, operator) {
  const l = String(line || "").trim();
  const op = String(operator || "");
  for (const [k, city] of CITY_OPS) if (op.startsWith(k) && /^\d호선$/.test(l)) return city + " " + l;
  return l;
}

const meters = (a, b) => Math.hypot((b.lat - a.lat) * 110540, (b.lng - a.lng) * 111320 * Math.cos((a.lat * Math.PI) / 180));

/** 지도 고치기 열쇠 (앱 MapEdits.stationKey 와 같음: "노선:역 코드:역 이름" 40자, 자료 그대로의 노선·이름) */
export const stationKey = (line, code, name) => `${line}:${code}:${name}`.slice(0, 40);

/** TSV → 역 묶음 [{ name, lat, lng, lines: [노선 이름…], keys: [고치기 열쇠…] }] (위도 차례) */
export function parseStations(tsv) {
  const rows = [];
  String(tsv || "").split("\n").forEach((l, i) => {
    const c = l.replace(/\r$/, "").split("\t");
    if (i === 0 || c.length < 5) return;
    const lat = parseFloat(c[3]), lng = parseFloat(c[4]);
    const name = stationNorm(c[1]);
    if (!name || !Number.isFinite(lat) || !Number.isFinite(lng)) return;
    rows.push({ name, lat, lng, line: lineLabel(c[2], c[5]), key: stationKey(c[2], c[0], c[1]) });
  });
  const groups = [];
  const byName = new Map();
  for (const r of rows) {
    const same = (byName.get(r.name) || []).find((g) => meters(g, r) <= GROUP_M);
    if (same) { if (!same.lines.includes(r.line)) same.lines.push(r.line); same.keys.push(r.key); continue; }
    const g = { name: r.name, lat: r.lat, lng: r.lng, lines: [r.line], keys: [r.key] };
    groups.push(g);
    if (!byName.has(r.name)) byName.set(r.name, []);
    byName.get(r.name).push(g);
  }
  return groups.sort((a, b) => a.lat - b.lat);
}

/** 손으로 고친 위치가 있으면 그것 (묶음의 역 중 처음 고친 것), 없으면 원래 자리. [fix]: 열쇠 → [위도, 경도] 또는 null */
export function stationPos(g, fix) {
  for (const k of g.keys || []) { const p = fix(k); if (p) return p; }
  return [g.lat, g.lng];
}

/** 화면 안 역 (위도 차례 목록에서 나눠 찾기) */
export function stationsIn(groups, s, w, n, e) {
  let lo = 0, hi = groups.length;
  while (lo < hi) { const m = (lo + hi) >> 1; if (groups[m].lat < s) lo = m + 1; else hi = m; }
  const out = [];
  for (let i = lo; i < groups.length && groups[i].lat <= n; i++) if (groups[i].lng >= w && groups[i].lng <= e) out.push(groups[i]);
  return out;
}

/** 역 찾기(국토교통부) 결과에서 [line] 에 맞는 것 (못 찾으면 첫 결과) */
export function pickSearchRow(rows, name, line) {
  const n = stationNorm(name);
  const sameName = rows.filter((r) => stationNorm(r.name) === n);
  const list = sameName.length ? sameName : rows;
  const flat = (x) => String(x || "").replace(/\s|도시철도|선$/g, "");
  const want = flat(line);
  const city = (/^(부산|대구|광주|대전|인천)/.exec(String(line)) || [])[1];
  return list.find((r) => flat(r.line) === want) ||
    list.find((r) => { const f = flat(r.line); return f.endsWith(want.replace(/^(부산|대구|광주|대전)/, "")) && (!city || f.includes(city)) && (city || !/(부산|대구|광주|대전)/.test(f)); }) ||
    list[0] || null;
}
