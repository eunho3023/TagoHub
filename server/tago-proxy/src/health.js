// 자료 제공처(API) 고장 확인: 30분마다 각 기관에 작은 요청을 하나씩 보내 되는지 D1(api_health)에 적어 둠.
// 서울 버스 API 가 앱에서 HTTP 401 로 조용히 고장 난 것을 사용자 화면을 보고서야 알았던 일 때문에 (2026-11-16).
//  GET /w/health → {at, checks: [{name, label, ok, msg, ms, at, lastOk, failSince}]} (웹 설정 화면 「자료 연결 상태」)
// 키·토큰은 응답·기록에 넣지 않음.

import { encodeKey, seoulArrivalUrl } from "./proxy.js";

const DG = "https://apis.data.go.kr/";
/** 공공데이터포털·기관이 키·한도 문제를 200 응답 안에 적어 보내는 말 */
const BAD = /SERVICE_KEY_IS_NOT|LIMITED_NUMBER_OF_SERVICE|SERVICE_ACCESS_DENIED|returnAuthMsg|SERVICE ERROR|APPLICATION_ERROR|ERROR-\d{3}|INFO-100|Unauthorized/i;

/** 사람이 읽을 이유 (키 문제·한도 초과·기타) */
export function reason(status, body) {
  const b = String(body || "");
  if (/LIMITED_NUMBER_OF_SERVICE/.test(b)) return "하루 요청 한도 초과";
  if (status === 401 || status === 403 || /SERVICE_KEY_IS_NOT|SERVICE_ACCESS_DENIED|returnAuthMsg|Unauthorized|INFO-100/i.test(b)) return "인증 실패 (키 확인 필요)";
  if (status === 429) return "요청이 너무 많음";
  if (status >= 500) return `제공처 서버 오류 (HTTP ${status})`;
  if (status !== 200) return `HTTP ${status}`;
  if (BAD.test(b)) return "제공처 오류 응답";
  return null;
}

/**
 * 확인할 곳. url(env) 가 null 이면(키 없음) 건너뜀. ok(body) 는 200 이고 오류 말이 없을 때 내용까지 맞는지
 */
export const CHECKS = [
  {
    name: "tago_arrival", label: "TAGO 버스 도착 (전국)",
    url: (e) => e.DATA_GO_KR_KEY && `${DG}1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList?serviceKey=${encodeKey(e.DATA_GO_KR_KEY)}&cityCode=35010&nodeId=JUB306101044&numOfRows=1&_type=json`,
    ok: (b) => /"resultCode"\s*:\s*"00"|<resultCode>00</.test(b),
  },
  {
    name: "tago_route", label: "TAGO 노선 정보 (전국)",
    url: (e) => e.DATA_GO_KR_KEY && `${DG}1613000/BusRouteInfoInqireService/getRouteInfoIem?serviceKey=${encodeKey(e.DATA_GO_KR_KEY)}&cityCode=35010&routeId=JUB305001764&_type=json`,
    ok: (b) => /"resultCode"\s*:\s*"00"|<resultCode>00</.test(b),
  },
  {
    name: "seoul_bus", label: "서울 버스",
    url: (e) => e.DATA_GO_KR_KEY && `http://ws.bus.go.kr/api/rest/stationinfo/getStationByUid?serviceKey=${encodeKey(e.DATA_GO_KR_KEY)}&arsId=04504&resultType=json`,
    ok: (b) => /"headerCd"\s*:\s*"0"/.test(b),
  },
  {
    name: "seoul_traffic", label: "서울 도로 소통·돌발 (TOPIS)",
    url: (e) => e.SEOUL_SUBWAY_KEY && `http://openapi.seoul.go.kr:8088/${encodeURIComponent(e.SEOUL_SUBWAY_KEY.trim())}/xml/AccInfo/1/1`,
    ok: (b) => /INFO-000|<list_total_count>/.test(b),
  },
  {
    name: "seoul_subway", label: "서울 지하철 도착",
    url: (e) => e.SEOUL_SUBWAY_KEY && seoulArrivalUrl("서울", e.SEOUL_SUBWAY_KEY).replace("/0/60/", "/0/1/"),
    ok: (b) => /"code"\s*:\s*"INFO-000"|realtimeArrivalList/.test(b),
  },
  {
    name: "gyeonggi_bus", label: "경기 버스",
    url: (e) => e.DATA_GO_KR_KEY && `${DG}6410000/busrouteservice/v2/getBusRouteInfoItemv2?serviceKey=${encodeKey(e.DATA_GO_KR_KEY)}&routeId=200000085&format=json`,
    ok: (b) => /"resultCode"\s*:\s*0\b|"resultCode"\s*:\s*"0"/.test(b),
  },
  {
    name: "jeonju_bus", label: "전주 버스",
    url: (e) => e.DATA_GO_KR_KEY && `${DG}4641000/nosun/bus_nosun_drawing_common?serviceKey=${encodeKey(e.DATA_GO_KR_KEY)}&brtStdid=305001764`,
    ok: (b) => /<code[^>]*>000<\/code>/.test(b),
  },
  {
    name: "ulsan_bus", label: "울산 버스",
    url: (e) => e.DATA_GO_KR_KEY && `${DG}6310000/bis/getBusArrivalInfo?serviceKey=${encodeKey(e.DATA_GO_KR_KEY)}&stopid=193012313&pageNo=1&numOfRows=1&_type=xml`,
    ok: (b) => /<resultCode>200<\/resultCode>/.test(b),
  },
  {
    // 아직 앱·웹에서 쓰지 않음: 2026-11-16 공공데이터포털이 울산 서버에 닿지 못해 60초 시간 초과(오류 05),
    // 2026-11-17 0시부터는 결과 코드는 정상(00)인데 도로 목록·소통정보가 0건(totalCount 0).
    // 살아나면 구간 속도·선 좌표로 서울처럼 울산 도로 소통을 그릴 수 있음 → 도로가 1건 이상 와야 정상으로 봄
    name: "ulsan_road", label: "울산 도로정보 (준비 중)",
    url: (e) => e.DATA_GO_KR_KEY && `${DG}6310000/roadinformation/RoadInfo?serviceKey=${encodeKey(e.DATA_GO_KR_KEY)}&numOfRows=1&pageNo=1&_type=xml`,
    ok: (b) => /<resultCode>00<\/resultCode>/.test(b) && /<RoadID>/.test(b),
  },
  {
    name: "jeju_bus", label: "제주 버스",
    url: () => "https://bus.jeju.go.kr/publicTrafficInformation/getBusRouteNum",
    init: { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8", "User-Agent": "Mozilla/5.0 (compatible; TagoHub/1.0)" }, body: "GROUTE_TYPE=1" },
    ok: (b) => b.trim().startsWith("[") || b.trim().startsWith("{"),
  },
  {
    name: "kakao", label: "카카오 장소 검색",
    url: (e) => e.KAKAO_REST_KEY && `https://dapi.kakao.com/v2/local/search/keyword.json?query=${encodeURIComponent("서울역")}&size=1`,
    init: (e) => ({ headers: { Authorization: "KakaoAK " + (e.KAKAO_REST_KEY || "").trim() } }),
    ok: (b) => /"documents"/.test(b),
  },
];

/** 키 글자를 지움 (기록·응답에 키가 남지 않게) */
function scrub(text, env) {
  let t = String(text || "");
  for (const k of [env.DATA_GO_KR_KEY, env.SEOUL_SUBWAY_KEY, env.KAKAO_REST_KEY]) {
    const v = (k || "").trim();
    if (v) t = t.split(v).join("").split(encodeKey(v)).join("").split(encodeURIComponent(v)).join("");
  }
  return t.slice(0, 120);
}

/** 한 곳 확인 → {ok, msg, ms} (키 없으면 null) */
export async function checkOne(c, env, fetcher = fetch, now = () => Date.now()) {
  const url = c.url(env);
  if (!url) return null;
  const init = typeof c.init === "function" ? c.init(env) : c.init;
  const t0 = now();
  try {
    const r = await fetcher(url, { ...(init || {}), signal: AbortSignal.timeout ? AbortSignal.timeout(15000) : undefined });
    const body = await r.text();
    // 내용이 다르면 응답 앞부분을 같이 남김 (무엇이 왔는지 알 수 있게, 키는 scrub 이 지움)
    const why = reason(r.status, body) || (c.ok(body) ? null : "응답 내용이 예상과 다름: " + body.replace(/<\?xml[^>]*>/, "").replace(/\s+/g, " ").trim().slice(0, 60));
    return { ok: !why, msg: why ? scrub(why, env) : "", ms: now() - t0 };
  } catch (e) {
    return { ok: false, msg: scrub("연결 실패: " + (e && e.name === "TimeoutError" ? "15초 안에 답 없음" : (e && e.message) || e), env), ms: now() - t0 };
  }
}

async function ensure(db) {
  await db.prepare("CREATE TABLE IF NOT EXISTS api_health (name TEXT PRIMARY KEY, ok INTEGER NOT NULL, msg TEXT, ms INTEGER, at INTEGER NOT NULL, last_ok INTEGER, fail_since INTEGER) WITHOUT ROWID").run();
}

/** 예약 작업: 모두 확인해 기록 (한 번에 11곳 + D1 쓰기 몇 번: 바깥 요청 50번 한도 안) */
export async function healthCron(env, fetcher = fetch) {
  if (!env.DB) return { skip: "no db" };
  await ensure(env.DB);
  const at = Date.now();
  const results = await Promise.all(CHECKS.map(async (c) => [c, await checkOne(c, env, fetcher)]));
  const stmts = [];
  for (const [c, r] of results) {
    if (!r) continue;
    stmts.push(env.DB.prepare(
      `INSERT INTO api_health (name, ok, msg, ms, at, last_ok, fail_since) VALUES (?1, ?2, ?3, ?4, ?5, CASE WHEN ?2 = 1 THEN ?5 END, CASE WHEN ?2 = 0 THEN ?5 END)
       ON CONFLICT(name) DO UPDATE SET ok = ?2, msg = ?3, ms = ?4, at = ?5,
         last_ok = CASE WHEN ?2 = 1 THEN ?5 ELSE api_health.last_ok END,
         fail_since = CASE WHEN ?2 = 1 THEN NULL ELSE COALESCE(api_health.fail_since, ?5) END`,
    ).bind(c.name, r.ok ? 1 : 0, r.msg, r.ms, at));
  }
  if (stmts.length) await env.DB.batch(stmts);
  return { at, checked: stmts.length, failed: results.filter(([, r]) => r && !r.ok).map(([c]) => c.name) };
}

/** /w/health */
export async function handleHealth(env) {
  const head = { "Content-Type": "application/json; charset=utf-8", "Access-Control-Allow-Origin": "*", "Cache-Control": "no-store" };
  if (!env.DB) return new Response(JSON.stringify({ error: "no db" }), { status: 503, headers: head });
  await ensure(env.DB);
  const rows = (await env.DB.prepare("SELECT name, ok, msg, ms, at, last_ok, fail_since FROM api_health").all()).results || [];
  const by = Object.fromEntries(rows.map((r) => [r.name, r]));
  const checks = CHECKS.map((c) => {
    const r = by[c.name];
    return r ? { name: c.name, label: c.label, ok: !!r.ok, msg: r.msg || "", ms: r.ms, at: r.at, lastOk: r.last_ok, failSince: r.fail_since } : { name: c.name, label: c.label, ok: null };
  });
  return new Response(JSON.stringify({ at: Math.max(0, ...rows.map((r) => r.at || 0)) || null, checks }), { headers: head });
}
