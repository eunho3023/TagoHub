// 지자체 버스정보 사이트의 공식 배차시간표 (노선별 기점 출발 시각 전체). 공공데이터 API 에는 첫차·막차·배차 간격만 있어
// 정류장 시간표를 더 정확하게 만들려고 씀. 노선 ID 는 TAGO 노선 ID 의 숫자 9자리와 같음.
// GET /w/bus/time/<지역>/<노선 9자리> → {weekday, sat, holi:[분...], weekdayDown?, satDown?, holiDown?, note, source} (하루 저장)
//  - 전주 its.jeonju.go.kr, 울산 its.ulsan.kr, 천안 its.cheonan.go.kr, 창원 bus.changwon.go.kr
//  - …Down 은 종점에서 출발하는 시각 (양쪽 끝에서 출발하는 노선)

const HEAD = { "Access-Control-Allow-Origin": "*", "Content-Type": "application/json; charset=utf-8" };
const TTL = 86400;
const UA = { "User-Agent": "Mozilla/5.0 (compatible; TagoHub/1.0)" };

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

const AREAS = { jeonju, ulsan, cheonan, changwon };

export async function handleBusTime(area, id, ctx, fetcher = fetch) {
  if (!AREAS[area] || !/^\d{9}$/.test(id || "")) return new Response(JSON.stringify({ error: "bad id" }), { status: 400, headers: HEAD });
  const key = new Request(`https://cache.tagohub/bus/time/${area}/${id}`);
  const hit = await caches.default.match(key);
  if (hit) return new Response(hit.body, { headers: { ...HEAD, "X-Cache": "HIT" } });
  let out = null;
  try { out = await AREAS[area](id, fetcher); } catch (_) { /* 없음 */ }
  const body = JSON.stringify(out || { none: true });
  const ttl = out ? TTL : 3600;
  ctx.waitUntil(caches.default.put(key, new Response(body, { headers: { "Cache-Control": `public, max-age=${ttl}` } })));
  return new Response(body, { headers: { ...HEAD, "X-Cache": "MISS" } });
}

/** 예전 주소 (/w/bus/jeonju/time/<id>) */
export const handleJeonjuTime = (id, ctx, fetcher = fetch) => handleBusTime("jeonju", id, ctx, fetcher);
