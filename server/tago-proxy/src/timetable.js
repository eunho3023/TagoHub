// 지자체 버스정보 사이트의 공식 배차시간표 (노선별 기점 출발 시각 전체). 공공데이터 API 에는 첫차·막차·배차 간격만 있어
// 정류장 시간표를 더 정확하게 만들려고 씀. 노선 ID 는 TAGO 노선 ID 의 숫자 9자리와 같음.
// GET /w/bus/time/<지역>/<노선 9자리> → {weekday, sat, holi:[분...], weekdayDown?, satDown?, holiDown?, note, source} (하루 저장)
//  - 전주 its.jeonju.go.kr, 울산 its.ulsan.kr, 천안 its.cheonan.go.kr(클라우드 접속 막힘 → 배포 때 받음), 창원 bus.changwon.go.kr,
//    대구 businfo.daegu.go.kr(주요 정류장 통과 시각), 제주 bus.jeju.go.kr(엑셀), 진주 bis.jinju.go.kr, 구미 bis.gumi.go.kr
//  - …Down 은 종점에서 출발하는 시각 (양쪽 끝에서 출발하는 노선)

import { readXlsx } from "./xlsx.js";

const HEAD = { "Access-Control-Allow-Origin": "*", "Content-Type": "application/json; charset=utf-8" };
const TTL = 86400;
const UA = { "User-Agent": "Mozilla/5.0 (compatible; TagoHub/1.0)", "Accept-Language": "ko-KR,ko;q=0.9" };

/** "06:00, 06:15" 또는 ["0600", "06:15"] → [360, 375] (이상한 값은 버림, 정렬·중복 제거) */
export function clockList(s) {
  const out = new Set();
  for (const t of Array.isArray(s) ? s : String(s || "").split(",")) {
    const m = /^\s*(\d{1,2}):?(\d\d)\b/.exec(String(t ?? ""));
    if (m && +m[1] < 30 && +m[2] < 60) out.add(+m[1] * 60 + +m[2]);
  }
  return [...out].sort((a, b) => a - b);
}

/** 전주 selectBisRouteTimeInfo 응답 → 요일별 기점 출발 시각. SAT_NLIST·HOLI_NLIST 는 토요일·일요일(공휴일)에 다니지 않는 시각 */
export function parseJeonjuTime(j) {
  const r = j && j.result;
  if (!r) return null;
  const all = clockList(r.COURSE_STIMELIST || (Array.isArray(j.timeList) ? j.timeList.join(",") : ""));
  if (!all.length) return null;
  const satOff = new Set(clockList(r.SAT_NLIST)), holiOff = new Set(clockList(r.HOLI_NLIST));
  return {
    weekday: all,
    sat: all.filter((m) => !satOff.has(m)),
    holi: all.filter((m) => !holiOff.has(m)),
    note: typeof r.BRT_TEXT === "string" ? r.BRT_TEXT.slice(0, 200) : null,
    source: "전주시 버스정보시스템",
  };
}

/** 천안 showBusTimeTable.do 화면 → 평일·주말·공휴일 출발 시각 (<dt>… 출발시간</dt> 다음 <dd>시각</dd> 들) */
export function parseCheonanHtml(html) {
  const part = (label) => {
    const i = html.indexOf(`<dt>${label} 출발시간</dt>`);
    if (i < 0) return [];
    const rest = html.slice(i + 10);
    const end = rest.indexOf("<dt>");
    return clockList([...(end < 0 ? rest : rest.slice(0, end)).matchAll(/<dd>\s*(\d{1,2}:\d\d)/g)].map((m) => m[1]));
  };
  const weekday = part("평일");
  if (!weekday.length) return null;
  const sat = part("주말"), holi = part("공휴일");
  return { weekday, sat: sat.length ? sat : weekday, holi: holi.length ? holi : sat.length ? sat : weekday, note: null, source: "천안시 버스정보시스템" };
}

/** 창원 getTimeTable.do 응답 3개(평일·토·공휴일) → 기점(updnSe 0)·종점(1) 출발 시각 */
export function parseChangwon(days) {
  const pick = (j, up) => clockList(((j && j.rows) || []).filter((r) => String(r.updnSe) === (up ? "0" : "1")).map((r) => r.startTime));
  const [w, s, h] = days;
  const weekday = pick(w, true);
  if (!weekday.length) return null;
  const out = { weekday, sat: pick(s, true), holi: pick(h, true), note: null, source: "창원시 버스정보시스템" };
  const wd = pick(w, false);
  if (wd.length) Object.assign(out, { weekdayDown: wd, satDown: pick(s, false), holiDown: pick(h, false) });
  return out;
}

/** 울산 getBusTimeTableInfo 응답 → 기점(또는 순환) 출발 시각 */
export function parseUlsan(j, circular) {
  const row = j && Array.isArray(j.rows) ? j.rows[0] : null;
  if (!row) return [];
  return clockList(((circular ? row.circularInfo : row.fiducialInfo) || []).map((x) => x.bttStarttime));
}

const post = (fetcher, url, body, type = "application/x-www-form-urlencoded; charset=UTF-8") =>
  fetcher(url, { method: "POST", headers: { ...UA, "Content-Type": type, Accept: "application/json, text/html" }, body });

async function jeonju(id, fetcher) {
  const r = await post(fetcher, "https://its.jeonju.go.kr/bis/selectBisRouteTimeInfo.do", `locale=ko&routeId=${id}`);
  return r.ok ? parseJeonjuTime(await r.json()) : null;
}

async function cheonan(id, fetcher) {
  const r = await post(fetcher, "https://its.cheonan.go.kr/bis/getRouteList.do", `searchKeyword=${id}`);
  if (!r.ok) return null;
  const it = ((await r.json()).resultList || []).find((x) => String(x.ROUTE_ID) === id);
  if (!it) return null;
  const q = new URLSearchParams({ routeName: it.ROUTE_NAME, routeDirection: it.ROUTE_DIRECTION, relayAreacode: it.RELAY_AREACODE, routeExplain: "", stName: "", edName: "",
    fstTime: it.FST_TIME || "", lstTime: it.LST_TIME || "", routeDLength: it.ROUTE_DLENGTH ?? "", avgTime: it.AVG_TIME ?? "" });
  const h = await fetcher("https://its.cheonan.go.kr/bis/showBusTimeTable.do?" + q, { headers: UA });
  return h.ok ? parseCheonanHtml(await h.text()) : null;
}

async function changwon(id, fetcher) {
  const days = await Promise.all([1, 2, 3].map((d) => fetcher(`https://bus.changwon.go.kr/search/getTimeTable.do?routeId=${id}&day=${d}`, { headers: UA })
    .then((r) => (r.ok ? r.json() : null)).catch(() => null)));
  return parseChangwon(days);
}

async function ulsan(id, fetcher) {
  const J = "application/json";
  const list = await post(fetcher, "https://its.ulsan.kr/mpng/getList.json", JSON.stringify({ postData: { serviceName: "routeBusService", methodName: "getOptionList" } }), J);
  if (!list.ok) return null;
  // brtNo = "노선ID:번호:구분:?:방향(3 순환)"
  const opt = ((await list.json()).rows || []).find((x) => String(x.brtNo || "").split(":")[0] === id);
  if (!opt) return null;
  const [, , cls, , dir] = String(opt.brtNo).split(":");
  const no = String(opt.bnodeName || "").split("(")[0];
  const day = (v) => post(fetcher, "https://its.ulsan.kr/mpng/getList.json", JSON.stringify({ postData: { serviceName: "routeBusService", methodName: "getBusTimeTableInfo", brtId: id, brtNo: no, dywkVal: v, brtClass: cls, bttDirection: dir } }), J)
    .then((r) => (r.ok ? r.json() : null)).then((j) => parseUlsan(j, dir === "3")).catch(() => []);
  const [weekday, sat, holi] = await Promise.all([day(0), day(7), day(1)]);
  return weekday.length ? { weekday, sat, holi, note: null, source: "울산광역시 교통정보센터" } : null;
}

/**
 * 대구 운행표 HTML(TimeTable) → {points: 주요 정류장 이름, trips: [[분|null...]...]} (회차별 주요 정류장 통과 시각).
 * 칸은 "&nbsp;"(안 지남), "05:45", "다른 기점 이름<br />05:30" 모양이라 칸의 마지막 시각을 씀
 */
export function parseDaeguTable(html) {
  const t = String(html || "");
  const head = /<thead>([\s\S]*?)<\/thead>/.exec(t);
  if (!head) return null;
  const points = [...head[1].matchAll(/<th[^>]*>([\s\S]*?)<\/th>/g)].map((m) => m[1].replace(/<[^>]+>/g, "").trim()).slice(1);
  if (points.length < 2) return null;
  const trips = [];
  for (const row of t.matchAll(/<tr>([\s\S]*?)<\/tr>/g)) {
    const cells = [...row[1].matchAll(/<td[^>]*>([\s\S]*?)<\/td>/g)].map((m) => m[1]).slice(1);
    if (cells.length !== points.length) continue;
    const mins = cells.map((c) => { const all = [...c.matchAll(/(\d{1,2}):(\d\d)/g)]; const m = all[all.length - 1]; return m && +m[1] < 30 ? +m[1] * 60 + +m[2] : null; });
    if (mins.some((x) => x != null)) trips.push(mins);
  }
  return trips.length ? { points, trips } : null;
}

/** 오늘(한국 시각)부터 가장 가까운 평일·토요일 날짜 yyyyMMdd */
function nextDays(nowMs = Date.now()) {
  const out = {};
  for (let k = 0; k < 8; k++) {
    const d = new Date(nowMs + 9 * 3600000 + k * 86400000), w = d.getUTCDay();
    const key = w === 6 ? "sat" : w === 0 ? "holi" : "weekday";
    if (!out[key]) out[key] = d.toISOString().slice(0, 10).replace(/-/g, "");
  }
  return out;
}

async function daegu(id, fetcher, no) {
  if (!no) return null;
  const B = "https://businfo.daegu.go.kr:8095/dbms_web_api/busInfo/";
  const dates = nextDays();
  const day = async (svcDt) => {
    const s = await fetcher(`${B}schedule?routeNo=${encodeURIComponent(no)}&svcDt=${svcDt}`, { headers: UA });
    if (!s.ok) return null;
    const dTCd = (await s.json())?.body?.dTCd;
    if (!dTCd) return null;
    const l = await fetcher(`${B}scheduleList?routeNo=${encodeURIComponent(no)}&routeId=${id}&dTCd=${dTCd}&svcDt=${svcDt}`, { headers: UA });
    return l.ok ? parseDaeguTable((await l.json())?.body?.TimeTable) : null;
  };
  // 대구는 토·일·공휴일이 같은 시간표 (dTCd 2)
  const [w, h] = await Promise.all([day(dates.weekday), day(dates.sat)]);
  if (!w) return null;
  const first = (tb) => (tb ? clockList(tb.trips.map((r) => r.find((x) => x != null)).filter((x) => x != null).map((m) => `${Math.floor(m / 60)}:${String(m % 60).padStart(2, "0")}`)) : []);
  return { weekday: first(w), sat: first(h || w), holi: first(h || w), tables: [{ days: ["weekday"], ...w }, { days: ["sat", "holi"], ...(h || w) }], note: null, source: "대구광역시 버스정보시스템" };
}

/**
 * 제주 시간표 엑셀 시트들 → 이 노선 번호(no)의 표 [{name, days, points, trips}].
 * "노선번호" 칸이 있는 줄이 머리(그 뒤가 주요 정류장), 그 아래 줄 중 노선번호가 no 인 줄이 회차. "X" 는 안 지남
 */
export function parseJejuSheets(sheets, no) {
  const base = String(no || "").replace(/번$/, "");
  const same = (cell) => { const c = String(cell || "").replace(/\s|번/g, ""); return c === base || c.split(/[,/]/).includes(base); };
  const out = [];
  for (const sh of sheets) {
    const h = sh.rows.findIndex((r) => r.some((c) => String(c).replace(/\s/g, "") === "노선번호"));
    if (h < 0) continue;
    const col = sh.rows[h].findIndex((c) => String(c).replace(/\s/g, "") === "노선번호");
    const points = sh.rows[h].slice(col + 1).map((c) => String(c).replace(/\s+/g, ""));
    const trips = [];
    for (const r of sh.rows.slice(h + 1)) {
      if (!same(r[col])) continue;
      const mins = points.map((_, k) => { const m = /(\d{1,2}):(\d\d)/.exec(String(r[col + 1 + k] || "")); return m && +m[1] < 30 ? +m[1] * 60 + +m[2] : null; });
      if (mins.some((x) => x != null)) trips.push(mins);
    }
    if (!trips.length || points.length < 2) continue;
    const nm = sh.name || "";
    const days = /주말|토|휴일|공휴/.test(nm) && !/평일/.test(nm) ? ["sat", "holi"] : /평일/.test(nm) ? ["weekday"] : ["weekday", "sat", "holi"];
    out.push({ name: nm, days, points, trips });
  }
  // 시트 이름이 이 번호로 시작하는 표가 있으면 그것만 (같은 회차가 다른 번호 시트에도 섞여 있음)
  const own = out.filter((t) => { const n = String(t.name).replace(/\s/g, ""); return n.startsWith(base) && !/[0-9-]/.test(n[base.length] || ""); });
  return own.length ? own : out;
}

async function jeju(id, fetcher, no) {
  if (!no) return null;
  const base = String(no).replace(/번$/, "");
  const lists = await Promise.all([1, 2, 3, 4, 5, 6, 7, 8].map((t) => post(fetcher, "https://bus.jeju.go.kr/publicTrafficInformation/getBusRouteNum", `GROUTE_TYPE=${t}`)
    .then((r) => (r.ok ? r.json() : [])).catch(() => [])));
  const groups = lists.flat().filter((g) => String(g.GSCHEDULE_NM || "").split(/[,/]/).map((x) => x.replace(/\(.*\)|\s/g, "")).includes(base));
  for (const g of groups.slice(0, 3)) {
    const r = await fetcher(`https://bus.jeju.go.kr/data/schedule/downScheduleExcel?gscheduleId=${encodeURIComponent(g.GSCHEDULE_ID)}`, { headers: UA });
    if (!r.ok) continue;
    let tables;
    try { tables = parseJejuSheets(await readXlsx(await r.arrayBuffer()), base); } catch (_) { continue; }
    if (!tables.length) continue;
    const firsts = (k) => clockList(tables.filter((t) => t.days.includes(k)).flatMap((t) => t.trips.map((tr) => tr.find((x) => x != null))).filter((x) => x != null).map((m) => `${Math.floor(m / 60)}:${String(m % 60).padStart(2, "0")}`));
    return { weekday: firsts("weekday"), sat: firsts("sat"), holi: firsts("holi"), tables, note: null, source: "제주특별자치도 버스정보시스템" };
  }
  return null;
}

/**
 * 진주: 노선 번호별 회차표에 갈래 노선(경유지만 다른 노선 ID)이 섞여 있어, 이 갈래의 첫차~막차 안에 들고
 * 다른 갈래의 한 번뿐인 회차가 아닌 것만 고름
 */
export function pickJinju(times, me, group) {
  const f = clockList([me.firsttime])[0], l = clockList([me.lasttime])[0];
  if (f == null) return [];
  if (f === l) return times.includes(f) ? [f] : [f];
  const others = new Set(group.filter((g) => g.brt_id !== me.brt_id && g.firsttime && g.firsttime === g.lasttime).flatMap((g) => clockList([g.firsttime])));
  return times.filter((t) => t >= f && t <= (l ?? 1e9) && !others.has(t));
}

async function jinju(id, fetcher) {
  const list = await post(fetcher, "https://bis.jinju.go.kr/addInfo/getBusTime.do", "btt_type=1");
  if (!list.ok) return null;
  const rows = (await list.json()).rows || [];
  const me = rows.find((r) => String(r.brt_id) === id);
  if (!me) return null;
  const group = rows.filter((r) => r.brt_no === me.brt_no);
  const day = (t) => post(fetcher, "https://bis.jinju.go.kr/addInfo/getBusDetail.do", `brt_no=${encodeURIComponent(me.brt_no)}&brt_id=${id}&btt_type=${t}&brt_direction=0`)
    .then((r) => (r.ok ? r.json() : null)).then((j) => pickJinju(clockList(((j && j.timeTable) || []).map((d) => d.btt_starttime)), me, group)).catch(() => []);
  // 진주 요일 구분: 1 평일, 3 토요일, 4 일요일(공휴일)
  const [weekday, sat, holi] = await Promise.all([day(1), day(3), day(4)]);
  return weekday.length ? { weekday, sat, holi, note: null, source: "진주시 버스정보시스템" } : null;
}

async function gumi(id, fetcher) {
  // 구미: bType 0 평일, 2 주말·공휴일
  const day = (b) => post(fetcher, "https://bis.gumi.go.kr/localbus/getTimetableInfo", `routeId=${id}&bType=${b}`)
    .then((r) => (r.ok ? r.json() : null)).then((j) => clockList(((j && j.rows) || []).map((x) => x.starttime))).catch(() => []);
  const [weekday, holi] = await Promise.all([day(0), day(2)]);
  return weekday.length ? { weekday, sat: holi.length ? holi : weekday, holi: holi.length ? holi : weekday, note: null, source: "구미시 버스정보시스템" } : null;
}

const AREAS = { jeonju, ulsan, cheonan, changwon, daegu, jeju, jinju, gumi };

export async function handleBusTime(area, id, ctx, fetcher = fetch, no = null) {
  if (!AREAS[area] || !/^\d{1,12}$/.test(id || "")) return new Response(JSON.stringify({ error: "bad id" }), { status: 400, headers: HEAD });
  if (no != null && !/^[0-9A-Za-z가-힣()-]{1,12}$/.test(no)) no = null;
  const key = new Request(`https://cache.tagohub/bus/time/${area}/${id}${no ? "?no=" + encodeURIComponent(no) : ""}`);
  const hit = await caches.default.match(key);
  if (hit) return new Response(hit.body, { headers: { ...HEAD, "X-Cache": "HIT" } });
  let out = null;
  try { out = await AREAS[area](id, fetcher, no); } catch (_) { /* 없음 */ }
  const body = JSON.stringify(out || { none: true });
  const ttl = out ? TTL : 3600;
  ctx.waitUntil(caches.default.put(key, new Response(body, { headers: { "Cache-Control": `public, max-age=${ttl}` } })));
  return new Response(body, { headers: { ...HEAD, "X-Cache": "MISS" } });
}

/** 예전 주소 (/w/bus/jeonju/time/<id>) */
export const handleJeonjuTime = (id, ctx, fetcher = fetch) => handleBusTime("jeonju", id, ctx, fetcher);
