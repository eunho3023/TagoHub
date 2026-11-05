// 버스 자료 (Compose 웹 WebRepo.kt·MetroBus.kt 와 같은 규칙).
// 정류장 찾기는 서버 이름 조각(/data/names), 도착은 웹 통로(/w/dg/1613000 TAGO, /w/bus/seoul|gg 서울·경기).
import { cached, getText, getJson, xmlRows, findRows, num, int, str, meters } from "./core.js";

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

/** 조각 한 줄 → 정류장 {id, name, lat, lng, no, city, routes} */
function stopOf(a) {
  if (!Array.isArray(a) || a[0] == null) return null;
  const lat = num(a[2]), lng = num(a[3]);
  if (lat == null || lng == null) return null;
  return { id: String(a[0]), name: String(a[1] ?? ""), lat, lng, no: str(a[4]) && a[4] !== "null" ? String(a[4]) : null, city: String(a[5] ?? ""), routes: (a[6] || []).map(String) };
}
const parseStops = (rows) => (Array.isArray(rows) ? rows.map(stopOf).filter(Boolean) : []);

async function tile(name) { try { return parseStops(await cached(`data/tiles/${name}.json`)); } catch (e) { return []; } }

const SAME_SPOT_M = 30, SAME_NO_M = 500;
export function sameStop(a, b) {
  const d = meters(a.lat, a.lng, b.lat, b.lng);
  if (a.no && a.no === b.no && d <= SAME_NO_M) return true;
  return a.name === b.name && d <= SAME_SPOT_M;
}
/** 같은 이름·같은 자리 정류장 묶기 (노선이 가장 많은 것 하나만) */
function mergeSameSpot(stops) {
  const ordered = [...stops].sort((a, b) => b.routes.length - a.routes.length || (a.city.startsWith("TS") ? 1 : 0) - (b.city.startsWith("TS") ? 1 : 0));
  const out = [];
  for (const s of ordered) if (!out.some((o) => sameStop(o, s))) out.push(s);
  return out;
}

/** 이름에 검색어가 든 정류장 (이름이 검색어로 시작하는 것 먼저) */
export async function searchStops(query, limit = 60) {
  const q = query.trim();
  if (!q) return [];
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
  return mergeSameSpot(all.filter((x) => x.lat >= s && x.lat <= n && x.lng >= w && x.lng <= e &&
    (x.routes.length || x.city.startsWith("TS") || x.id.startsWith("JEB") || x.id.startsWith("JUB")))).slice(0, limit);
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

/** 노선 전체 (정류장 순서·좌표·시간표). 파일의 예전 id 로 찾고 지금 id 로 돌려줌.
 * 파일에 없는 노선(전주·제주·교통안전공단 등)은 [city](TAGO 도시코드)로 TAGO 노선 정류장 목록을 물음 */
export async function route(id, city) {
  const legacy = legacyId(id);
  const m = (await cached(`data/routes/${bucket(legacy)}.json`).catch(() => ({})))[legacy];
  if (!m || m.no == null) return city && /^\d+$/.test(city) ? tagoRoute(id, city) : null;
  const info = Array.isArray(m.info) ? m.info : null;
  const sum = info ? { id: legacy, no: String(m.no), start: str(m.start), end: str(m.end), type: str(m.rtype), first: str(info[0]), last: str(info[1]), interval: int(info[2]), intervalSat: int(info[3]), intervalSun: int(info[4]) } : null;
  const stops = (m.stops || []).map((a) => ({ seq: int(a[0]) || 0, id: str(a[1]), name: String(a[2] ?? ""), lat: num(a[3]), lng: num(a[4]), upDown: str(a[5]) })).sort((a, b) => a.seq - b.seq);
  return { id: currentId(legacy), no: String(m.no), start: str(m.start), end: str(m.end), city: String(m.city ?? ""), region: str(m.region), type: str(m.rtype), sum, stops };
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

/** 정류장에 서는 노선 요약 (같은 자리 정류장 것까지) */
export async function stopRoutes(stop) {
  const ids = [...new Set([stop, ...(await sameSpot(stop))].flatMap((s) => s.routes))];
  const sums = await routeSums(ids);
  const seen = new Set();
  return Object.values(sums).map((s) => ({ ...s, id: currentId(s.id) })).filter((s) => !seen.has(s.id) && seen.add(s.id));
}

/** 노선 도로 모양 (서버가 만들어 저장). 못 받으면 null */
export async function shape(routeId) {
  try {
    const poly = (await getJson("w/shape/" + legacyId(routeId))).poly;
    const pts = decodePolyline(String(poly), 5);
    return pts.length >= 2 ? pts : null;
  } catch (e) { return null; }
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

function seoulArrivals(body) {
  return findRows(JSON.parse(body), "arrmsg1").flatMap((r) => [1, 2].map((n) => {
    const p = parseArrMsg(r["arrmsg" + n]);
    if (!p) return null;
    return { routeId: "SEB" + r.busRouteId, routeNo: str(r.busRouteAbrv) || String(r.rtNm ?? ""), routeType: SEOUL_TYPES[r.routeType] || null,
      vehicleType: r["busType" + n] === "1" ? "저상버스" : r["busType" + n] === "2" ? "굴절버스" : null, prevStops: p[1], arrSeconds: p[0], towards: str(r.adirection) };
  }).filter(Boolean));
}
function ggArrivals(body) {
  return findRows(JSON.parse(body), "routeId").flatMap((r) => {
    if (String(r.flag || "").toUpperCase() === "STOP") return [];
    return [1, 2].map((n) => {
      const sec = int(r["predictTimeSec" + n]) ?? (int(r["predictTime" + n]) != null ? int(r["predictTime" + n]) * 60 : null);
      if (sec == null) return null;
      return { routeId: "GGB" + r.routeId, routeNo: String(r.routeName ?? ""), routeType: str(r.routeTypeName) || GG_TYPES[int(r.routeTypeCd)] || null,
        vehicleType: GG_VEHICLE[int(r["lowPlate" + n])] || null, prevStops: int(r["locationNo" + n]), arrSeconds: sec, towards: str(r.routeDestName) };
    }).filter(Boolean);
  });
}

// ── 도착 ──
/** 같은 자리의 다른 시군 정류장 (시군마다 가장 가까운 하나) */
export async function sameSpot(stop) {
  const d = 0.005;
  const near = (await Promise.all(tileNames(stop.lat - d, stop.lng - d, stop.lat + d, stop.lng + d).map(tile))).flat();
  const groups = new Map();
  for (const o of near) {
    if (o.id === stop.id) continue;
    const m = meters(stop.lat, stop.lng, o.lat, o.lng);
    if (!((stop.no && o.no === stop.no && m <= SAME_NO_M) || (o.city !== stop.city && m <= SAME_SPOT_M))) continue;
    const k = o.city + "|" + (o.no || o.id);
    const best = groups.get(k);
    if (!best || meters(stop.lat, stop.lng, best.lat, best.lng) > m) groups.set(k, o);
  }
  return [...groups.values()];
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
    if (ggId(s.id) && ggCity(s.city)) metro.add("w/bus/gg/arrival/" + ggId(s.id));
  }
  return { tago: out.slice(0, 5), metro: [...metro].slice(0, 3) };
}

async function tagoArrivals(city, node) {
  const rows = xmlRows(await getText("w/dg/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", { cityCode: city, nodeId: currentId(node) }));
  return rows.map((r) => ({ routeId: r.routeid || "", routeNo: r.routeno || "", routeType: str(r.routetp), vehicleType: str(r.vehicletp), prevStops: int(r.arrprevstationcnt), arrSeconds: int(r.arrtime), city }));
}

/** 정류장 실시간 도착: {state: "ok"|"unsupported"|"failed", list, at} */
export async function arrivals(stop) {
  try {
    const t = await targets(stop);
    if (!t.tago.length && !t.metro.length) return { state: "unsupported", list: [], at: Date.now() };
    const res = await Promise.allSettled([
      ...t.tago.map(([c, id]) => tagoArrivals(c, id)),
      ...t.metro.map(async (p) => { const b = await getText(p); return p.includes("/seoul/") ? seoulArrivals(b) : ggArrivals(b); }),
    ]);
    if (res.every((r) => r.status === "rejected")) throw res[0].reason;
    const seen1 = new Set(), seen2 = new Set();
    const list = res.flatMap((r) => (r.status === "fulfilled" ? r.value : []))
      .filter((a) => { const k = a.routeId + "|" + a.arrSeconds; return !seen1.has(k) && seen1.add(k); })
      .filter((a) => { const k = a.routeNo + "|" + Math.floor((a.arrSeconds ?? -60) / 60); return !seen2.has(k) && seen2.add(k); })
      .sort((a, b) => (a.arrSeconds ?? 1e9) - (b.arrSeconds ?? 1e9));
    return { state: "ok", list, at: Date.now() };
  } catch (e) {
    return { state: "failed", list: [], at: Date.now(), why: String(e.message || e) };
  }
}

/** 노선 버스 위치: {state, buses:[{nodeId, ord, vehicleNo, lat, lng}]} */
export async function positions(r) {
  try {
    const sid = seoulId(r.id), gid = ggId(r.id);
    if (sid) {
      const rows = findRows(JSON.parse(await getText("w/bus/seoul/pos/" + sid)), "vehId");
      return { state: "ok", buses: rows.filter((x) => x.isrunyn !== "0").map((x) => ({ nodeId: x.lastStnId ? "SEB" + x.lastStnId : null, ord: int(x.sectOrd), vehicleNo: str(x.plainNo) || str(x.vehId), lat: num(x.gpsY), lng: num(x.gpsX) })) };
    }
    if (gid && ggCity(r.city)) {
      const rows = findRows(JSON.parse(await getText("w/bus/gg/pos/" + gid)), "plateNo");
      return { state: "ok", buses: rows.map((x) => ({ nodeId: x.stationId != null ? "GGB" + x.stationId : null, ord: int(x.stationSeq), vehicleNo: str(x.plateNo), lat: null, lng: null })) };
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
    let i = b.nodeId ? stops.findIndex((s) => s.id && currentId(s.id) === currentId(b.nodeId)) : -1;
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
  if (a.prevStops === 0 && (sec == null || sec <= 30)) return ["정류장 도착", low].filter(Boolean);
  return [timeText(sec), a.prevStops == null ? null : a.prevStops <= 0 ? "이번 정류장" : a.prevStops + "정류장 전", low].filter(Boolean);
}
/** 이미 지나간 버스 (남은 시간이 0 아래로 3초 넘게, 바로 앞이거나 이번 정류장) */
export const passed = (a, elapsed) => a.arrSeconds != null && a.arrSeconds - elapsed < -3 && (a.prevStops == null || a.prevStops <= 1);
