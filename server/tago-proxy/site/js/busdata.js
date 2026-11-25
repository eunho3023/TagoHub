// 버스 자료 (Compose 웹 WebRepo.kt·MetroBus.kt 와 같은 규칙).
// 정류장 찾기는 서버 이름 조각(/data/names), 도착은 웹 통로(/w/dg/1613000 TAGO, /w/bus/seoul|gg 서울·경기).
import { cached, getText, getJson, xmlRows, findRows, num, int, str, meters, cacheGet, cachePut, naturalCompare } from "./core.js";
import * as Edits from "./edits.js";
import { seoulTime } from "./busahead.js";

// ── 자료 조각 주소 (pipeline/build_web_data.py 와 같게) ──
function bucket(id) {
  let h = 0x811c9dc5;
  for (const b of new TextEncoder().encode(id)) { h ^= b; h = Math.imul(h, 0x01000193) >>> 0; }
  return h % 1024;
}
export const tileName = (lat, lng) => `${Math.floor(lat * 10)}_${Math.floor(lng * 10)}`;
export function tileNames(s, w, n, e) {
  const out = [];
  for (let a = Math.floor(s * 10); a <= Math.floor(n * 10); a++) for (let o = Math.floor(w * 10); o <= Math.floor(e * 10); o++) out.push(`${a}_${o}`);
  return out;
}

// 인천 정류장·노선 id 바뀐 것 맞추기 (TagoIds.kt)
export function currentId(id) {
  if (id.length < 5 || !id.startsWith("ICB")) return id;
  return id[3] === "1" ? "ICB3" + id.slice(4) : id[3] === "2" ? "ICB4" + id.slice(4) : id;
}
export function legacyId(id) {
  if (id.length < 5 || !id.startsWith("ICB")) return id;
  return id[3] === "3" ? "ICB1" + id.slice(4) : id[3] === "4" ? "ICB2" + id.slice(4) : id;
}

/** 조각 한 줄 → 정류장 {id, name, lat, lng, no, city, routes, spot}.
 * spot: 한 점으로 묶인 정류장의 대표 id (pipeline/stop_spots.py — 같은 방면에 시군마다 쪼개진 정류장), 혼자면 null, 옛 자료면 undefined */
function stopOf(a) {
  if (!Array.isArray(a) || a[0] == null) return null;
  let lat = num(a[2]), lng = num(a[3]);
  // 지도에서 손으로 고친 위치가 있으면 그것 (edits.js)
  const fix = Edits.stopFix(String(a[0]));
  if (fix) { lat = fix[0]; lng = fix[1]; }
  if (lat == null || lng == null) return null;
  return { id: String(a[0]), name: String(a[1] ?? ""), lat, lng, no: str(a[4]) && a[4] !== "null" ? String(a[4]) : null, city: String(a[5] ?? ""), routes: (a[6] || []).map(String),
    spot: a.length > 7 ? (a[7] == null ? null : String(a[7])) : undefined };
}
const parseStops = (rows) => (Array.isArray(rows) ? rows.map(stopOf).filter(Boolean) : []);

async function tile(name) { try { const rows = await cached(`data/tiles/${name}.json`); await Edits.ready(); return parseStops(rows); } catch (e) { return []; } }

const SAME_SPOT_M = 30, SAME_NO_M = 500, BORROW_M = 120;
export function sameStop(a, b) {
  const d = meters(a.lat, a.lng, b.lat, b.lng);
  if (a.no && a.no === b.no && d <= SAME_NO_M) return true;
  // 같은 지역에서 번호가 다르면 다른 정류장 (길 건너 맞은편 정류장은 이름이 같고 20~30m 떨어져 있음)
  if (a.city === b.city && a.no && b.no && a.no !== b.no) return false;
  return a.name === b.name && d <= SAME_SPOT_M;
}
const SAME_NAME_M = 60;
const nameKey = (n) => String(n ?? "").replace(/[\s.·,()[\]\-_]/g, "");
/**
 * 같은 자리 정류장 묶음 (예: 전주 시내버스 정류장과 그 자리에 서는 진안·완주 버스 정류장).
 * 폰 앱 StopMerge.groups · 정류장 짝 표(pipeline/build_stop_companions.js)와 같은 규칙:
 * 다른 시군 정류장이 30m 안이거나 이름이 같고 60m 안이면 후보, 가까운 짝부터 묶고 한 묶음에는 시군마다 하나씩만.
 */
export function spotGroups(stops) {
  const list = [], seen = new Set();
  for (const x of stops) if (x && x.lat != null && x.lng != null && !seen.has(x.id)) { seen.add(x.id); list.push(x); }
  const order = list.map((_, i) => i).sort((a, b) => list[a].lat - list[b].lat);
  const dLat = SAME_NAME_M / 111000, edges = [];
  for (let i = 0; i < order.length; i++) {
    const a = list[order[i]], ka = nameKey(a.name);
    for (let j = i + 1; j < order.length && list[order[j]].lat - a.lat <= dLat; j++) {
      const b = list[order[j]];
      if (a.city === b.city) continue;
      const d = meters(a.lat, a.lng, b.lat, b.lng);
      if (d <= (ka && ka === nameKey(b.name) ? SAME_NAME_M : SAME_SPOT_M)) edges.push([order[i], order[j], d]);
    }
  }
  edges.sort((x, y) => x[2] - y[2] || Math.min(x[0], x[1]) - Math.min(y[0], y[1]) || Math.max(x[0], x[1]) - Math.max(y[0], y[1]));
  const parent = list.map((_, i) => i), cities = list.map((x) => new Set([x.city]));
  const root = (x) => { while (parent[x] !== x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; };
  const join = (a, b) => {
    const ra = root(a), rb = root(b);
    if (ra === rb) return;
    const [keep, gone] = ra < rb ? [ra, rb] : [rb, ra];
    parent[gone] = keep;
    for (const c of cities[gone]) cities[keep].add(c);
  };
  // 1) 관리자가 손으로 묶은 것 먼저 (거리·시군 상관없이), 빈 목록은 따로 떼기 (지도 고치기 → 묶기, edits.js)
  // 따로 뗀 정류장이 낀 짝은 뺌: A 에서 B 를 묶은 뒤 B 를 "따로 떼기"하면 A 쪽 기록 때문에 계속 묶여 있었음 (앱 StopMerge.Manual 과 같게)
  const at = new Map(list.map((x, i) => [legacyId(x.id), i])), g = Object.entries(Edits.groupEdits()).filter(([, v]) => Array.isArray(v));
  const apart = new Set(g.filter(([, v]) => !v.length).map(([k]) => legacyId(k)));
  for (const [k, v] of g) {
    if (apart.has(legacyId(k))) continue;
    const a = at.get(legacyId(k));
    for (const o of v) { if (apart.has(legacyId(String(o)))) continue; const b = at.get(legacyId(String(o))); if (a != null && b != null) join(a, b); }
  }
  // 2) 자료에 미리 묶어 둔 점 (방면까지 본 묶음, pipeline/stop_spots.py — 폰 앱 StopMerge 와 같음). 있으면 거리 규칙은 안 씀
  const pre = list.some((x) => x.spot !== undefined);
  if (pre) {
    const first = new Map();
    list.forEach((x, i) => {
      if (!x.spot || apart.has(legacyId(x.id))) return;
      if (first.has(x.spot)) join(first.get(x.spot), i); else first.set(x.spot, i);
    });
  }
  // 3) (옛 자료) 가까운 짝부터. 합칠 두 묶음에 같은 시군이 있으면 안 합침
  for (const [a, b] of pre ? [] : edges) {
    if (apart.has(legacyId(list[a].id)) || apart.has(legacyId(list[b].id))) continue;
    const ra = root(a), rb = root(b);
    if (ra === rb || [...cities[ra]].some((c) => cities[rb].has(c))) continue;
    join(a, b);
  }
  const groups = new Map();
  list.forEach((x, i) => { const r = root(i); if (!groups.has(r)) groups.set(r, []); groups.get(r).push(x); });
  return [...groups.values()];
}
/** 그 지역 시내버스 정류장인지 (교통안전공단 TS… 아님. 지도에는 노선 있는 것과 전주·제주 정류장만 들어옴) */
const localStop = (x) => (x.city.startsWith("TS") ? 0 : 1);
/** 노선이 많은 것, 같으면 교통안전공단(TS…) 아닌 것 먼저 */
const byRoutes = (a, b) => b.routes.length - a.routes.length || (a.city.startsWith("TS") ? 1 : 0) - (b.city.startsWith("TS") ? 1 : 0);

/** 같은 이름·같은 자리 정류장 묶기 (노선이 가장 많은 것 하나만) */
function mergeSameSpot(stops) {
  const ordered = [...stops].sort(byRoutes);
  const out = [];
  for (const s of ordered) if (!out.some((o) => sameStop(o, s))) out.push(s);
  return out;
}

/** 이름에 검색어가 든 정류장 (이름이 검색어로 시작하는 것 먼저) */
export async function searchStops(query, limit = 60) {
  const q = query.trim();
  if (!q) return [];
  await Edits.ready();
  const rows = parseStops(await cached(`data/names/${q.codePointAt(0).toString(16)}.json`).catch(() => []));
  const key = q.replace(/ /g, "");
  return mergeSameSpot(rows.filter((s) => s.name.replace(/ /g, "").includes(key)))
    .sort((a, b) => (a.name.startsWith(q) ? 0 : 1) - (b.name.startsWith(q) ? 0 : 1) || a.name.length - b.name.length || (a.name < b.name ? -1 : 1))
    .slice(0, limit);
}

/** 이 범위 안의 정류장 (지도) */
export async function stopsIn(s, w, n, e, limit = 400) {
  const names = tileNames(s, w, n, e);
  if (names.length > 12) return [];
  const all = (await Promise.all(names.map(tile))).flat();
  // 노선 목록이 없는 정류장은 빼되, 노선을 서버에 물어 채우는 지역은 남김: 교통안전공단(TSB… — 도시 코드가 TS… 인 곳도,
  // 익산 35030·진안 35320 처럼 숫자인 곳도 있음), 제주(JEB), 전주(JUB)
  // 정리된 자료(pipeline/stop_spots.py, 칸 줄에 대표 id 칸이 있음)는 숨길 정류장이 이미 빠져 있어 그대로 (노선 자료가 덜 찬
  // 경기·교통안전공단 정류장, 경기 버스가 서는 서울 정류장도 보이게 — 명동입구B 는 M4108 이 서는데 노선 자료에 없어 안 보였음)
  const inView = all.filter((x) => x.lat >= s && x.lat <= n && x.lng >= w && x.lng <= e &&
    (x.spot !== undefined || x.routes.length || x.city.startsWith("TS") || x.id.startsWith("TSB") || x.id.startsWith("JEB") || x.id.startsWith("JUB")));
  // 같은 자리의 다른 시군 정류장은 점 하나로. 대표는 그 지역 시내버스 정류장(교통안전공단 TS… 아닌 것) — 폰 앱과 같게
  // 미리 묶은 자료면 그 대표(점 자리), 방면이 다른 길 건너 정류장은 이름이 같아도 따로 둠
  const isMain = (x) => (x.spot && x.spot === x.id ? 1 : 0);
  const mainOf = (g) => [...g].sort((x, y) => (isMain(y) - isMain(x)) || (localStop(y) - localStop(x)) || byRoutes(x, y))[0];
  const mains = spotGroups(inView).map(mainOf);
  return (inView.some((x) => x.spot !== undefined) ? mains : mergeSameSpot(mains)).slice(0, limit);
}

/** 자료 조각의 원래 좌표 (손으로 고친 것 적용 전). 못 찾으면 null */
export async function rawStopPos(id, lat, lng) {
  const legacy = legacyId(id);
  for (const name of new Set([tileName(lat, lng), ...tileNames(lat - 0.01, lng - 0.01, lat + 0.01, lng + 0.01)])) {
    const rows = await cached(`data/tiles/${name}.json`).catch(() => []);
    const a = Array.isArray(rows) ? rows.find((x) => Array.isArray(x) && (String(x[0]) === legacy || String(x[0]) === id)) : null;
    if (a && num(a[2]) != null) return [num(a[2]), num(a[3])];
  }
  return null;
}
export async function stopAt(id, lat, lng) {
  const legacy = legacyId(id);
  return (await tile(tileName(lat, lng))).find((s) => s.id === legacy || s.id === id) || null;
}

let citiesP = null;
function cities() {
  if (!citiesP) citiesP = cached("data/cities.json").then((r) => ({ names: r.names || {}, alias: r.alias || {} })).catch(() => ({ names: {}, alias: {} }));
  return citiesP;
}
export async function cityName(code) { return (await cities()).names[code] || null; }

// ── 노선 ──
function sumOf(id, a) {
  if (!Array.isArray(a) || a[0] == null) return null;
  return { id, no: String(a[0]), start: str(a[1]), end: str(a[2]), type: str(a[3]), first: str(a[4]), last: str(a[5]), interval: int(a[6]), intervalSat: int(a[7]), intervalSun: int(a[8]) };
}
/** 오늘(요일 1~7) 배차 간격 */
/** "07:40" → 운행일 기준 분 (03시 전은 전날 운행의 연장, 앱 TagoTime.serviceMinute 와 같음). 못 읽으면 null */
function serviceMin(t) {
  const d = String(t || "").replace(/\D/g, "").padStart(4, "0");
  if (d.length !== 4) return null;
  const h = +d.slice(0, 2), m = +d.slice(2);
  if (m >= 60 || h >= 30) return null;
  const v = h * 60 + m;
  return v < 180 ? v + 1440 : v;
}
/** 시간표상 운행 시간(첫차~막차) 안인지 (앱 TagoTime.inService 와 같음). [nowMin]: 자정 기준 지금 분 (한국 시각) */
export function inService(first, last, nowMin) {
  const f = serviceMin(first), l = serviceMin(last);
  if (f == null || l == null || nowMin == null) return false;
  const n = nowMin < 180 ? nowMin + 1440 : nowMin;
  return f <= n && n <= l;
}
/** 지금 한국 시각 (자정 기준 분) */
export const kstMinute = (ms = Date.now()) => { const d = new Date(ms + 9 * 3600e3); return d.getUTCHours() * 60 + d.getUTCMinutes(); };
export function intervalOn(s, day) { return day === 6 ? s.intervalSat ?? s.interval : day === 7 ? s.intervalSun ?? s.intervalSat ?? s.interval : s.interval; }

export async function routeSums(ids) {
  const out = {};
  await Promise.all([...new Set(ids.map((id) => bucket(id)))].map(async (b) => {
    let file = {};
    try { file = await cached(`data/routesum/${b}.json`); } catch (e) { /* 없음 */ }
    for (const id of ids) if (bucket(id) === b && file[id]) { const s = sumOf(id, file[id]); if (s) out[id] = s; }
  }));
  return out;
}

/**
 * 정류장 검색 결과마다 서는 노선 번호 몇 개 ("버스 140, 402, 3412 외 5" 또는 null).
 * "강남역"처럼 비슷한 이름이 10곳 넘게 나오면 어느 정류장인지 고르기 어려웠음 (2026-11-22 점검).
 * 노선 요약 파일(data/routesum, 조각마다 1KB 안팎)에서 번호만 찾음. 정류장마다 노선 12개까지 (경기 노선은 요약 파일에 없어 빠질 수 있음)
 */
export async function routeNoLabels(stops, show = 4) {
  const sums = await routeSums(stops.flatMap((s) => s.routes.slice(0, 12)));
  return stops.map((s) => {
    const nos = [...new Set(s.routes.slice(0, 12).map((id) => sums[id]?.no).filter(Boolean))].sort(naturalCompare);
    if (!nos.length) return null;
    const shown = nos.slice(0, show), more = Math.max(0, new Set(s.routes).size - shown.length);
    return "버스 " + shown.join(", ") + (more ? ` 외 ${more}` : "");
  });
}

/** 노선 전체 (정류장 순서·좌표·시간표). 파일의 예전 id 로 찾고 지금 id 로 돌려줌.
 * 파일에 없는 노선(전주·제주·교통안전공단 등)은 [city](TAGO 도시코드)로 TAGO 노선 정류장 목록을 물음 */
export async function route(id, city) {
  const [r] = await Promise.all([routeRaw(id, city), Edits.ready()]);
  // 지도에서 손으로 고친 정류장 위치 (edits.js)
  if (r) for (const s of r.stops || []) { const f = s.id && (Edits.stopFix(s.id) || Edits.stopFix(legacyId(s.id))); if (f) { s.lat = f[0]; s.lng = f[1]; } }
  return r;
}
async function routeRaw(id, city) {
  const legacy = legacyId(id);
  const m = (await cached(`data/routes/${bucket(legacy)}.json`).catch(() => ({})))[legacy];
  if (!m || m.no == null) return ggId(id) || (city && /^\d+$/.test(city)) ? savedRoute(id, city) : null;
  const info = Array.isArray(m.info) ? m.info : null;
  const sum = info ? { id: legacy, no: String(m.no), start: str(m.start), end: str(m.end), type: str(m.rtype), first: str(info[0]), last: str(info[1]), interval: int(info[2]), intervalSat: int(info[3]), intervalSun: int(info[4]) } : null;
  const stops = (m.stops || []).map((a) => ({ seq: int(a[0]) || 0, id: str(a[1]), name: String(a[2] ?? ""), lat: num(a[3]), lng: num(a[4]), upDown: str(a[5]) })).sort((a, b) => a.seq - b.seq);
  return { id: currentId(legacy), no: String(m.no), start: str(m.start), end: str(m.end), city: String(m.city ?? ""), region: str(m.region), type: str(m.rtype), sum, stops };
}

/**
 * 자료 조각에 없는 노선(경기·TAGO 지역)은 서버에 물어야 해서 느림 → 이 브라우저에 저장해 두고 다음부터 바로.
 * 저장한 것이 있으면 그것을 주고 뒤에서 새로 받아 저장 (다음에 열 때 반영). 하루 지나면 기다려서 새로 받음
 */
// 저장해 둔 노선 정류장 목록은 30일까지 바로 보여 줌 (서버 쪽은 열 때마다 뒤에서 다시 받아 저장). 예전 하루: 다음 날 열면 다시 기다렸음
const ROUTE_KEEP_MS = 30 * 24 * 3600 * 1000;
async function savedRoute(id, city) {
  const key = "route:" + legacyId(id) + ":" + (city || "");
  const net = () => (ggId(id) ? ggRoute(id) : tagoRoute(id, city))
    // 전주·제주 노선은 TAGO 가 실패하면(하루 한도 초과 등) 전주시·제주 버스정보로 (앱과 같은 자료)
    .catch((e) => { if (/^(JUB|JEB)\d{9}$/.test(legacyId(id))) return null; throw e; })
    .then((r) => {
      if (r && r.stops && r.stops.length) return r;
      const l = legacyId(id);
      return /^JUB\d{9}$/.test(l) ? jeonjuRoute(l) : /^JEB\d{9}$/.test(l) ? jejuRoute(l) : r;
    })
    .then((r) => {
      if (r && r.stops && r.stops.length) cachePut(key, { at: Date.now(), r });
      return r;
    });
  const saved = await cacheGet(key);
  if (!saved || !saved.r || Date.now() - saved.at > ROUTE_KEEP_MS) return net();
  net().catch(() => {});
  // 정류장 고친 위치는 route() 가 덧씌우므로 저장본은 그대로 복사해서 줌
  return JSON.parse(JSON.stringify(saved.r));
}

/**
 * 전주 노선 정류장 차례를 전주시 버스정보(노선별 버스 위치 응답 = 노선 전체 정류장, 앱과 같은 자료)로.
 * 좌표는 이 응답에 없어 정류장 이름 자료(data/names, 이름 첫 글자별)에서 정류장 id 로 찾음
 */
async function jeonjuRoute(id) {
  const x = await getText("w/bus/jeonju/stops/" + id.slice(3));
  const rows = [...new DOMParser().parseFromString(x, "text/xml").getElementsByTagName("list")].map((el) => {
    const r = {};
    for (const c of el.children) r[c.tagName.toLowerCase()] = c.textContent.trim();
    return r;
  });
  const seen = new Set();
  const raw = rows.map((r) => ({ seq: int(r.brsseqno ?? r.brnseqno) || 0, id: r.stopstandardid ? "JUB" + r.stopstandardid : null, name: r.stopkname || "", no: str(r.stopid) }))
    .filter((s) => s.id && !seen.has(s.seq) && seen.add(s.seq))
    .sort((a, b) => a.seq - b.seq);
  if (raw.length < 2) return null;
  return withCoords(id, "35010", "노선", raw);
}

/** 제주 노선 정류장 차례를 제주 버스정보(bus.jeju.go.kr, 앱과 같은 자료)로. 좌표는 정류장 이름 자료에서 */
async function jejuRoute(id) {
  const rows = JSON.parse(await getText("w/bus/jeju/stops/" + id.slice(3)));
  const seen = new Set();
  const raw = (Array.isArray(rows) ? rows : []).map((r) => ({ seq: int(r.STATION_ORD) || 0, id: r.STATION_ID != null ? "JEB" + r.STATION_ID : null, name: String(r.STATION_NM || ""), no: null }))
    .filter((s) => s.id && !seen.has(s.seq) && seen.add(s.seq))
    .sort((a, b) => a.seq - b.seq);
  if (raw.length < 2) return null;
  const no = rows[0] && rows[0].ROUTE_NUM != null ? String(rows[0].ROUTE_NUM) : "노선";
  return withCoords(id, "39", no, raw);
}

/** 정류장 차례 [{seq, id, name, no}] 에 좌표를 정류장 이름 자료(data/names, 이름 첫 글자별)에서 정류장 id 로 찾아 붙여 노선으로 */
async function withCoords(id, city, no, raw) {
  const files = new Map();
  for (const s of raw) {
    const ch = s.name.codePointAt(0)?.toString(16);
    if (ch && !files.has(ch)) files.set(ch, cached(`data/names/${ch}.json`).then(parseStops).catch(() => []));
  }
  const at = new Map();
  for (const f of files.values()) for (const s of await f) at.set(legacyId(s.id), s);
  const stops = raw.map((s) => {
    const k = at.get(s.id);
    return { seq: s.seq, id: s.id, name: s.name, lat: k ? k.lat : null, lng: k ? k.lng : null, upDown: null, no: s.no || (k && k.no) || null };
  });
  if (stops.filter((s) => s.lat != null).length < 2) return null;
  return { id, no, start: stops[0].name, end: stops[stops.length - 1].name, city, region: null, type: null, sum: null, stops };
}

/** TAGO 노선 정류장 목록·기본 정보로 노선 만들기 (서버 웹 통로, 하루 저장) */
async function tagoRoute(id, city) {
  const q = { cityCode: city, routeId: currentId(id) };
  const [stopsXml, info] = await Promise.all([
    getText("w/dg/1613000/BusRouteInfoInqireService/getRouteAcctoThrghSttnList", q),
    getText("w/dg/1613000/BusRouteInfoInqireService/getRouteInfoIem", q).then(xmlRows).then((r) => r[0] || {}).catch(() => ({})),
  ]);
  const stops = xmlRows(stopsXml).map((r) => ({ seq: int(r.nodeord) || 0, id: str(r.nodeid), name: r.nodenm || "", lat: num(r.gpslati), lng: num(r.gpslong), upDown: str(r.updowncd), no: str(r.nodeno) }))
    .sort((a, b) => a.seq - b.seq);
  if (!stops.length) return null;
  const clockOf = (t) => (t && /^\d{3,4}$/.test(t) ? t.padStart(4, "0").replace(/(\d\d)(\d\d)/, "$1:$2") : null);
  const no = info.routeno ? String(info.routeno) : "노선";
  const sum = { id, no, start: str(info.startnodenm), end: str(info.endnodenm), type: str(info.routetp), first: clockOf(info.startvehicletime), last: clockOf(info.endvehicletime),
    interval: int(info.intervaltime), intervalSat: int(info.intervalsattime), intervalSun: int(info.intervalsuntime) };
  return { id: currentId(id), no, start: sum.start, end: sum.end, city, region: null, type: sum.type, sum, stops };
}

/** 경기 노선 (경기 GBIS 정류장 목록, 서버 하루 저장): 좌표·순번, 회차점까지 상행 */
async function ggRoute(id) {
  const body = JSON.parse(await getText("w/bus/gg/stops/" + ggId(id)));
  // 정류장이 없다는 답(resultCode 4 "결과가 존재하지 않습니다")만 '없음'(null). 한도·오류 답은 실패로
  const code = body?.response?.msgHeader?.resultCode;
  if (code != null && +code !== 0 && +code !== 4) throw new Error("gbis " + code);
  const rows = findRows(body, "stationId")
    .map((r) => ({ seq: int(r.stationSeq) || 0, id: r.stationId != null ? "GGB" + r.stationId : null, name: String(r.stationName ?? ""), lat: num(r.y), lng: num(r.x), turn: String(r.turnYn || "").toUpperCase() === "Y", no: str(r.mobileNo) }))
    .sort((a, b) => a.seq - b.seq);
  if (!rows.length) return null;
  const t = rows.findIndex((r) => r.turn);
  const stops = rows.map((r, i) => ({ ...r, upDown: t < 0 ? null : i <= t ? "0" : "1" }));
  const start = stops[0].name, end = t >= 0 ? stops[t].name : stops[stops.length - 1].name;
  return { id, no: "노선", start, end, city: "31", region: "경기", type: null, sum: null, stops };
}

// 정류장 노선 목록은 이 브라우저에 하루 저장해 두고 바로 보여 줌 (서버 확인은 뒤에서, 달라졌으면 onNewer 로 알림).
// 전주시 정류장처럼 자료에 노선 목록이 없는 곳은 서버에 물어야 해서 처음엔 몇 초 걸림
// 정류장을 지나는 노선도 30일까지 바로 (뒤에서 다시 받아 달라졌으면 onNewer)
const STOP_ROUTES_KEEP_MS = 30 * 24 * 3600 * 1000;
const stopRoutesMemo = new Map();
// 누가 지도를 고치면(정류장 묶기·떼기·옮기기) 묶음이 달라지므로 다시 물음
Edits.onChange(() => stopRoutesMemo.clear());
/** 정류장에 서는 노선 요약 (같은 자리 정류장 것까지). 저장해 둔 것이 있으면 바로 */
export async function stopRoutes(stop, { onNewer = null } = {}) {
  // 저장 열쇠에 지도 고치기 판 번호: 정류장을 합치거나 떼면 예전 목록을 쓰지 않음
  await Edits.ready();
  const key = "stoproutes:" + legacyId(stop.id) + ":" + Edits.version();
  // 같은 화면에서 여러 곳이 물어도(지도 카드 방면·정류장 화면) 한 번만
  let m = stopRoutesMemo.get(key);
  if (!m || Date.now() - m.at > 60000) {
    m = { at: Date.now(), p: stopRoutesNet(stop) };
    stopRoutesMemo.set(key, m);
    m.p.then((v) => cachePut(key, { at: Date.now(), v }), () => stopRoutesMemo.delete(key));
  }
  const saved = await cacheGet(key);
  if (saved && Array.isArray(saved.v) && saved.v.length && Date.now() - saved.at < STOP_ROUTES_KEEP_MS) {
    const sig = (l) => l.map((r) => r.id).sort().join(",");
    m.p.then((v) => { if (onNewer && sig(v) !== sig(saved.v)) onNewer(v); }).catch(() => {});
    return saved.v;
  }
  return m.p;
}
async function stopRoutesNet(stop) {
  const same = await sameSpot(stop);
  const ids = [...new Set([stop, ...same].flatMap((s) => s.routes))];
  const sums = await routeSums(ids);
  // 경기 노선 요약(번호·기점·종점만, 2026-11-23부터)은 서버 실시간 목록(종류·방면, 지금 다니는 노선)이 안 될 때만 씀
  const ggSums = Object.values(sums).filter((s) => ggId(s.id));
  const seen = new Set();
  const out = Object.values(sums).filter((s) => !ggId(s.id)).map((s) => ({ ...s, id: currentId(s.id) })).filter((s) => !seen.has(s.id) && seen.add(s.id));
  // 같은 번호라도 본선·지선·분선처럼 기점·종점이 다르면 다른 노선 → 번호가 아니라 노선 ID(없으면 번호+기점+종점)로 구분
  const sig = (s) => [s.no, (s.start || "").replace(/ /g, ""), (s.end || "").replace(/ /g, "")].join("|");
  const seenSig = new Set(out.map(sig));
  // 자료 조각에 요약이 없는 노선이 있거나, 같은 자리 정류장 중 노선 목록이 비어 있는 곳(전주시 정류장 등)이 있으면
  // 정류장을 지나는 노선을 서버로 물어 채움 (운행 안 하는 노선도 보이게).
  // 노선 목록이 빈 정류장을 묻지 않으면 그 지역 노선은 실시간 도착이 와야 뒤늦게 따로 나타남
  if (!ids.length || ids.some((id) => !sums[id] || ggId(id)) || [stop, ...same].some((s) => !(s.routes || []).length)) {
    const live = await liveStopRoutes(stop, same).catch(() => []);
    for (const s of live) {
      // 자료 파일 노선과 겹치는지만 번호+기점+종점으로 봄 (받아 온 노선끼리는 ID 가 다르면 경유지만 다른 다른 노선)
      if (seen.has(s.id) || seenSig.has(sig(s))) continue;
      seen.add(s.id); out.push(s);
    }
    // 경기 실시간 목록을 못 받았으면 자료 파일 요약으로라도 (예전엔 경기 노선이 통째로 안 보였음)
    if (!live.some((s) => ggId(s.id))) for (const s of ggSums) if (!seen.has(s.id)) { seen.add(s.id); out.push({ ...s, city: "31" }); }
  }
  return out;
}

/** 정류장을 지나는 노선 (경기 GBIS, TAGO). 시간표는 없고 번호·종류·방향만 */
async function liveStopRoutes(stop, same) {
  const jobs = [];
  for (const g of new Set([stop, ...same].map((s) => ggId(s.id)).filter(Boolean))) {
    jobs.push(getText("w/bus/gg/routes/" + g).then((b) => findRows(JSON.parse(b), "routeId").map((r) => ({
      id: "GGB" + r.routeId, no: String(r.routeName ?? ""), start: null, end: str(r.routeDestName) ? str(r.routeDestName) + " 방면" : null,
      type: str(r.routeTypeName) || GG_TYPES[int(r.routeTypeCd)] || null, city: "31",
    }))));
  }
  // 전주: 전주시 버스정보 (JUB + 9자리, 줄 이름 <list>)
  for (const j of new Set([stop, ...same].map((s) => (/^JUB\d{9}$/.test(s.id) ? s.id.slice(3) : null)).filter(Boolean))) {
    jobs.push(getText("w/bus/jeonju/routes/" + j).then((x) => [...new DOMParser().parseFromString(x, "text/xml").getElementsByTagName("list")].map((el) => {
      const r = {};
      for (const c of el.children) r[c.tagName.toLowerCase()] = c.textContent.trim();
      const no = jeonjuNo(r.brtid, r.brtclass);
      return { id: "JUB" + (r.brtstdid || ""), no, start: str(r.brtsname), end: str(r.brtename), type: jeonjuType(r.brtclass, no, r.brtsname, r.brtename), city: "35010" };
    })));
  }
  for (const [city, node] of (await targets(stop)).tago) {
    jobs.push(getText("w/dg/1613000/BusSttnInfoInqireService/getSttnThrghRouteList", { cityCode: city, nodeid: currentId(node) }).then((x) => xmlRows(x).map((r) => ({
      id: String(r.routeid || ""), no: String(r.routeno ?? ""), start: str(r.startnodenm), end: str(r.endnodenm), type: str(r.routetp), city,
    }))));
  }
  const res = await Promise.allSettled(jobs);
  return res.flatMap((r) => (r.status === "fulfilled" ? r.value : [])).filter((s) => s.id && s.no);
}

/** 노선 도로 모양 (서버가 만들어 저장). 못 받으면 null. [city] 는 자료 조각에 없는 노선(전주 등)을 TAGO 로 만들 때 */
/**
 * 노선 모양. 이 브라우저에 저장해 둔 것이 있으면 바로 주고, 서버 쪽은 뒤에서 다시 받아
 * 달라졌으면 저장하고 onNewer(새 모양) 로 알려 줌 (폰 앱처럼 열자마자 그림).
 */
export async function shape(routeId, city, stops, no, { noEdit = false, onNewer = null } = {}) {
  const key = "shape:" + legacyId(routeId) + (noEdit ? ":auto" : "");
  const saved = await cacheGet(key);
  const fresh = shapeNet(routeId, city, stops, no, { noEdit }).then((pts) => {
    if (pts && pts.length >= 2) cachePut(key, Edits.encodePolyline(pts));
    return pts;
  });
  const old = typeof saved === "string" ? decodePolyline(saved, 5) : [];
  if (old.length < 2) return fresh;
  fresh.then((pts) => {
    if (onNewer && pts && pts.length >= 2 && Edits.encodePolyline(pts) !== saved) onNewer(pts);
  }).catch(() => {});
  return old;
}
async function shapeNet(routeId, city, stops, no, { noEdit = false } = {}) {
  // 지도에서 손으로 고친 노선 모양이 있으면 그것 (edits.js)
  if (!noEdit) {
    await Edits.ready();
    const fixed = Edits.busFix(routeId) || Edits.busFix(legacyId(routeId));
    if (fixed) { const pts = decodePolyline(String(fixed), 5); if (pts.length >= 2) return pts; }
  }
  // 앱(폰)이 만들어 서버에 올린 모양이 있으면 그것 → 앱과 웹의 노선 선이 같음 (서버 src/appshape.js)
  try {
    const got = await getJson("w/shape/" + legacyId(routeId), { only: "app" });
    const pts = got && got.poly ? decodePolyline(String(got.poly), 5) : [];
    if (pts.length >= 2) return pts;
  } catch (e) { /* 아직 앱 모양 없음 */ }
  // 앱 안에 든 실제 경로 자료(서울 TBIS·경기 GBIS 경로이력, 자료 조각 shapes/).
  // 정류장과 맞을 때만: 앱과 같은 기준 (정류장 90% 가 선에서 100m 안, 앱 Shapes.fitsStops)
  try {
    const legacy = legacyId(routeId);
    const poly = (await cached(`data/shapes/${bucket(legacy)}.json`).catch(() => ({})))[legacy];
    const pts = poly ? decodePolyline(String(poly), 5) : [];
    if (pts.length >= 2 && (!stops || fitsStops(pts, stops, 100, 0.9))) return pts;
  } catch (e) { /* 서버에서 */ }
  try {
    // v=11(예전에 직선으로 저장된 모양을 다시 만든 것, 브라우저가 하루 저장한 예전 응답을 쓰지 않게. v=10: 회차에서 끊어 다시 만든 모양): 노선 번호를 주면 서버가 카카오 경로(앱과 같은 방법)로. 골목으로 돌아 들어가지 않게 다시 만든 모양 (브라우저에 저장된 예전 모양을 쓰지 않게)
    const poly = (await getJson("w/shape/" + legacyId(routeId), /^\d+$/.test(city || "") ? { city, v: 11, no: no || undefined } : { v: 11, no: no || undefined })).poly;
    const pts = decodePolyline(String(poly), 5);
    return pts.length >= 2 ? pts : null;
  } catch (e) { return null; }
}
/** 정류장의 90% 이상이 선에서 maxM 안이면 true */
export function fitsStops(line, stops, maxM = 30, ratio = 0.9) {
  const pts = stops.filter((s) => s.lat != null && s.lng != null);
  if (!pts.length) return true;
  let ok = 0;
  for (const s of pts) {
    const kx = 111320 * Math.cos((s.lat * Math.PI) / 180), ky = 110540;
    let best = Infinity;
    for (let i = 0; i < line.length - 1 && best > maxM; i++) {
      const ax = (line[i][1] - s.lng) * kx, ay = (line[i][0] - s.lat) * ky, bx = (line[i + 1][1] - s.lng) * kx, by = (line[i + 1][0] - s.lat) * ky;
      const dx = bx - ax, dy = by - ay, l = dx * dx + dy * dy, t = l ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l)) : 0;
      best = Math.min(best, Math.hypot(ax + t * dx, ay + t * dy));
    }
    if (best <= maxM) ok++;
  }
  return ok / pts.length >= ratio;
}
export function decodePolyline(s, precision) {
  const f = 10 ** precision, out = [];
  let i = 0, lat = 0, lng = 0;
  const next = () => {
    let shift = 0, result = 0, b;
    do { b = s.charCodeAt(i++) - 63; result |= (b & 0x1f) << shift; shift += 5; } while (b >= 0x20 && i < s.length);
    return result & 1 ? ~(result >> 1) : result >> 1;
  };
  while (i < s.length) { lat += next(); if (i >= s.length) break; lng += next(); out.push([lat / f, lng / f]); }
  return out;
}

// ── 서울·경기 (TAGO 에 없음) ──
const ggCity = (c) => c.startsWith("31") || c === "11" || c === "23";
const ggId = (id) => { const r = id.slice(3); return id.startsWith("GGB") && /^\d{9}$/.test(r) ? r : null; };
const seoulId = (id) => { const r = id.slice(3); return id.startsWith("SEB") && /^\d{1,12}$/.test(r) ? r : null; };
const ars = (no) => (no && /^\d{1,5}$/.test(no.trim()) ? no.trim().padStart(5, "0") : null);
const metroId = (id, city) => id.startsWith("SEB") || (id.startsWith("GGB") && ggCity(city));
const tagoStop = (s) => s.city && !metroId(s.id, s.city);

const SEOUL_TYPES = { 1: "공항", 2: "마을", 3: "간선", 4: "지선", 5: "순환", 6: "광역", 7: "인천", 8: "경기", 10: "관광", 13: "동행", 14: "한강", 15: "심야", 0: "공용" };
const GG_TYPES = { 11: "직행좌석형시내버스", 12: "좌석형시내버스", 13: "일반형시내버스", 14: "광역급행형시내버스", 15: "따복형시내버스", 16: "경기순환버스", 21: "직행좌석형농어촌버스", 22: "좌석형농어촌버스", 23: "일반형농어촌버스", 30: "마을버스", 41: "고속형시외버스", 42: "좌석형시외버스", 43: "일반형시외버스", 51: "리무진공항버스", 52: "좌석형공항버스", 53: "일반형공항버스" };
const GG_VEHICLE = { 1: "저상버스", 2: "2층버스", 5: "전세버스", 6: "예약버스", 7: "트롤리" };

/** "8분2초후[4번째 전]", "곧 도착" → [초, 정류장 수] (운행종료 등은 null) */
export function parseArrMsg(m) {
  m = (m || "").trim();
  if (!m || /운행종료|출발대기|회차대기/.test(m)) return null;
  const stops = int(/\[(\d+)번째/.exec(m)?.[1]);
  if (m.includes("곧 도착")) return [30, stops ?? 0];
  const min = int(/(\d+)분/.exec(m)?.[1]), sec = int(/(\d+)초/.exec(m)?.[1]);
  if (min == null && sec == null) return null;
  return [(min || 0) * 60 + (sec || 0), stops];
}

// ── 차 안 혼잡·빈자리 (Crowd, ArrivalText.kt 와 같은 규칙). 없으면 null ──
/** 서울: congestion 0 정보없음 3 여유 4 보통 5 혼잡 6 매우혼잡, isFullFlag 1 만차 */
export function crowdSeoul(c, full) {
  if (String(full ?? "").trim() === "1") return "만차";
  return { 3: "여유", 4: "보통", 5: "혼잡", 6: "매우혼잡" }[String(c ?? "").trim()] || null;
}
/** 경기: 빈자리(-1 정보없음, 좌석 없는 시내버스 0)가 1 이상이면 그것만, 아니면 crowded 1 여유 2 보통 3 혼잡 4 매우혼잡 */
export function crowdGyeonggi(crowded, seats) {
  if (seats != null && seats > 0) return "빈자리 " + seats;
  return { 1: "여유", 2: "보통", 3: "혼잡", 4: "매우혼잡" }[crowded] || null;
}
function seoulArrivals(body) {
  return findRows(JSON.parse(body), "arrmsg1").flatMap((r) => [1, 2].map((n) => {
    const p = parseArrMsg(r["arrmsg" + n]);
    if (!p) return null;
    return { routeId: "SEB" + r.busRouteId, routeNo: str(r.busRouteAbrv) || String(r.rtNm ?? ""), routeType: SEOUL_TYPES[r.routeType] || null,
      vehicleType: r["busType" + n] === "1" ? "저상버스" : r["busType" + n] === "2" ? "굴절버스" : null, prevStops: p[1], arrSeconds: p[0], towards: str(r.adirection), crowd: crowdSeoul(r["congestion" + n], r["isFullFlag" + n]) };
  }).filter(Boolean));
}
function ggArrivals(body) {
  return findRows(JSON.parse(body), "routeId").flatMap((r) => {
    if (String(r.flag || "").toUpperCase() === "STOP") return [];
    return [1, 2].map((n) => {
      const sec = int(r["predictTimeSec" + n]) ?? (int(r["predictTime" + n]) != null ? int(r["predictTime" + n]) * 60 : null);
      if (sec == null) return null;
      return { routeId: "GGB" + r.routeId, routeNo: String(r.routeName ?? ""), routeType: str(r.routeTypeName) || GG_TYPES[int(r.routeTypeCd)] || null,
        vehicleType: GG_VEHICLE[int(r["lowPlate" + n])] || null, prevStops: int(r["locationNo" + n]), arrSeconds: sec, towards: str(r.routeDestName),
        crowd: crowdGyeonggi(int(r["crowded" + n]), int(r["remainSeatCnt" + n])) };
    }).filter(Boolean);
  });
}

// ── 도착 ──
/** [near] 안에서 [stop] 과 한 점으로 묶이는 정류장 (stop 은 빼고) */
function matesIn(stop, near) {
  const g = spotGroups([stop, ...near.filter((o) => o.id !== stop.id)]).find((l) => l.some((o) => o.id === stop.id)) || [];
  return g.filter((o) => o.id !== stop.id);
}
/** 지도에서 이 정류장과 한 점으로 묶인 정류장 (지도 고치기 창에 보여 줌) */
export async function spotMates(stop) {
  const d = 0.005;
  const near = (await Promise.all(tileNames(stop.lat - d, stop.lng - d, stop.lat + d, stop.lng + d).map(tile))).flat();
  return matesIn(stop, near);
}

/** 같은 자리의 다른 시군 정류장 (시군마다 가장 가까운 하나) */
export async function sameSpot(stop) {
  const d = 0.005;
  const near = (await Promise.all(tileNames(stop.lat - d, stop.lng - d, stop.lat + d, stop.lng + d).map(tile))).flat();
  const groups = new Map();
  // 경기 밖에 있는 경기 버스 정류장(인천·서울·청주 등의 GGB): 그 지역 시내버스 정류장이 조금 떨어져 따로 있어 가까운 것 하나씩 함께 봄
  const borrowed = stop.id.startsWith("GGB") && !stop.city.startsWith("31");
  // 지도에서 점 하나로 묶인 같은 자리 정류장 (spotGroups)
  const mine = new Set(matesIn(stop, near).map((o) => o.id));
  for (const o of near) {
    if (o.id === stop.id) continue;
    const m = meters(stop.lat, stop.lng, o.lat, o.lng);
    if (borrowed && !o.id.startsWith("GGB") && o.routes.length && m <= BORROW_M) {
      const k = "near|" + o.id.slice(0, 3), best = groups.get(k);
      if (!best || meters(stop.lat, stop.lng, best.lat, best.lng) > m) groups.set(k, o);
      continue;
    }
    if (!((stop.no && o.no === stop.no && m <= SAME_NO_M) || mine.has(o.id))) continue;
    const k = o.city + "|" + (o.no || o.id);
    const best = groups.get(k);
    if (!best || meters(stop.lat, stop.lng, best.lat, best.lng) > m) groups.set(k, o);
  }
  const out = [...groups.values()];
  // 노선 자료가 없는 TAGO 지역 정류장(교통안전공단 TSB 등): TAGO 는 정류장 파일에 없는 같은 자리 정류장(익산 ISB… 등)으로만 노선을 줌
  if (!stop.routes.length && tagoStop(stop) && !/^(JUB|JEB)/.test(stop.id)) {
    for (const t of await tagoTwins(stop)) if (!out.some((o) => o.id === t.id)) out.push(t);
  }
  return out;
}

/** 이 정류장 자리 150m 안의 같은 이름 TAGO 정류장 (가까운 3곳). 정류장 좌표로만 물음 */
const twinMemo = new Map();
function tagoTwins(stop) {
  if (!twinMemo.has(stop.id)) {
    twinMemo.set(stop.id, (async () => {
      const rows = xmlRows(await getText("w/dg/1613000/BusSttnInfoInqireService/getCrdntPrxmtSttnList", { gpsLati: stop.lat.toFixed(6), gpsLong: stop.lng.toFixed(6) }));
      const key = stop.name.replace(/ /g, "");
      return rows.map((r) => ({ id: str(r.nodeid), name: r.nodenm || "", lat: num(r.gpslati), lng: num(r.gpslong), no: str(r.nodeno), city: String(r.citycode || ""), routes: [] }))
        // 같은 이름은 150m 안, 이름이 달라도 30m 안이면 같은 자리 (예: 익산역 TSB ↔ 2m 옆 "익산역남부" ISB)
        .filter((o) => o.id && o.id !== stop.id && /^\d+$/.test(o.city) && o.lat != null && o.lng != null &&
          meters(stop.lat, stop.lng, o.lat, o.lng) <= (o.name.replace(/ /g, "") === key ? 150 : SAME_SPOT_M))
        .sort((a, b) => meters(stop.lat, stop.lng, a.lat, a.lng) - meters(stop.lat, stop.lng, b.lat, b.lng))
        .slice(0, 3);
    })().catch(() => []));
  }
  return twinMemo.get(stop.id);
}

/** TAGO 에 물을 도시코드 후보 (교통안전공단 TS… 시군은 대응표 → 근처 시군) */
async function cityCandidates(stop) {
  if (!stop.city.startsWith("TS")) return [stop.city];
  const out = new Set();
  const c = await cities();
  if (c.alias[stop.city]) out.add(c.alias[stop.city]);
  const near = (await Promise.all(tileNames(stop.lat - 0.07, stop.lng - 0.07, stop.lat + 0.07, stop.lng + 0.07).map(tile))).flat()
    .filter((x) => x.id.startsWith("TSB") && /^\d+$/.test(x.city));
  const best = new Map();
  for (const x of near) { const d = (x.lat - stop.lat) ** 2 + (x.lng - stop.lng) ** 2; if (!best.has(x.city) || best.get(x.city) > d) best.set(x.city, d); }
  [...best.entries()].sort((a, b) => a[1] - b[1]).forEach(([k]) => out.add(k));
  return [...out].slice(0, 3);
}

async function targets(stop) {
  const out = [], seen = new Set();
  const same = await sameSpot(stop);
  for (const s of [...same.filter(tagoStop), ...(tagoStop(stop) ? [stop] : [])]) {
    for (const c of await cityCandidates(s)) { const k = c + "|" + s.id; if (!seen.has(k)) { seen.add(k); out.push([c, s.id]); } }
  }
  const metro = new Set();
  for (const s of [stop, ...same]) {
    if (s.id.startsWith("SEB") && ars(s.no)) metro.add("w/bus/seoul/arrival/" + ars(s.no));
  }
  // 같은 자리의 모든 정류장 (예전엔 TAGO 5곳·서울 3곳까지만)
  return { tago: out, metro: [...metro] };
}

async function tagoArrivals(city, node) {
  const rows = xmlRows(await getText("w/dg/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", { cityCode: city, nodeId: currentId(node) }));
  return rows.map((r) => ({ routeId: r.routeid || "", routeNo: r.routeno || "", routeType: str(r.routetp), vehicleType: str(r.vehicletp), prevStops: int(r.arrprevstationcnt), arrSeconds: int(r.arrtime), city }));
}

/** 정류장 실시간 도착: {state: "ok"|"unsupported"|"failed", list, at} */
/** 전주 노선 종류 (앱 JeonjuMappers.routeType): 간선(4자리 번호, 6001·6002 처럼 기점=종점이어도) / 순환(기점과 종점이 같은 노선, 예: 2번) / 일반.
 *  cls(brtClass)는 종류가 아니라 가지 번호(3-1 의 1)라 쓰지 않음 */
/** 화면에 보일 전주 노선 번호 (앱 JeonjuMappers.routeNo): brtid 에 가지 번호를 붙임 (3 + brtclass 1 → 3-1, 0·빈 값은 그대로) */
export function jeonjuNo(no, cls) {
  const n = String(no ?? "").trim(), c = String(cls ?? "").trim();
  return !n || !c || c === "0" || n.includes("-") ? n : `${n}-${c}`;
}
export function jeonjuType(cls, no, start, end) {
  if (/^\d{4}/.test(String(no || "").trim())) return "간선";
  const s = String(start || "").trim();
  return s && s === String(end || "").trim() ? "순환" : "일반";
}
/** 전주시 버스정보 정류장 도착 (줄 이름 <list>, RTime 초, RStop 남은 정류장) */
async function jeonjuArrivals(id) {
  const x = await getText("w/bus/jeonju/arrival/" + id.slice(3));
  return [...new DOMParser().parseFromString(x, "text/xml").getElementsByTagName("list")].map((el) => {
    const r = {};
    for (const c of el.children) r[c.tagName.toLowerCase()] = c.textContent.trim();
    const no = jeonjuNo(r.brtid, r.brtclass);
    return { routeId: "JUB" + (r.brtstdid || ""), routeNo: no, routeType: jeonjuType(r.brtclass, no, ...(r.brtvianame || "").split("->")),
      vehicleType: r.buslow === "1" ? "저상버스" : null, prevStops: int(r.rstop), arrSeconds: int(r.rtime), city: "35010",
      towards: str((r.brtvianame || "").split("->")[1]) };
  }).filter((a) => a.routeNo && a.arrSeconds != null);
}

/** 제주 버스정보 정류장 도착 (bus.jeju.go.kr, 앱과 같은 자료). predict_trav_tm 분, remain_station 남은 정류장 */
async function jejuArrivals(id) {
  const rows = JSON.parse(await getText("w/bus/jeju/arrival/" + id.slice(3)));
  return (Array.isArray(rows) ? rows : findRows(rows, "ROUTE_NUM")).map((r) => {
    const g = (k) => r[k] ?? r[k.toLowerCase()];
    const min = int(g("PREDICT_TRAV_TM"));
    return { routeId: g("ROUTE_ID") != null ? "JEB" + g("ROUTE_ID") : "", routeNo: String(g("ROUTE_NUM") ?? ""), routeType: null,
      vehicleType: g("LOW_PLATE_TP") === "Y" ? "저상버스" : null, prevStops: int(g("REMAIN_STATION")), arrSeconds: min == null ? null : min * 60, city: "39",
      towards: str(g("ROUTE_SUB_NM")) };
  }).filter((a) => a.routeNo && a.arrSeconds != null);
}

/**
 * 울산 BIS 정류장 도착 (공공데이터포털 "울산광역시_BIS 정보_GW", 앱과 같은 자료). ARRIVALTIME 초, PREVSTOPCNT 남은 정류장,
 * ROUTENM 노선번호(예전 형식은 "527(태화강역방면)"), REMARK "○○ 방면", ROUTEID 는 TAGO 와 같은 번호(앞에 USB)
 */
async function ulsanArrivals(id) {
  const x = await getText("w/bus/ulsan/arrival/" + id.slice(3));
  const doc = new DOMParser().parseFromString(x, "text/xml");
  const code = doc.querySelector("resultCode")?.textContent?.trim();
  if (code && code !== "200") throw new Error("울산 오류 " + code);
  return [...doc.getElementsByTagName("row")].map((el) => {
    const r = {};
    for (const c of el.children) r[c.tagName.toUpperCase()] = c.textContent.trim();
    const m = /^(.*?)\s*\((.*?)\)\s*$/.exec(r.ROUTENM || "");
    const no = (m ? m[1] : r.ROUTENM || "").trim();
    const toward = (m ? m[2] : r.REMARK || "").replace(/\s*방면$/, "").trim();
    return { routeId: r.ROUTEID ? "USB" + r.ROUTEID : "", routeNo: no, routeType: null, vehicleType: null, prevStops: int(r.PREVSTOPCNT),
      arrSeconds: int(r.ARRIVALTIME), city: "26", towards: toward || null, atStop: str(r.PRESENTSTOPNM) };
  }).filter((a) => a.routeNo && a.arrSeconds != null);
}

/** 대전 BIS XML(<ServiceResult>…<itemList>) → [{태그: 값}]. headerCd 가 0·4(결과 없음)가 아니면 오류 */
export function daejeonRows(xml) {
  const code = /<headerCd>\s*(\d+)\s*<\/headerCd>/.exec(xml)?.[1];
  if (code !== "0" && code !== "4") throw new Error("대전 오류 " + (code ?? "형식"));
  return [...String(xml).matchAll(/<itemList>([\s\S]*?)<\/itemList>/g)].map((m) => {
    const o = {};
    for (const [, k, v] of m[1].matchAll(/<(\w+)>([^<]*)<\/\1>/g)) o[k] = v.trim();
    return o;
  });
}

/** 대전 ROUTE_TP → TAGO 와 같은 이름 (앱 RegionalMappers.daejeonType 과 같음) */
const DAEJEON_TYPES = { 1: "급행버스", 2: "간선버스", 3: "광역버스", 4: "지선버스", 5: "마을버스", 7: "광역버스" };

/**
 * 대전 BIS 정류장 도착 (앱과 같은 자료): EXTIME_SEC 남은 초, STATUS_POS 남은 정류장, DESTINATION 방면.
 * MSG_TP 07 은 아직 출발하지 않은 차(EXTIME_SEC 에 출발 시각 1440 = 14:40 이 들어 있음)라 뺌. 같은 순간 TAGO 보다 40~130초 새로움
 */
export function daejeonArrivalList(xml) {
  return daejeonRows(xml).filter((r) => r.MSG_TP !== "07").map((r) => ({
    routeId: r.ROUTE_CD ? "DJB" + r.ROUTE_CD : "", routeNo: str(r.ROUTE_NO), routeType: DAEJEON_TYPES[int(r.ROUTE_TP)] || null, vehicleType: null,
    prevStops: int(r.STATUS_POS), arrSeconds: int(r.EXTIME_SEC), city: "25", towards: str(r.DESTINATION),
  })).filter((a) => a.routeNo && a.arrSeconds != null);
}

/** 대전 BIS 노선 버스 위치: 정류장 좌표(TAGO 와 같음, 한 정류장쯤 새로움), dir 0 가는 길·1 오는 길 (같은 정류장이 두 번이면 뒤쪽) */
export function daejeonBusList(xml) {
  return daejeonRows(xml).filter((x) => x.PLATE_NO).map((x) => ({
    nodeId: x.BUS_NODE_ID ? "DJB" + x.BUS_NODE_ID : null, ord: null, vehicleNo: x.PLATE_NO, lat: num(x.GPS_LATI), lng: num(x.GPS_LONG), dir: int(x.DIR),
  }));
}

/** 정류장의 지역 자체 도착 (경기 GBIS·전주시·제주 BIS·울산 BIS·대전 BIS). 없으면 null */
function localArrivals(s) {
  if (/^JUB\d{9}$/.test(s.id)) return jeonjuArrivals(s.id);
  if (/^JEB\d{9}$/.test(s.id)) return jejuArrivals(s.id);
  if (/^USB\d{9}$/.test(s.id)) return ulsanArrivals(s.id);
  if (/^DJB\d{7}$/.test(s.id)) return getText("w/bus/daejeon/arrival/" + s.id.slice(3)).then(daejeonArrivalList);
  if (ggId(s.id) && ggCity(s.city)) return getText("w/bus/gg/arrival/" + ggId(s.id)).then(ggArrivals);
  return null;
}

/**
 * 정류장 도착. quickMs 를 주면(화면을 처음 열 때) 지역 도착이 그 안에 안 오는 정류장은 TAGO 도착을 먼저 쓰고,
 * 결과의 later(Promise) 가 지역 도착까지 받은 결과를 줌 — 전주시 서버가 가끔 6~7초 걸려 첫 화면이 10초쯤 비었음
 */
export async function arrivals(stop, { quickMs = null, reuse = null } = {}) {
  try {
    const t = await targets(stop);
    // 경기·전주·제주 정류장: 지역 자체 도착을 먼저 (앱과 같음). 같은 정류장·같은 순간에 비교하니 TAGO 보다 1~2분 새롭고 버스가 더 많았음.
    // 그 정류장의 TAGO 도착은 지역 도착을 못 받았거나 비었을 때만 씀 (둘 다 쓰면 같은 버스가 시간이 달라 두 번 보였음)
    const locals = new Map();
    for (const s of [stop, ...(await sameSpot(stop))]) if (!locals.has(s.id)) { const p = reuse?.get(s.id) || localArrivals(s); if (p) locals.set(s.id, p); }
    if (!t.tago.length && !t.metro.length && !locals.size) return { state: "unsupported", list: [], at: Date.now() };
    // 처음 열 때: quickMs 안에 안 오는 지역 도착은 "늦음"으로 보고 그 정류장은 TAGO 를 먼저 (지역 도착은 later 로)
    const SLOW = Symbol("slow"), slow = new Set(), shown = new Map();
    for (const [id, p] of locals) shown.set(id, quickMs == null ? p : Promise.race([p, new Promise((r) => setTimeout(() => r(SLOW), quickMs))]).then((l) => { if (l === SLOW) slow.add(id); return l; }));
    // 지역마다 한꺼번에 물음 (차례로 물으면 느린 지역들의 시간을 더한 만큼 늦음)
    const res = await Promise.allSettled([
      ...[...shown.values()].map((p) => p.then((l) => (l === SLOW ? [] : l))),
      ...t.tago.map(([c, id]) => {
        const p = shown.get(id);
        return p ? p.then((l) => (l !== SLOW && l.length ? [] : tagoArrivals(c, id)), () => tagoArrivals(c, id)) : tagoArrivals(c, id);
      }),
      ...t.metro.map(async (p) => seoulArrivals(await getText(p))),
    ]);
    if (res.every((r) => r.status === "rejected")) throw res[0].reason;
    const seen1 = new Set(), seen2 = new Set();
    const list = res.flatMap((r) => (r.status === "fulfilled" ? r.value : []))
      .filter((a) => { const k = a.routeId + "|" + a.arrSeconds; return !seen1.has(k) && seen1.add(k); })
      // 같은 버스가 두 곳(시군)에서 다른 노선 ID 로 올 때만 하나로: 번호·분·남은 정류장이 다 같을 때 (본선·지선이 같은 분에 와도 남겨 둠)
      .filter((a) => { const k = a.routeNo + "|" + Math.floor((a.arrSeconds ?? -60) / 60) + "|" + (a.prevStops ?? ""); return !seen2.has(k) && seen2.add(k); })
      .sort((a, b) => (a.arrSeconds ?? 1e9) - (b.arrSeconds ?? 1e9));
    const later = slow.size ? Promise.allSettled([...slow].map((id) => locals.get(id))).then(() => arrivals(stop, { reuse: locals })) : null;   // 이미 받은 지역 도착을 그대로 씀 (다시 묻지 않음)
    // 일부(다른 시군 TAGO 등)를 못 받았으면 partial: 화면이 원인(막힌 자료)을 함께 보여 줌
    return { state: "ok", list, at: Date.now(), later, partial: res.some((r) => r.status === "rejected") };
  } catch (e) {
    return { state: "failed", list: [], at: Date.now(), why: String(e.message || e) };
  }
}

// ── 버스 위치로 도착 정보 고치기 (앱 PassedByPosition 과 같은 규칙) ──
// 도착 예측은 늦게 갱신되고 버스 위치가 더 새로운 경우가 많아서: 막 지나간 버스는 빼고, 위치가 더 가까우면 남은 정류장·시간을 줄임
// 다가오는 모든 노선의 버스 위치를 봄 (예전엔 한도를 아끼려고 6개 노선까지만 → 나머지 노선은 막 지나간 버스가 남아 보였음)
// 지난 것으로 보는 범위: 앞쪽에 오는 버스가 없고 이만큼 안에 지나간 버스가 있으면 낡은 도착 (예전 2 → 3정류장 지난 버스가 "도착"으로 남았음)
const APPROACH_STOPS = 6, JUST_PASSED_STOPS = APPROACH_STOPS;
const memo = new Map();
function remembered(key, ms, make) {
  const hit = memo.get(key);
  if (hit && Date.now() - hit.at < ms) return hit.p;
  const p = make().catch((e) => { memo.delete(key); throw e; });
  memo.set(key, { at: Date.now(), p });
  return p;
}
export function verdict(stop, busOrds, claimed) {
  const window = Math.max(APPROACH_STOPS, (claimed ?? 0) + 2);
  if (busOrds.some((o) => o >= stop - window && o <= stop - 1)) return "keep";
  if (busOrds.includes(stop)) return (claimed ?? 0) > 0 ? "at" : "keep";
  return busOrds.some((o) => o >= stop + 1 && o <= stop + JUST_PASSED_STOPS) ? "passed" : "keep";
}
export function closer(stop, busOrds, claimed, sec) {
  if (!(claimed > 1)) return null;
  const window = Math.max(APPROACH_STOPS, claimed + 2);
  const near = busOrds.filter((o) => o >= stop - window && o <= stop - 1);
  if (!near.length) return null;
  const k = stop - Math.max(...near);
  if (k >= claimed) return null;
  let s = sec == null ? null : Math.floor((sec * k) / claimed);
  if (s != null && k === 1) s = Math.min(s, 60);
  return [k, s];
}
/** 노선 요약(기점·종점·첫차·막차·배차)만: 자료 파일에 있으면 파일에서, 없으면 TAGO 노선 정보 한 번 (정류장 목록은 받지 않음) */
export function routeInfo(id, city) {
  return remembered("info|" + id, 3600000, async () => {
    const legacy = legacyId(id);
    const m = (await cached(`data/routes/${bucket(legacy)}.json`).catch(() => ({})))[legacy];
    if (m && m.no != null) {
      const info = Array.isArray(m.info) ? m.info : [];
      return { id, no: String(m.no), start: str(m.start), end: str(m.end), type: str(m.rtype), first: str(info[0]), last: str(info[1]), interval: int(info[2]), intervalSat: int(info[3]), intervalSun: int(info[4]) };
    }
    if (!city || !/^\d+$/.test(city)) return null;
    const r = xmlRows(await getText("w/dg/1613000/BusRouteInfoInqireService/getRouteInfoIem", { cityCode: city, routeId: currentId(id) }))[0];
    if (!r) return null;
    const clockOf = (t) => (t && /^\d{3,4}$/.test(t) ? t.padStart(4, "0").replace(/(\d\d)(\d\d)/, "$1:$2") : null);
    return { id, no: str(r.routeno), start: str(r.startnodenm), end: str(r.endnodenm), type: str(r.routetp), first: clockOf(r.startvehicletime), last: clockOf(r.endvehicletime),
      interval: int(r.intervaltime), intervalSat: int(r.intervalsattime), intervalSun: int(r.intervalsuntime) };
  });
}

// ── 정류장 시간표 (지난 운행 기록: 앱·웹이 본 버스가 이 정류장에 도착한 시각, 최근 6주) ──
/** 이 정류장(같은 자리 정류장 포함)의 기록 {노선 id: [분(1970년부터)...]} */
export async function stopLog(stop) {
  const ids = [stop, ...(await sameSpot(stop))].map((s) => s.id).slice(0, 4);
  return (await getJson("w/log/stop?" + ids.map((i) => "node=" + encodeURIComponent(i)).join("&"))).routes || {};
}
const KST_MIN = 9 * 60, CLUSTER_MIN = 5, SAME_PASS_MIN = 3;
/** 1970년부터 날 수 → 요일 (월 1 … 일 7). 1970-01-01 은 목요일 */
export const isoDayOf = (day) => ((day + 3) % 7) + 1;
/**
 * 기록 분 목록 → 이 요일(isoDay)의 시간표 (앱 ArrivalStats.table 과 같은 계산):
 * 같은 날 3분 안의 기록은 한 번, 시각별로 묶되 묶음 첫 시각에서 5분 넘게 벌어지거나 같은 날이 두 번 나오면 다음 운행.
 * {days: 기록이 있는 날 수, slots: [{expected 가운데, earliest, latest, days}]} (시각은 하루 중 분)
 */
export function timetable(minutes, isoDay) {
  const rows = [];
  for (const m of minutes) {
    const local = m + KST_MIN, day = Math.floor(local / 1440);
    if (isoDayOf(day) === isoDay) rows.push([day, ((local % 1440) + 1440) % 1440]);
  }
  rows.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const uniq = [];
  for (const r of rows) { const l = uniq[uniq.length - 1]; if (!l || l[0] !== r[0] || r[1] - l[1] > SAME_PASS_MIN) uniq.push(r); }
  uniq.sort((a, b) => a[1] - b[1]);
  const groups = [];
  for (const r of uniq) {
    const g = groups[groups.length - 1];
    if (g && r[1] - g[0][1] <= CLUSTER_MIN && !g.some((x) => x[0] === r[0])) g.push(r); else groups.push([r]);
  }
  const slots = groups.map((g) => {
    const m = g.map((x) => x[1]).sort((a, b) => a - b);
    return { expected: m[m.length >> 1], earliest: m[0], latest: m[m.length - 1], days: new Set(g.map((x) => x[0])).size };
  });
  return { days: new Set(uniq.map((x) => x[0])).size, slots };
}

// ── 예상 시간표 (기록이 모자란 노선): 첫차·막차·배차 간격 + 기점에서 이 정류장까지 걸리는 시간 ──
const EST_M_PER_MIN = 300, EST_STOP_MIN = 0.3;
const clockMin = (t) => { const m = /^(\d{1,2}):?(\d\d)$/.exec(String(t || "").trim()); return m ? +m[1] * 60 + +m[2] : null; };
/**
 * 노선 요약(sum)과 기점→정류장 걸리는 분(offset)으로 이 요일(day)의 예상 도착 시각(하루 중 분) 목록.
 * 첫차=막차면 한 번, 배차 간격이 3~180분이면 첫차부터 막차까지, 간격이 없으면 첫차·막차만. 모르면 []
 */
export function plannedTimes(sum, offset, day, downOffset = null) {
  if (!sum) return [];
  // 지자체 공식 배차시간표(기점 출발 시각 전체)가 있으면 그것을 씀. 종점에서도 출발하는 노선은 돌아오는 쪽 정류장에 그 버스도 더함
  if (sum.deps) {
    // 토·일 시각이 비면 토 → 평일 순으로 (앱 CityTimetable.parse 와 같음). 종점 출발은 그 요일 것만
    const arr = (l) => (Array.isArray(l) ? l : []);
    const pick = (k) => {
      const d = sum.deps, wd = arr(d[k]), sat = arr(d[k.replace("weekday", "sat")]), holi = arr(d[k.replace("weekday", "holi")]);
      if (k !== "weekday") return day === 6 ? sat : day === 7 ? holi : wd;
      return day === 6 ? (sat.length ? sat : wd) : day === 7 ? (holi.length ? holi : sat.length ? sat : wd) : wd;
    };
    const off = Math.max(0, Math.round(offset || 0));
    const out = pick("weekday").map((m) => m + off);
    if (downOffset != null) for (const m of pick("weekdayDown")) out.push(m + Math.max(0, Math.round(downOffset)));
    return [...new Set(out)].sort((a, b) => a - b);
  }
  const first = clockMin(sum.first);
  let last = clockMin(sum.last);
  if (first == null) return [];
  if (last == null) last = first;
  if (last < first) last += 1440;   // 자정 넘어 막차
  const iv = intervalOn(sum, day);
  const off = Math.max(0, Math.round(offset || 0));
  if (last === first) return [first + off];
  if (!(iv >= 3 && iv <= 180)) return [first + off, last + off];
  const out = [];
  for (let t = first; t <= last && out.length < 200; t += iv) out.push(t + off);
  if (out[out.length - 1] !== last + off && last - (out[out.length - 1] - off) >= 2) out.push(last + off);
  return out;
}
/** 노선 정류장 목록에서 from 번째 정류장부터 이 정류장(ids 중 하나)까지 걸리는 분 (거리 ÷ 시속 18km + 정류장마다 18초). 못 찾으면 null */
export function offsetMinutes(stops, ids, from = 0) {
  const i = stops.findIndex((s, k) => k >= from && (ids.has(s.id) || ids.has(currentId(s.id || ""))));
  if (i < 0) return null;
  let m = 0;
  for (let k = from; k < i; k++) {
    const a = stops[k], b = stops[k + 1];
    if (a.lat != null && b.lat != null) m += meters(a.lat, a.lng, b.lat, b.lng) / EST_M_PER_MIN;
    m += EST_STOP_MIN;
  }
  return m;
}
/** 노선 ID 앞 글자 → 공식 배차시간표를 주는 지역 (서버 src/timetable.js) */
const TIME_AREAS = { JUB: "jeonju", USB: "ulsan", CAB: "cheonan", CWB: "changwon", DGB: "daegu", JEB: "jeju", JJB: "jinju", GMB: "gumi", ADB: "andong", GJB: "geoje" };
/** 배포할 때 받아 둔 자료 조각(data/times/<지역>.json)으로 보는 지역: 천안·구미(서버 접속 막힘), 안동·거제(데이터 PC 에서 받음, 2026-11-24) */
const FILE_TIME_AREAS = new Set(["cheonan", "gumi", "andong", "geoje"]);
/** 서버에 묻지 않고 자료 조각만 보는 지역 */
const FILE_ONLY_AREAS = new Set(["andong", "geoje"]);
/**
 * 노선의 공식 배차시간표 {weekday, sat, holi:[분], source, tables?} — 지자체 버스정보 사이트(서버 하루 저장) 또는 배포 때 받은 자료 조각. 없으면 null
 */
export async function routeDeps(id, no) {
  const area = TIME_AREAS[String(id).slice(0, 3)];
  if (!area || !/^[A-Z]{3}\d{1,12}$/.test(id)) return null;
  const q = area === "daegu" || area === "jeju" ? "?no=" + encodeURIComponent(no || "") : "";
  let deps = FILE_ONLY_AREAS.has(area) ? null : await remembered("bustime|" + id, 3600000, () => getJson(`w/bus/time/${area}/${id.slice(3)}${q}`)).catch(() => null);
  if ((!deps || !Array.isArray(deps.weekday)) && FILE_TIME_AREAS.has(area)) deps = (await cached(`data/times/${area}.json`).catch(() => ({})))[id.slice(3)] || null;
  return deps && Array.isArray(deps.weekday) && (deps.weekday.length || (deps.holi || []).length) ? deps : null;
}
/** 오늘(한국 시각 요일) 시간표 칸: 토 → sat, 일 → holi, 나머지 weekday (공휴일은 모름) */
export function todayDeps(deps, dow = new Date(Date.now() + 9 * 3600 * 1000).getUTCDay()) {
  if (!deps) return [];
  const k = dow === 6 ? "sat" : dow === 0 ? "holi" : "weekday";
  return (deps[k] && deps[k].length ? deps[k] : deps.weekday) || [];
}
/** 지도 버스 진행 방향: 같은 차의 앞 위치에서 15m 넘게 움직였으면 그 방향(북쪽 0도, 시계 방향), 아니면 앞서 잰 방향 */
export function headingTracker() {
  const last = new Map();
  return (id, lat, lng) => {
    if (!id || lat == null || lng == null) return null;
    const p = last.get(id);
    if (!p) { last.set(id, { lat, lng, dir: null }); return null; }
    if (meters(p.lat, p.lng, lat, lng) < 15) return p.dir;
    const y = Math.sin((lng - p.lng) * Math.PI / 180) * Math.cos(lat * Math.PI / 180);
    const x = Math.cos(p.lat * Math.PI / 180) * Math.sin(lat * Math.PI / 180) - Math.sin(p.lat * Math.PI / 180) * Math.cos(lat * Math.PI / 180) * Math.cos((lng - p.lng) * Math.PI / 180);
    const dir = (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
    last.set(id, { lat, lng, dir });
    return dir;
  };
}
/**
 * 지도 버스 이름표 겹침 줄이기: 줌 15 아래는 이름표 없이 번호만, 15부터는 이미 이름표를 단 버스 가까이(가로 70px·세로 26px 안)의 이름표는 숨김.
 * dots 의 bus3d.caption 만 바꾼 새 배열
 */
export function declutterCaptions(dots, zoom, metersPerPx) {
  const mpp = metersPerPx > 0 ? metersPerPx : 1;
  if (zoom < 15) return dots.map((d) => (d.bus3d && d.bus3d.caption ? { ...d, bus3d: { ...d.bus3d, caption: null } } : d));
  // 어느 버스가 이름표를 달지는 id 순서로 정함: 받은 순서대로 정하면 5초마다 순서가 바뀌어 이름표가 이 버스 저 버스로 옮겨 다녔음
  const kept = [], show = new Set();
  for (const d of dots.filter((x) => x.bus3d && x.bus3d.caption).sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))) {
    if (kept.some((k) => Math.abs(meters(k.lat, d.lng, k.lat, k.lng)) / mpp < 70 && Math.abs(meters(d.lat, d.lng, k.lat, d.lng)) / mpp < 26)) continue;
    kept.push(d); show.add(d);
  }
  return dots.map((d) => (!d.bus3d || !d.bus3d.caption || show.has(d) ? d : { ...d, bus3d: { ...d.bus3d, caption: null } }));
}
/** 저상버스만 보기 (이 브라우저에 기억, 정류장 도착·지도 버스) */
export const LowFloor = {
  on() { try { return localStorage.getItem("tagohub.lowOnly") === "1"; } catch (e) { return false; } },
  set(v) { try { localStorage.setItem("tagohub.lowOnly", v ? "1" : "0"); } catch (e) { /* 저장 못 하면 이번만 */ } },
};
/** 도착 정보가 저상버스인지 (vehicleType "저상버스"). 지역 자료가 차 종류를 아예 안 주면 null */
export const isLowArrival = (a) => (a && a.vehicleType ? /저상/.test(a.vehicleType) : null);
/** 시간표 주요 정류장 이름 ↔ 노선 정류장 이름 맞추기용: 괄호 안·띄어쓰기·종점 표시를 빼고 학교 이름은 줄임말로 */
const normName = (s) => String(s || "").replace(/\[[^\]]*\]|\([^)]*\)/g, "").replace(/[\s.·]/g, "")
  .replace(/초등학교/g, "초교").replace(/중학교/g, "중").replace(/고등학교/g, "고").replace(/(종점|기점|정류장|정류소|가상)$/g, "");
const sameName = (a, b) => !!a && !!b && (a === b || (Math.min(a.length, b.length) >= 2 && (a.startsWith(b) || b.startsWith(a) || (Math.min(a.length, b.length) >= 3 && (a.includes(b) || b.includes(a))))));
/**
 * 회차별 주요 정류장 통과 시각표 {points, trips} → 노선 정류장 목록의 i 번째 정류장 도착 시각들.
 * 주요 정류장을 노선 순서대로 이름으로 맞추고, 앞뒤 주요 정류장 시각을 거리(예상 걸리는 시간) 비율로 나눔.
 * 앞뒤 주요 정류장 중 한쪽 시각이 없는 회차(이 정류장을 안 지나는 짧은 운행)는 뺌
 */
export function timesAtStop(table, stops, i) {
  if (!table || !Array.isArray(table.points) || !Array.isArray(table.trips) || i < 0) return [];
  const cum = [0];
  for (let k = 1; k < stops.length; k++) {
    const a = stops[k - 1], b = stops[k];
    cum.push(cum[k - 1] + (a.lat != null && b.lat != null ? meters(a.lat, a.lng, b.lat, b.lng) / EST_M_PER_MIN : 0) + EST_STOP_MIN);
  }
  let from = 0;
  const at = table.points.map((p) => {
    const n = normName(p);
    const k = stops.findIndex((s, j) => j >= from && sameName(normName(s.name), n));
    if (k >= 0) from = k + 1;
    return k;
  });
  if (at.filter((k) => k >= 0).length < 2) return [];
  const out = new Set();
  for (const trip of table.trips) {
    let a = null, b = null;
    trip.forEach((m, c) => {
      const k = at[c];
      if (m == null || k < 0) return;
      if (k <= i && (!a || k >= a.k)) a = { k, m };
      if (k >= i && (!b || k < b.k)) b = { k, m };
    });
    if (!a || !b) continue;
    if (a.k === b.k) { out.add(a.m); continue; }
    const span = cum[b.k] - cum[a.k];
    out.add(Math.round(a.m + (b.m - a.m) * (span > 0 ? (cum[i] - cum[a.k]) / span : 0)));
  }
  return [...out].sort((x, y) => x - y);
}
/** 이 정류장을 지나는 노선 하나의 예상 시간표 재료 {sum, offset, downOffset} (노선 정보는 10분 기억). 모르면 null */
export async function plannedFor(stop, rt) {
  const ids = new Set([stop, ...(await sameSpot(stop))].flatMap((s) => [s.id, currentId(s.id)]));
  const r = await remembered("route|" + rt.id, 600000, () => route(rt.id, rt.city || (/^\d+$/.test(stop.city) ? stop.city : null)));
  if (!r) return null;
  let sum = rt.first ? rt : r.sum;
  // 지자체 버스정보시스템 배차시간표 (전주·울산·천안·창원·대구·제주·진주·구미, 서버가 하루 저장)
  {
    // 서버가 못 받는 지역(천안·구미·안동·거제)은 배포할 때 받아 둔 자료 조각 (pipeline/fetch_city_times.py)
    const deps = await routeDeps(rt.id, rt.no || r.no || "");
    if (deps && Array.isArray(deps.weekday) && deps.weekday.length) {
      const fmt = (m) => String(Math.floor(m / 60)).padStart(2, "0") + ":" + String(m % 60).padStart(2, "0");
      sum = { ...(sum || {}), first: fmt(deps.weekday[0]), last: fmt(deps.weekday[deps.weekday.length - 1]), deps, source: deps.source };
    }
  }
  if (!sum || !sum.first) return null;
  const offset = offsetMinutes(r.stops, ids);
  if (offset == null) return null;
  // 주요 정류장 통과 시각표가 있으면 (대구·제주) 이 정류장 시각을 바로 계산. 방향별 표 여러 개면 정류장 순서가 맞는 표만
  if (sum.deps && Array.isArray(sum.deps.tables) && sum.deps.tables.length) {
    const i = r.stops.findIndex((s) => ids.has(s.id) || ids.has(currentId(s.id || "")));
    const dayTimes = (key) => [...new Set(sum.deps.tables.filter((t) => (t.days || []).includes(key)).flatMap((t) => timesAtStop(t, r.stops, i)))].sort((a, b) => a - b);
    const at = { weekday: dayTimes("weekday"), sat: dayTimes("sat"), holi: dayTimes("holi") };
    if (at.weekday.length || at.sat.length) return { sum: { ...sum, deps: { ...at, source: sum.deps.source } }, offset: 0, downOffset: null };
  }
  // 종점에서도 출발하는 노선: 종점(회차) 정류장 뒤쪽이면 종점 출발 버스는 회차점부터 걸리는 시간
  let downOffset = null;
  if (sum.deps && Array.isArray(sum.deps.weekdayDown) && sum.deps.weekdayDown.length) {
    // 회차점 찾기는 앱 CityTimetable.stopTimes 와 같게 (이름 다듬어 맞춤)
    const endName = normName(r.end || sum.end || "");
    const turn = endName ? r.stops.findIndex((s, k) => k > 0 && sameName(normName(s.name), endName)) : -1;
    if (turn > 0) downOffset = offsetMinutes(r.stops, ids, turn);
  }
  return { sum, offset, downOffset };
}

/** 정류장 방면: 이 정류장을 지나는 노선에서 바로 다음 정류장 이름 (노선 몇 개를 보고 가장 많이 나온 것). 모르면 null */
export async function towardOf(stop, routes) {
  const ids = new Set([stop, ...(await sameSpot(stop))].map((s) => s.id));
  const names = new Map();
  for (const rt of routes.slice(0, 4)) {
    try {
      const r = await remembered("route|" + rt.id, 600000, () => route(rt.id, rt.city || (/^\d+$/.test(stop.city) ? stop.city : null)));
      const i = r ? r.stops.findIndex((s) => ids.has(s.id)) : -1;
      const next = i >= 0 ? r.stops.slice(i + 1).find((s) => s.name && s.name !== stop.name) : null;
      if (next) names.set(next.name, (names.get(next.name) || 0) + 1);
    } catch (e) { /* 다음 노선 */ }
  }
  return [...names.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] || null;
}

/** 받은 도착 목록을 버스 위치로 고친 목록 (못 고치면 같은 목록). 화면은 먼저 받은 목록을 보여 주고 이것이 오면 바꿈 */
export function refineArrivals(stop, list) { return fixByPosition(stop, list).catch(() => list); }
async function fixByPosition(stop, list) {
  const firsts = new Map();
  for (const a of list) {
    if (a.prevStops == null || a.prevStops > APPROACH_STOPS || !a.routeId) continue;
    const b = firsts.get(a.routeId);
    if (!b || (a.arrSeconds ?? 1e9) < (b.arrSeconds ?? 1e9)) firsts.set(a.routeId, a);
  }
  if (!firsts.size) return list;
  const ids = new Set([stop, ...(await sameSpot(stop))].map((s) => s.id));
  const out = new Map();
  await Promise.all([...firsts.values()].map(async (a) => {
    try {
      const r = await remembered("route|" + a.routeId, 600000, () => route(a.routeId, a.city || null));
      if (!r || !r.stops.length) return;
      const ords = r.stops.filter((s) => ids.has(s.id)).map((s) => s.seq);
      if (ords.length !== 1) return; // 순환 노선처럼 두 번 나오면 판단하지 않음
      const pos = await remembered("pos|" + a.routeId, 12000, () => positions(r));
      if (pos.state !== "ok") return;
      const seqOf = new Map(r.stops.map((s) => [s.id, s.seq]));
      const busOrds = pos.buses.map((b) => (b.nodeId && seqOf.has(b.nodeId) ? seqOf.get(b.nodeId) : b.ord)).filter((o) => o != null);
      if (!busOrds.length) return;
      if (verdict(ords[0], busOrds, a.prevStops) !== "keep") { out.set(a, null); return; }
      const c = closer(ords[0], busOrds, a.prevStops, a.arrSeconds);
      if (c) out.set(a, { ...a, prevStops: c[0], arrSeconds: c[1] ?? a.arrSeconds });
    } catch (e) { /* 모르면 그대로 */ }
  }));
  if (!out.size) return list;
  return list.flatMap((a) => (out.has(a) ? (out.get(a) ? [out.get(a)] : []) : [a])).sort((a, b) => (a.arrSeconds ?? 1e9) - (b.arrSeconds ?? 1e9));
}

/**
 * 이 정류장 도착에 쓰는 자료 중 지금 막혀 있는 것 [{label, since}] (서버가 30분마다 확인하는 /w/health, 1분 저장).
 * 예: 2026-11-16 TAGO 도착이 30분 넘게 "가용한 세션이 없습니다"(오류 99)였는데 화면엔 "받지 못했어요"만 보였음
 */
let healthMemo = null;
export async function downFor(stop) {
  if (!healthMemo || Date.now() - healthMemo.at > 60000) healthMemo = { at: Date.now(), p: getJson("w/health").catch(() => null) };
  const h = await healthMemo.p;
  if (!h?.checks) return [];
  const id = stop.id || "";
  const names = id.startsWith("SEB") ? ["seoul_bus"] : ["tago_arrival"];
  if (id.startsWith("JUB")) names.push("jeonju_bus");
  else if (id.startsWith("JEB")) names.push("jeju_bus");
  else if (id.startsWith("USB")) names.push("ulsan_bus");
  else if (id.startsWith("DJB")) names.push("daejeon_bus");
  else if (ggId(id) && ggCity(stop.city || "")) names.push("gyeonggi_bus");
  return h.checks.filter((c) => names.includes(c.name) && !c.ok).map((c) => ({ label: c.label, since: c.failSince || c.at }));
}

/** 서울 버스 위치 busType → 차 종류 (0 일반, 1 저상, 2 굴절) */
export const seoulKind = (t) => ({ 0: "일반", 1: "저상", 2: "굴절" })[String(t ?? "").trim()] || null;
/** 경기 버스 위치 lowPlate → 차 종류 (0 일반, 1 저상, 2 2층, 5 전세, 6 예약, 7 트롤리) */
export const ggKind = (t) => ({ 0: "일반", 1: "저상", 2: "2층", 5: "전세", 6: "예약", 7: "트롤리" })[String(t ?? "").trim()] || null;
/**
 * 노선 버스 위치: {state, buses:[{nodeId, ord, vehicleNo, lat, lng, dataAt, kind}]} (dataAt = 자료 시각 ms, 서울만)
 * kind: 차 종류("저상"·"일반"·"굴절"·"2층"…), 위치 자료가 알려 주는 서울·경기만 (대전 BIS·TAGO 위치 자료에는 없어 null)
 */
export async function positions(r) {
  try {
    const sid = seoulId(r.id), gid = ggId(r.id);
    if (sid) {
      const rows = findRows(JSON.parse(await getText("w/bus/seoul/pos/" + sid)), "vehId");
      return { state: "ok", buses: rows.filter((x) => x.isrunyn !== "0").map((x) => ({ nodeId: x.lastStnId ? "SEB" + x.lastStnId : null, ord: int(x.sectOrd), vehicleNo: str(x.plainNo) || str(x.vehId), lat: num(x.gpsY), lng: num(x.gpsX), dataAt: seoulTime(x.dataTm), crowd: crowdSeoul(x.congetion ?? x.congestion, x.isFullFlag), kind: seoulKind(x.busType) })) };
    }
    // 경기 노선 id(GGB+9자리)는 경기 버스(GBIS)로. 대전 터미널에 서는 경기 시외버스(8302 시흥-대전 등)는 도시가 대전이라
    // TAGO 에 묻고 있었는데 TAGO 에는 그 노선이 없어 늘 빈 답 — TAGO 위치 하루 한도만 썼음 (2026-11-25)
    if (gid) {
      const rows = findRows(JSON.parse(await getText("w/bus/gg/pos/" + gid)), "plateNo");
      return { state: "ok", buses: rows.map((x) => ({ nodeId: x.stationId != null ? "GGB" + x.stationId : null, ord: int(x.stationSeq), vehicleNo: str(x.plateNo), lat: null, lng: null, kind: ggKind(x.lowPlate) })) };
    }
    // 대전 BIS (앱과 같음): 못 받거나 비면 아래 TAGO
    if (/^DJB\d{8}$/.test(r.id)) {
      const dj = await getText("w/bus/daejeon/pos/" + r.id.slice(3)).then(daejeonBusList).catch(() => []);
      if (dj.length) return { state: "ok", buses: dj };
    }
    if (!r.city || !/^\d+$/.test(r.city) || metroId(r.id, r.city) || r.id.startsWith("TSB")) return { state: "unsupported", buses: [] };
    const rows = xmlRows(await getText("w/dg/1613000/BusLcInfoInqireService/getRouteAcctoBusLcList", { cityCode: r.city, routeId: r.id }));
    return { state: "ok", buses: rows.map((x) => ({ nodeId: str(x.nodeid), ord: int(x.nodeord), vehicleNo: str(x.vehicleno), lat: num(x.gpslati), lng: num(x.gpslong) })) };
  } catch (e) {
    return { state: "failed", buses: [] };
  }
}

/** 버스가 노선의 어느 정류장(목록 자리)에 있는지 */
export function busAtStops(stops, buses) {
  const at = new Map();
  for (const b of buses) {
    // 같은 정류장이 노선에 두 번 있으면(오가는 길이 같은 정류장) 오는 길(dir 1, 대전) 버스는 뒤쪽
    const same = (s) => s.id && currentId(s.id) === currentId(b.nodeId);
    let i = b.nodeId ? (b.dir === 1 ? stops.findLastIndex(same) : stops.findIndex(same)) : -1;
    if (i < 0 && b.ord != null) i = stops.findIndex((s) => s.seq === b.ord);
    if (i < 0) continue;
    if (!at.has(i)) at.set(i, []);
    at.get(i).push(b);
  }
  return at;
}

// ── 도착 문구 (ArrivalText.kt) ──
export function timeText(sec) {
  if (sec == null) return "정보없음";
  if (sec <= 30) return "곧 도착";
  if (sec < 60) return sec + "초";
  if (sec < 180) return `${Math.floor(sec / 60)}분 ${String(sec % 60).padStart(2, "0")}초`;
  return Math.floor(sec / 60) + "분";
}
export function arrivalParts(a, elapsed = 0) {
  const sec = a.arrSeconds == null ? null : Math.max(0, a.arrSeconds - elapsed);
  const low = a.vehicleType && a.vehicleType.includes("저상") ? "저상" : null;
  if (a.waiting) return ["출발 대기", low, a.crowd].filter(Boolean);
  if (a.prevStops === 0 && (sec == null || sec <= 30)) return ["정류장 도착", low, a.crowd].filter(Boolean);
  return [timeText(sec), a.prevStops == null ? null : a.prevStops <= 0 ? "이번 정류장" : a.prevStops + "정류장 전", low, a.crowd].filter(Boolean);
}
/**
 * 기점에서 출발을 기다리는 버스 (앱 ArrivalText.kt OriginWait): 도착정보는 기점에 서 있는 버스를 "0정류장 전 · 곧 도착"으로 줘서
 * 기점 정류장에 출발도 안 한 버스가 "정류장 도착"으로 보였음. 정류장 이름이 노선 기점과 같으면 "출발 대기".
 * 기점과 종점 이름이 같은 노선(회차)은 그 정류장에 닿는 버스일 수도 있어 그대로 둠
 */
export function atOrigin(stopName, start, end) {
  const s = normName(stopName), a = normName(start);
  return !!s && s === a && a !== normName(end);
}
export const markWaiting = (stopName, start, end, list) =>
  atOrigin(stopName, start, end) ? list.map((x) => (x.prevStops === 0 && !x.estimated ? { ...x, waiting: true } : x)) : list;
/** 이미 지나간 버스 (남은 시간이 0 아래로 3초 넘게, 바로 앞이거나 이번 정류장) */
export const passed = (a, elapsed) => !a.waiting && a.arrSeconds != null && a.arrSeconds - elapsed < -3 && (a.prevStops == null || a.prevStops <= 1);

// ── 실시간 도착이 없는 노선 줄: "정보없음" 대신 첫차·막차·배차(기점 출발)로 (앱 NoLiveText 와 같은 규칙) ──
// 운행 시간 밖이면 "운행종료" + 첫차까지 남은 시간, 운행 중이면 다음 출발 어림(배차를 알면)·막차. 첫차·막차를 모르면 null
const toMin = (t) => { const m = /(\d{1,2}):(\d{2})/.exec(t || ""); return m ? +m[1] * 60 + +m[2] : null; };
/** "22:30" → 1350 (못 읽으면 null) */
export const toMinutes = (t) => toMin(t);
const clockOf = (m) => { const x = ((m % 1440) + 1440) % 1440; return `${String(Math.floor(x / 60)).padStart(2, "0")}:${String(x % 60).padStart(2, "0")}`; };
/** 분 → "곧" / "25분" / "1시간" / "8시간 20분" */
export function waitText(m) { return m <= 0 ? "곧" : m < 60 ? `${m}분` : m % 60 === 0 ? `${m / 60}시간` : `${Math.floor(m / 60)}시간 ${m % 60}분`; }
const afterText = (m) => (m <= 0 ? "곧" : `${waitText(m)} 후`);
/** 노선 운행 문구: "첫차 06:00 · 막차 22:20 · 배차 15분", 하루 한 번만 다니면 "하루 1번 06:00" */
export function planText(s, day) {
  if (!s) return "";
  const f = toMin(s.first);
  if (f != null && f === toMin(s.last)) return `하루 1번 ${clockOf(f)}`;
  const iv = intervalOn(s, day);
  return [s.first && "첫차 " + s.first, s.last && "막차 " + s.last, iv > 0 && "배차 " + iv + "분"].filter(Boolean).join(" · ");
}
export function noLive(first, last, interval, nowMin) {
  const f = toMin(first);
  let l = toMin(last), n = nowMin;
  if (f == null || l == null) return null;
  if (l < f) { l += 1440; if (n < f) n += 1440; }
  const fc = clockOf(f);
  // 하루 한 번만 다니는 갈래 노선(첫차 = 막차, 전주 165 효자로전북도청→전주동물원 06:00 등): "첫차 06:00 · 막차 06:00" 대신 (앱 NoLiveText 와 같음)
  if (f === l) {
    if (n <= f) return { main: `하루 1번 ${fc}`, sub: `${afterText(f - n)} · 기점`, ended: false };
    return { main: n > l + 90 ? "운행종료" : "출발함", sub: `내일 ${fc} · 하루 1번 · ${afterText(f + 1440 - n)}`, ended: n > l + 90 };
  }
  if (n < f - 30) return { main: "운행종료", sub: `첫차 ${fc} · ${afterText(f - n)}`, ended: true };
  if (n > l + 90) return { main: "운행종료", sub: `내일 첫차 ${fc} · ${afterText(f + 1440 - n)}`, ended: true };
  if (n > l) return { main: "막차 출발함", sub: `내일 첫차 ${fc} · ${afterText(f + 1440 - n)}`, ended: false };
  if (n < f) return { main: `첫차 ${fc}`, sub: `${afterText(f - n)} · 기점`, ended: false };
  if (interval > 0) {
    const t = f + Math.ceil((n - f) / interval) * interval;
    if (t <= l) return { main: `다음 출발 약 ${clockOf(t)}`, sub: `${afterText(t - n)} · 기점`, ended: false };
  }
  return { main: `막차 ${clockOf(l)}`, sub: `${afterText(l - n)} · 기점`, ended: false };
}


// ── 지도 범위 버스 (초정밀 버스, 서버 /w/klid) ──
/**
 * 자료를 주는 지역 (2026-11-25 전국 시·군 코드를 모두 물어 찾음. 서울·광역시·경기 대부분·전북·제주 등은 없음).
 * prefix: 우리 노선 id = prefix + rteId (울산 USB192000001, 창원 CWB379000010, 고양·오산은 경기 GGB, 함양은 교통안전공단 TSB).
 * prefix 가 null 인 곳(구미·양산)은 rteId 가 우리 id 와 달라 노선 번호로 찾음 (data/routes_by_no.json). 지도 범위는 그 지역 정류장 좌표
 */
export const KLID_REGIONS = [
  { code: "3100000000", name: "울산", city: "26", prefix: "USB", s: 35.30, w: 128.95, n: 35.75, e: 129.48 },
  { code: "4128000000", name: "고양", city: "31100", prefix: "GGB", s: 37.56, w: 126.68, n: 37.75, e: 126.94 },
  { code: "4137000000", name: "오산", city: "31140", prefix: "GGB", s: 37.11, w: 126.99, n: 37.22, e: 127.11 },
  { code: "4313000000", name: "충주", city: "33020", prefix: "CHB", s: 36.86, w: 127.73, n: 37.04, e: 128.04 },
  { code: "4315000000", name: "제천", city: "33030", prefix: "JCB", s: 36.94, w: 128.16, n: 37.20, e: 128.40 },
  { code: "4611000000", name: "목포", city: "36010", prefix: "MPB", s: 34.74, w: 126.32, n: 35.04, e: 126.54 },
  { code: "4613000000", name: "여수", city: "36020", prefix: "YEB", s: 34.64, w: 127.52, n: 34.83, e: 127.79 },
  { code: "4623000000", name: "광양", city: "36060", prefix: "KYB", s: 34.87, w: 127.52, n: 35.03, e: 127.76 },
  { code: "4684000000", name: "무안", city: "36420", prefix: "MPB", s: 34.77, w: 126.25, n: 35.16, e: 126.55 },
  { code: "4686000000", name: "함평", city: "36430", prefix: "HPB", s: 34.98, w: 126.34, n: 35.31, e: 126.92 },
  { code: "4719000000", name: "구미", city: "37050", prefix: null, s: 35.95, w: 128.13, n: 36.35, e: 128.56 },
  { code: "4812000000", name: "창원", city: "38010", prefix: "CWB", s: 35.02, w: 128.39, n: 35.40, e: 128.76 },
  { code: "4833000000", name: "양산", city: "38100", prefix: null, s: 35.21, w: 128.90, n: 35.58, e: 129.33 },
  { code: "4887000000", name: "함양", city: "38380", prefix: "TSB", s: 35.38, w: 127.60, n: 35.74, e: 127.88 },
  { code: "5111000000", name: "춘천", city: "32010", prefix: "CCB", s: 37.67, w: 127.52, n: 38.06, e: 127.90 },
];
const overlaps = (r, b) => b.n > r.s && b.s < r.n && b.e > r.w && b.w < r.e;
/** 지도 가운데가 초정밀 버스 지역 안인지 (그 지역은 화면 안 노선마다 따로 묻지 않음) */
export const klidCovers = (b) => { const la = (b.s + b.n) / 2, lo = (b.w + b.e) / 2; return KLID_REGIONS.some((r) => la >= r.s && la <= r.n && lo >= r.w && lo <= r.e); };
/** 서울 (서울 버스는 하루 한도 때문에 화면 안 노선마다 묻지 않음 — 사용자 결정 2026-11-25) */
const SEOUL_BOX = { s: 37.41, n: 37.72, w: 126.76, e: 127.19 };
export const inSeoulView = (b) => { const la = (b.s + b.n) / 2, lo = (b.w + b.e) / 2; return la >= SEOUL_BOX.s && la <= SEOUL_BOX.n && lo >= SEOUL_BOX.w && lo <= SEOUL_BOX.e; };
let byNoP = null;
/** 노선 번호 → 우리 노선 id (rteId 가 다른 지역: 구미·양산). 같은 번호가 여럿이면 첫 것. 못 찾으면 null */
export async function routeIdByNo(city, no) {
  if (!byNoP) byNoP = cached("data/routes_by_no.json").catch(() => { byNoP = null; return {}; });
  const ids = ((await byNoP)[city] || {})[String(no || "")];
  return ids && ids.length ? ids[0] : null;
}
/** 이보다 오래전에 보낸 위치는 지금 안 다니는 차의 마지막 위치 (울산 2,675대 중 하루 넘은 것 1,554대) */
export const KLID_FRESH_MS = 3 * 60 * 1000;
export const KLID_MAX_PAGES = 30;
const klidItems = (j) => { const it = j?.body?.items?.item; return Array.isArray(it) ? it : it ? [it] : []; };
/** "2026-11-24 21:00:34.099502"(한국 시각) → ms */
export const klidTime = (s) => { const t = Date.parse(String(s || "").slice(0, 19).replace(" ", "T") + "+09:00"); return Number.isFinite(t) ? t : null; };
const klidRouteMemo = new Map();
/**
 * 지도 범위 안 버스 [{key, routeId, city, no, type, lat, lng}] — 최근 위치만, 노선 번호·종류는 노선 기준정보로 (위치 자료의 rteNo 는 비어 옴)
 * b: {s, w, n, e}. 자료를 주는 지역이 아니면 []
 */
export async function klidBuses(b, now = Date.now()) {
  // 지도가 걸친 지역 모두 (목포·무안처럼 붙어 있는 곳). 같은 차는 한 번만
  const lists = await Promise.all(KLID_REGIONS.filter((r) => overlaps(r, b)).map((r) => klidRegionBuses(r, b, now).catch(() => [])));
  const seen = new Set();
  return lists.flat().filter((x) => !seen.has(x.key) && seen.add(x.key));
}
async function klidRegionBuses(reg, b, now) {
  if (!klidRouteMemo.has(reg.code)) {
    klidRouteMemo.set(reg.code, getText(`w/klid/routes/${reg.code}/1`).then((t) => new Map(klidItems(JSON.parse(t)).map((x) => [String(x.rteId), x])))
      .catch(() => { klidRouteMemo.delete(reg.code); return new Map(); }));
  }
  const [routes, first] = await Promise.all([klidRouteMemo.get(reg.code), getText(`w/klid/pos/${reg.code}/1`).then(JSON.parse)]);
  const rows = klidItems(first);
  // 지난 위치가 섞여 있어 새 위치가 모든 쪽에 흩어져 있음: 30쪽까지 다 받음 (충주 19쪽, 2026-11-25)
  const pages = Math.min(KLID_MAX_PAGES, Math.ceil((+first?.body?.totalCount || 0) / 1000));
  for (const more of await Promise.all(Array.from({ length: Math.max(0, pages - 1) }, (_, i) => getText(`w/klid/pos/${reg.code}/${i + 2}`).then((t) => klidItems(JSON.parse(t))).catch(() => [])))) rows.push(...more);
  return klidPick(rows, routes, reg, b, now);
}
/** 위치 줄 → 지도에 그릴 버스 (최근 위치·지도 범위 안만) */
export function klidPick(rows, routes, reg, b, now = Date.now()) {
  const out = [];
  for (const x of rows) {
    const lat = num(x.lat), lng = num(x.lot), at = klidTime(x.gthrDt);
    if (lat == null || lng == null || (at != null && now - at > KLID_FRESH_MS)) continue;
    if (lat < b.s || lat > b.n || lng < b.w || lng > b.e) continue;
    const r = routes.get(String(x.rteId));
    out.push({ key: `${x.rteId}:${x.vhclNo}`, routeId: reg.prefix ? reg.prefix + x.rteId : null, city: reg.city, no: str(x.rteNo) || (r && str(r.rteNo)) || "", type: (r && str(r.rteType)) || null, lat, lng });
  }
  return out;
}

// ── 다음 저상버스·도착 알림·버스 몰림·막차 뒤 (2026-11-25) ──
/** 다음 저상버스: 지나가지 않은 도착 중 첫 차가 저상이 아닐 때 그 뒤 첫 저상버스. 저상이 하나도 없으면 false, 해당 없음(첫 차가 저상·차 종류 모름)이면 null */
export function nextLow(arr, elapsed = 0) {
  const live = (arr || []).filter((a) => !passed(a, elapsed));
  if (!live.length || isLowArrival(live[0]) !== false) return null;
  return live.find((a) => isLowArrival(a)) || false;
}
/** 도착 알림 단계: 2 = 곧 도착(90초·1정류장 안), 1 = 다가옴(5분·3정류장 안), 0 = 아직. 기점에서 출발 대기 중인 차는 0 */
export function arriveStage(a, elapsed = 0) {
  if (!a || a.waiting) return 0;
  const sec = a.arrSeconds != null ? a.arrSeconds - elapsed : null, n = a.prevStops;
  if ((sec != null && sec <= 90) || (n != null && n <= 1)) return 2;
  if ((sec != null && sec <= 300) || (n != null && n <= 3)) return 1;
  return 0;
}
/** 노선 첫 정류장부터 각 정류장까지 걸리는 분 (offsetMinutes 와 같은 어림) */
export function cumMinutes(stops) {
  const out = [0];
  for (let k = 0; k < stops.length - 1; k++) {
    const a = stops[k], b = stops[k + 1];
    out.push(out[k] + (a.lat != null && b.lat != null ? meters(a.lat, a.lng, b.lat, b.lng) / EST_M_PER_MIN : 0) + EST_STOP_MIN);
  }
  return out;
}
/**
 * 버스 몰림·빈 구간: 버스들의 노선 정류장 순번(idxs) → 이웃한 두 버스 사이 걸리는 분.
 * bunched: 2분 안에 붙어 다니는 쌍 [{i, j, min}] (i 가 뒤차), gap: 가장 긴 간격 {i, j, min} (15분 넘고 배차 간격의 2배 넘을 때만, 아니면 null)
 */
export function busSpacing(stops, idxs, interval = null) {
  const xs = (idxs || []).filter((i) => i >= 0 && i < stops.length).sort((a, b) => a - b);
  if (xs.length < 2) return { bunched: [], gap: null };
  const cum = cumMinutes(stops), bunched = [];
  let gap = null;
  for (let k = 0; k < xs.length - 1; k++) {
    const i = xs[k], j = xs[k + 1], min = cum[j] - cum[i];
    if (min <= 2) bunched.push({ i, j, min });
    if (!gap || min > gap.min) gap = { i, j, min };
  }
  if (gap && !(gap.min > 15 && (!(interval > 0) || gap.min > interval * 2))) gap = null;
  return { bunched, gap };
}
/** 첫차~막차 안인지 (막차가 자정을 넘으면 넘긴 만큼도, 03시 넘어 끝나는 심야 노선 포함) */
function runsAt(s, n) {
  const f = toMin(s.first), l = toMin(s.last);
  if (f == null || l == null) return false;
  return l >= f ? n >= f && n <= l : n >= f || n <= l;
}
/** 첫차·막차를 아는 노선 중 지금 다니는 것 */
export const runningNow = (sums, nowMin) => (sums || []).filter((s) => s && s.first && s.last && runsAt(s, nowMin));
/** 이 정류장 노선이 다 끊겼는지: 첫차·막차를 아는 노선이 있고, 그중 지금 다니는 것이 하나도 없음 */
export function allEnded(sums, nowMin) {
  const k = (sums || []).filter((s) => s && s.first && s.last);
  return k.length > 0 && !k.some((s) => runsAt(s, nowMin));
}
