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

const SAME_SPOT_M = 30, SAME_NO_M = 500, BORROW_M = 120;
export function sameStop(a, b) {
  const d = meters(a.lat, a.lng, b.lat, b.lng);
  if (a.no && a.no === b.no && d <= SAME_NO_M) return true;
  // 같은 지역에서 번호가 다르면 다른 정류장 (길 건너 맞은편 정류장은 이름이 같고 20~30m 떨어져 있음)
  if (a.city === b.city && a.no && b.no && a.no !== b.no) return false;
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
  if (!m || m.no == null) return ggId(id) ? ggRoute(id) : city && /^\d+$/.test(city) ? tagoRoute(id, city) : null;
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

/** 경기 노선 (경기 GBIS 정류장 목록, 서버 하루 저장): 좌표·순번, 회차점까지 상행 */
async function ggRoute(id) {
  const rows = findRows(JSON.parse(await getText("w/bus/gg/stops/" + ggId(id))), "stationId")
    .map((r) => ({ seq: int(r.stationSeq) || 0, id: r.stationId != null ? "GGB" + r.stationId : null, name: String(r.stationName ?? ""), lat: num(r.y), lng: num(r.x), turn: String(r.turnYn || "").toUpperCase() === "Y", no: str(r.mobileNo) }))
    .sort((a, b) => a.seq - b.seq);
  if (!rows.length) return null;
  const t = rows.findIndex((r) => r.turn);
  const stops = rows.map((r, i) => ({ ...r, upDown: t < 0 ? null : i <= t ? "0" : "1" }));
  const start = stops[0].name, end = t >= 0 ? stops[t].name : stops[stops.length - 1].name;
  return { id, no: "노선", start, end, city: "31", region: "경기", type: null, sum: null, stops };
}

/** 정류장에 서는 노선 요약 (같은 자리 정류장 것까지) */
export async function stopRoutes(stop) {
  const same = await sameSpot(stop);
  const ids = [...new Set([stop, ...same].flatMap((s) => s.routes))];
  const sums = await routeSums(ids);
  const seen = new Set();
  const out = Object.values(sums).map((s) => ({ ...s, id: currentId(s.id) })).filter((s) => !seen.has(s.id) && seen.add(s.id));
  // 같은 번호라도 본선·지선·분선처럼 기점·종점이 다르면 다른 노선 → 번호가 아니라 노선 ID(없으면 번호+기점+종점)로 구분
  const sig = (s) => [s.no, (s.start || "").replace(/ /g, ""), (s.end || "").replace(/ /g, "")].join("|");
  const seenSig = new Set(out.map(sig));
  // 자료 조각에 요약이 없는 노선이 있으면 (경기·전주 등) 정류장을 지나는 노선을 서버로 물어 채움 (운행 안 하는 노선도 보이게)
  if (!ids.length || ids.some((id) => !sums[id])) {
    for (const s of await liveStopRoutes(stop, same).catch(() => [])) {
      // 자료 파일 노선과 겹치는지만 번호+기점+종점으로 봄 (받아 온 노선끼리는 ID 가 다르면 경유지만 다른 다른 노선)
      if (seen.has(s.id) || seenSig.has(sig(s))) continue;
      seen.add(s.id); out.push(s);
    }
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
      const no = String(r.brtid ?? "");
      return { id: "JUB" + (r.brtstdid || ""), no, start: str(r.brtsname), end: str(r.brtename), type: r.brtclass === "1" ? "순환" : /^\d{4}/.test(no) ? "간선" : "일반", city: "35010" };
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
export async function shape(routeId, city, stops, no) {
  // 앱과 같은 실제 경로 자료(서울 TBIS·경기 GBIS 경로이력)가 있으면 그것 (자료 조각 shapes/).
  // 단 정류장과 잘 맞을 때만 (90% 가 선에서 30m 안): 경기 자료는 노선 일부만 덮은 것도 있어서
  try {
    const legacy = legacyId(routeId);
    const poly = (await cached(`data/shapes/${bucket(legacy)}.json`).catch(() => ({})))[legacy];
    const pts = poly ? decodePolyline(String(poly), 5) : [];
    if (pts.length >= 2 && (!stops || fitsStops(pts, stops))) return pts;
  } catch (e) { /* 서버에서 */ }
  try {
    // v=5: 노선 번호를 주면 서버가 카카오 경로(앱과 같은 방법)로. 골목으로 돌아 들어가지 않게 다시 만든 모양 (브라우저에 저장된 예전 모양을 쓰지 않게)
    const poly = (await getJson("w/shape/" + legacyId(routeId), /^\d+$/.test(city || "") ? { city, v: 7, no: no || undefined } : { v: 7, no: no || undefined })).poly;
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
  // 경기 밖에 있는 경기 버스 정류장(인천·서울·청주 등의 GGB): 그 지역 시내버스 정류장이 조금 떨어져 따로 있어 가까운 것 하나씩 함께 봄
  const borrowed = stop.id.startsWith("GGB") && !stop.city.startsWith("31");
  for (const o of near) {
    if (o.id === stop.id) continue;
    const m = meters(stop.lat, stop.lng, o.lat, o.lng);
    if (borrowed && !o.id.startsWith("GGB") && o.routes.length && m <= BORROW_M) {
      const k = "near|" + o.id.slice(0, 3), best = groups.get(k);
      if (!best || meters(stop.lat, stop.lng, best.lat, best.lng) > m) groups.set(k, o);
      continue;
    }
    if (!((stop.no && o.no === stop.no && m <= SAME_NO_M) || (o.city !== stop.city && m <= SAME_SPOT_M))) continue;
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
    if (ggId(s.id) && ggCity(s.city)) metro.add("w/bus/gg/arrival/" + ggId(s.id));
  }
  return { tago: out.slice(0, 5), metro: [...metro].slice(0, 3) };
}

async function tagoArrivals(city, node) {
  const rows = xmlRows(await getText("w/dg/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList", { cityCode: city, nodeId: currentId(node) }));
  return rows.map((r) => ({ routeId: r.routeid || "", routeNo: r.routeno || "", routeType: str(r.routetp), vehicleType: str(r.vehicletp), prevStops: int(r.arrprevstationcnt), arrSeconds: int(r.arrtime), city }));
}

/** 정류장 실시간 도착: {state: "ok"|"unsupported"|"failed", list, at} */
/** 전주시 버스정보 정류장 도착 (줄 이름 <list>, RTime 초, RStop 남은 정류장) */
async function jeonjuArrivals(id) {
  const x = await getText("w/bus/jeonju/arrival/" + id.slice(3));
  return [...new DOMParser().parseFromString(x, "text/xml").getElementsByTagName("list")].map((el) => {
    const r = {};
    for (const c of el.children) r[c.tagName.toLowerCase()] = c.textContent.trim();
    const no = String(r.brtid ?? "");
    return { routeId: "JUB" + (r.brtstdid || ""), routeNo: no, routeType: r.brtclass === "1" ? "순환" : /^\d{4}/.test(no) ? "간선" : "일반",
      vehicleType: r.buslow === "1" ? "저상버스" : null, prevStops: int(r.rstop), arrSeconds: int(r.rtime), city: "35010",
      towards: str((r.brtvianame || "").split("->")[1]) };
  }).filter((a) => a.routeNo && a.arrSeconds != null);
}

export async function arrivals(stop) {
  try {
    const t = await targets(stop);
    // 전주 정류장: 전주시 버스정보 도착 (앱과 같은 자료). 받으면 그 정류장의 TAGO 도착은 쓰지 않음
    const jeonju = [...new Set([stop, ...(await sameSpot(stop))].map((s) => (/^JUB\d{9}$/.test(s.id) ? s.id : null)).filter(Boolean))];
    if (!t.tago.length && !t.metro.length && !jeonju.length) return { state: "unsupported", list: [], at: Date.now() };
    const ju = await Promise.allSettled(jeonju.map((id) => jeonjuArrivals(id)));
    const jeonjuOk = new Set(jeonju.filter((_, i) => ju[i].status === "fulfilled"));
    const res = [...ju, ...(await Promise.allSettled([
      ...t.tago.filter(([, id]) => !jeonjuOk.has(id)).map(([c, id]) => tagoArrivals(c, id)),
      ...t.metro.map(async (p) => { const b = await getText(p); return p.includes("/seoul/") ? seoulArrivals(b) : ggArrivals(b); }),
    ]))];
    if (res.every((r) => r.status === "rejected")) throw res[0].reason;
    const seen1 = new Set(), seen2 = new Set();
    const list = res.flatMap((r) => (r.status === "fulfilled" ? r.value : []))
      .filter((a) => { const k = a.routeId + "|" + a.arrSeconds; return !seen1.has(k) && seen1.add(k); })
      // 같은 버스가 두 곳(시군)에서 다른 노선 ID 로 올 때만 하나로: 번호·분·남은 정류장이 다 같을 때 (본선·지선이 같은 분에 와도 남겨 둠)
      .filter((a) => { const k = a.routeNo + "|" + Math.floor((a.arrSeconds ?? -60) / 60) + "|" + (a.prevStops ?? ""); return !seen2.has(k) && seen2.add(k); })
      .sort((a, b) => (a.arrSeconds ?? 1e9) - (b.arrSeconds ?? 1e9));
    return { state: "ok", list, at: Date.now() };
  } catch (e) {
    return { state: "failed", list: [], at: Date.now(), why: String(e.message || e) };
  }
}

// ── 버스 위치로 도착 정보 고치기 (앱 PassedByPosition 과 같은 규칙) ──
// 도착 예측은 늦게 갱신되고 버스 위치가 더 새로운 경우가 많아서: 막 지나간 버스는 빼고, 위치가 더 가까우면 남은 정류장·시간을 줄임
const APPROACH_STOPS = 6, JUST_PASSED_STOPS = 2, MAX_ROUTES = 6;
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
  await Promise.all([...firsts.values()].slice(0, MAX_ROUTES).map(async (a) => {
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
