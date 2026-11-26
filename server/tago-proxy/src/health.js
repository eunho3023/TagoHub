// 자료 제공처(API) 고장 확인: 30분마다 각 기관에 작은 요청을 하나씩 보내 되는지 D1(api_health)에 적어 둠.
// 서울 버스 API 가 앱에서 HTTP 401 로 조용히 고장 난 것을 사용자 화면을 보고서야 알았던 일 때문에 (2026-11-16).
//  GET /w/health → {at, checks: [{name, label, ok, msg, ms, at, lastOk, failSince}]} (웹 설정 화면 「자료 연결 상태」)
// 키·토큰은 응답·기록에 넣지 않음.

import { encodeKey, seoulArrivalUrl } from "./proxy.js";

const DG = "https://apis.data.go.kr/";
/** 공공데이터포털·기관이 키·한도 문제를 200 응답 안에 적어 보내는 말 */
const BAD = /SERVICE_KEY_IS_NOT|LIMITED_NUMBER_OF_SERVICE|SERVICE_ACCESS_DENIED|returnAuthMsg|SERVICE ERROR|APPLICATION_ERROR|ERROR-\d{3}|INFO-100|Unauthorized/i;

/**
 * 사람이 읽을 이유 (키 문제·한도 초과·기타). 공공데이터포털은 키 문제가 아닌 오류(기관 서버 시간 초과 등)도 returnAuthMsg 에 담아 보내서,
 * 예전엔 그것도 "인증 실패"로 적었음 (2026-11-22 울산 도로정보 SERVICETIMEOUT_ERROR). errMsg 를 보고 가르고, 무엇이 왔는지 같이 적음
 */
export function reason(status, body) {
  const b = String(body || "");
  const err = (/<errMsg>([^<]+)<\/errMsg>/.exec(b) || /"errMsg"\s*:\s*"([^"]+)"/.exec(b) || [])[1] || "";
  if (isPerSecondBody(b)) return "초당 요청 한도 초과 (잠깐 몰림)";
  if (/LIMITED_NUMBER_OF_SERVICE/.test(b)) return "하루 요청 한도 초과";
  if (/SERVICE_?TIME_?OUT|연결실패/i.test(b)) return `제공처 기관 서버에 닿지 못함 (${err || "시간 초과"})`;
  if (/SERVICE_KEY_IS_NOT|SERVICE_ACCESS_DENIED|INFO-100/i.test(b)) return `인증 실패 (키 확인 필요: ${(/SERVICE_KEY_IS_NOT\w*|SERVICE_ACCESS_DENIED\w*|INFO-100/i.exec(b) || [""])[0]})`;
  if (status === 401 || status === 403 || /Unauthorized/i.test(b)) return `인증 실패 (키 확인 필요: HTTP ${status})`;
  if (/returnAuthMsg/.test(b)) return `제공처 오류 응답 (${err || "returnAuthMsg"})`;
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
    // 대전 BIS 정류장 도착 (정부청사역 근처 8001378). 정상은 headerCd 0
    name: "daejeon_bus", label: "대전 버스",
    url: (e) => e.DATA_GO_KR_KEY && `${DG}6300000/arrive/getArrInfoByStopID?serviceKey=${encodeKey(e.DATA_GO_KR_KEY)}&BusStopID=8001378`,
    ok: (b) => /<headerCd>[04]<\/headerCd>/.test(b),
  },
  {
    // 한국지역정보개발원 전국 공영자전거 (서울 밖 10곳 대여소, 지도 공영자전거 층). 정상은 resultCode K0
    name: "bike_kr", label: "전국 공영자전거",
    url: (e) => e.DATA_GO_KR_KEY && `${DG}B551982/pbdo_v2/inf_101_00010002_v2?serviceKey=${encodeKey(e.DATA_GO_KR_KEY)}&pageNo=1&numOfRows=1&type=JSON&lcgvmnInstCd=3611000000`,
    ok: (b) => /"resultCode"\s*:\s*"K0"/.test(b),
  },
  {
    name: "ulsan_bus", label: "울산 버스",
    url: (e) => e.DATA_GO_KR_KEY && `${DG}6310000/bis/getBusArrivalInfo?serviceKey=${encodeKey(e.DATA_GO_KR_KEY)}&stopid=193012313&pageNo=1&numOfRows=1&_type=xml`,
    ok: (b) => /<resultCode>200<\/resultCode>/.test(b),
  },
  {
    // 웹·앱이 실제로 쓰는 정류장 도착(제주시청 406000110). 예전엔 노선 번호 목록(POST getBusRouteNum)을 물었는데,
    // 그 기능만 Cloudflare 에서 504 가 나 도착은 되는데도 고장으로 보였음 (2026-11-23)
    name: "jeju_bus", label: "제주 버스",
    url: () => "https://bus.jeju.go.kr/api/searchArrivalInfoList.do?station_id=406000110",
    init: { headers: { "User-Agent": "Mozilla/5.0 (compatible; TagoHub/1.0)", Accept: "application/json" } },
    ok: (b) => b.trim().startsWith("[") || b.trim().startsWith("{"),
  },
  {
    name: "kakao", label: "카카오 장소 검색",
    url: (e) => e.KAKAO_REST_KEY && `https://dapi.kakao.com/v2/local/search/keyword.json?query=${encodeURIComponent("서울역")}&size=1`,
    init: (e) => ({ headers: { Authorization: "KakaoAK " + (e.KAKAO_REST_KEY || "").trim() } }),
    ok: (b) => /"documents"/.test(b),
  },
  {
    // 장소·주소 검색, 좌표 → 주소 (src/pubgeo.js, 11월 30일부터 카카오 대신)
    name: "vworld", label: "브이월드 장소·주소 (국토교통부)",
    url: (e) => e.VWORLD_KEY && `https://api.vworld.kr/req/search?service=search&request=search&version=2.0&crs=EPSG:4326&size=1&page=1&type=place&format=json&errorformat=json&query=${encodeURIComponent("서울역")}&key=${encodeURIComponent(e.VWORLD_KEY.trim())}`,
    ok: (b) => /"status"\s*:\s*"OK"/.test(b),
  },
  {
    // 주유소 (src/pubgeo.js): 서울역 둘레 1km
    name: "opinet", label: "오피넷 주유소 (한국석유공사)",
    url: (e) => e.OPINET_KEY && `https://www.opinet.co.kr/api/aroundAll.do?code=${encodeURIComponent(e.OPINET_KEY.trim())}&x=309251&y=550771&radius=1000&prodcd=B027&sort=2&out=json`,
    ok: (b) => /"OIL"\s*:\s*\[/.test(b),
  },
];

/** 키 글자를 지움 (기록·응답에 키가 남지 않게) */
function scrub(text, env) {
  let t = String(text || "");
  for (const k of [env.DATA_GO_KR_KEY, env.SEOUL_SUBWAY_KEY, env.KAKAO_REST_KEY, env.VWORLD_KEY, env.OPINET_KEY]) {
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

/** 확인할 때가 됐는지 (마지막 확인이 29분 넘게 지났거나 기록이 없음).
 * 예전엔 정각 0분·30분에 불렸을 때만 확인했는데, GitHub 예약이 6시간에 한 번꼴로만 돌아(2026-11-23) 13시간씩 멈췄음 */
export const HEALTH_EVERY_MS = 29 * 60 * 1000;
export async function healthDue(env, now = Date.now()) {
  if (!env.DB) return false;
  await ensure(env.DB);
  const row = await env.DB.prepare("SELECT MAX(at) AS at FROM api_health").first();
  return !row || row.at == null || now - row.at >= HEALTH_EVERY_MS;
}

/** 예약 작업: 모두 확인해 기록 (한 번에 13곳 + D1 쓰기 몇 번: 바깥 요청 50번 한도 안) */
/**
 * 고장 표시는 두 번 이어서(30분 간격 확인 두 번) 실패해야: 처음 실패는 fail_since·msg 만 적고 ok 는 그대로.
 * retryMs: 실패한 곳을 같은 확인 안에서 다시 묻기까지 (시험은 0)
 */
export async function healthCron(env, fetcher = fetch, retryMs = 2000) {
  if (!env.DB) return { skip: "no db" };
  await ensure(env.DB);
  const at = Date.now();
  // 실패하면 2초 뒤 한 번 더 (공공데이터포털이 요청 절반을 HTTP_ERROR 로 끝내던 때 한 번 실패로 빨갛게 떴음, 2026-11-24)
  const results = await Promise.all(CHECKS.map(async (c) => {
    let r = await checkOne(c, env, fetcher);
    if (r && !r.ok && retryMs >= 0) { await new Promise((ok) => setTimeout(ok, retryMs)); r = await checkOne(c, env, fetcher); }
    return [c, r];
  }));
  const stmts = [];
  for (const [c, r] of results) {
    if (!r) continue;
    stmts.push(env.DB.prepare(
      `INSERT INTO api_health (name, ok, msg, ms, at, last_ok, fail_since) VALUES (?1, ?2, ?3, ?4, ?5, CASE WHEN ?2 = 1 THEN ?5 END, CASE WHEN ?2 = 0 THEN ?5 END)
       ON CONFLICT(name) DO UPDATE SET ok = CASE WHEN ?2 = 1 THEN 1 WHEN api_health.fail_since IS NOT NULL THEN 0 ELSE api_health.ok END, msg = ?3, ms = ?4, at = ?5,
         last_ok = CASE WHEN ?2 = 1 THEN ?5 ELSE api_health.last_ok END,
         fail_since = CASE WHEN ?2 = 1 THEN NULL ELSE COALESCE(api_health.fail_since, ?5) END`,
    ).bind(c.name, r.ok ? 1 : 0, r.msg, r.ms, at));
  }
  if (stmts.length) await env.DB.batch(stmts);
  return { at, checked: stmts.length, failed: results.filter(([, r]) => r && !r.ok).map(([c]) => c.name) };
}

// ── 실제 요청에서 하루 한도 초과 알아보기 ──
// 고장 확인은 기관마다 한 가지 요청만 해 봐서, 지하철 시간표·경기 노선 정류장처럼 다른 서비스의 한도가 차도 몰랐음 (2026-11-22).
// 그래서 사용자 요청에 대한 실제 응답에 LIMITED_NUMBER_OF_SERVICE 가 오면 서비스별로 적어 두고 /w/health 에 오늘 것을 보여 줌.

/** 공공데이터포털 서비스 이름 (기관번호/서비스) → 사람이 읽을 이름. 없으면 서비스 이름 그대로 */
export const QUOTA_LABELS = {
  "1613000/SubwayInfoService": "TAGO 지하철 정보",
  "1613000/BusRouteInfoInqireService": "TAGO 노선 정보",
  "1613000/ArvlInfoInqireService": "TAGO 버스 도착",
  "1613000/BusLcInfoInqireService": "TAGO 버스 위치",
  "1613000/BusSttnInfoInqireService": "TAGO 정류장 정보",
  "6410000/busrouteservice": "경기 버스 노선",
  "6410000/busarrivalservice": "경기 버스 도착",
  "6410000/busstationservice": "경기 정류장",
  "6410000/buslocationservice": "경기 버스 위치",
  "6300000/arrive": "대전 버스 도착",
  "6310000/bis": "울산 버스",
  "6260000/BusanBIMS": "부산 버스",
  "6290000/gj_bis": "광주 버스",
  "4641000/nosun": "전주 버스 노선",
  "B551982/pbdo_v2": "전국 공영자전거",
};

/** 주소·경로 → "기관번호/서비스" (apis.data.go.kr 만, 아니면 null) */
export function quotaService(target) {
  const m = /^(?:https?:\/\/apis\.data\.go\.kr)?\/?(\w+)\/(\w+)/.exec(String(target || ""));
  if (!m || !/^(?:https?:\/\/apis\.data\.go\.kr|\/?\d|\/?B\d)/.test(String(target))) return null;
  return m[1] + "/" + m[2];
}

/**
 * 초당 요청 한도 초과 응답인지 (LIMITED_NUMBER_OF_SERVICE_REQUESTS_PER_SECOND_EXCEEDS_ERROR, 까닭 번호 23).
 * 하루 한도와 이름이 비슷해 예전엔 하루 한도로 적었음: 2026-11-25·26 아침 7시 "TAGO 버스 위치 하루 한도 초과"는 사실 이것이었고,
 * 바로 다시 물으면 받아졌음. 잠깐 쉬고 다시 물으면 됨
 */
export function isPerSecondBody(body) { return /LIMITED_NUMBER_OF_SERVICE_REQUESTS_PER_SECOND|<returnReasonCode>\s*23\s*</.test(String(body || "").slice(0, 4000)); }

/** 초당 한도 오류 뒤 다시 묻는 횟수와 기다림 (0.3~0.6초, 1~1.5초: 다른 요청과 겹치지 않게 조금씩 다르게) */
export const PER_SECOND_RETRIES = 2;
export const perSecondWait = (i, rnd = Math.random()) => (i === 0 ? 300 : 1000) + Math.floor(rnd * (i === 0 ? 300 : 500));

/** 하루 한도 초과 응답인지 (초당 한도는 뺌) */
export function isQuotaBody(body) { return /LIMITED_NUMBER_OF_SERVICE/.test(String(body || "").slice(0, 4000)) && !isPerSecondBody(body); }

const quotaMemo = new Map();
/** 실제 응답이 한도 초과면 서비스별로 적어 둠 (같은 서비스는 1분에 한 번만 씀). 실패해도 조용히 */
export async function noteQuota(env, target, body, now = Date.now()) {
  if (!env.DB || !isQuotaBody(body)) return false;
  const svc = quotaService(target);
  if (!svc || now - (quotaMemo.get(svc) || 0) < 60000) return false;
  quotaMemo.set(svc, now);
  try {
    await env.DB.prepare("CREATE TABLE IF NOT EXISTS api_quota (svc TEXT PRIMARY KEY, at INTEGER NOT NULL, first INTEGER NOT NULL) WITHOUT ROWID").run();
    // first: 오늘(한국 시각) 처음 넘은 때. 날이 바뀐 뒤 다시 넘으면 새로 씀
    await env.DB.prepare(`INSERT INTO api_quota (svc, at, first) VALUES (?1, ?2, ?2)
      ON CONFLICT(svc) DO UPDATE SET at = ?2, first = CASE WHEN api_quota.at < ?3 THEN ?2 ELSE api_quota.first END`).bind(svc, now, kstMidnight(now)).run();
    return true;
  } catch { return false; }
}
const okMemo = new Map();
/**
 * 그 서비스가 다시 정상으로 답하면 오늘 적어 둔 한도 초과를 지움 (같은 서비스는 1분에 한 번만 씀).
 * 진짜 하루 한도라면 자정 전엔 정상 답이 올 수 없음. 잠깐 몰린 것(초당 한도 등)이 자정까지 "하루 한도 초과"로 남아 헷갈렸음 (2026-11-26 TAGO 버스 위치)
 */
export async function clearQuota(env, target, now = Date.now()) {
  if (!env.DB) return false;
  const svc = quotaService(target);
  if (!svc || now - (okMemo.get(svc) || 0) < 60000) return false;
  okMemo.set(svc, now);
  try {
    await env.DB.prepare("DELETE FROM api_quota WHERE svc = ?1 AND at < ?2").bind(svc, now).run();
    return true;
  } catch { return false; }   // 표가 아직 없음 등
}
export function _resetQuotaMemo() { quotaMemo.clear(); okMemo.clear(); }

/** 한국 시각 오늘 0시 (ms). 공공데이터포털 하루 한도는 한국 자정에 풀림 */
export function kstMidnight(now = Date.now()) {
  const K = 9 * 3600 * 1000;
  return Math.floor((now + K) / 86400000) * 86400000 - K;
}

/** 중계·예약 작업·오늘 한도 초과를 고장 확인 줄 모양으로 (D1 에 이미 있는 기록으로 바로 계산, 바깥 요청 없음) */
export async function extraChecks(env, now = Date.now()) {
  const out = [];
  const min = (ms) => Math.max(0, Math.round(ms / 60000));
  // 국내 중계 (집 노트북): 5분마다 안부, 15분 넘게 없으면 꺼진 것 (src/krrelay.js)
  const relay = await env.DB.prepare("SELECT at FROM kr_relay WHERE id = 1").first().catch(() => null);
  if (env.RELAY_KEY || relay) {
    const ago = relay ? now - relay.at : null;
    const ok = ago != null && ago < 15 * 60 * 1000;
    out.push({ name: "kr_relay", label: "국내 중계 (집 노트북)", ok, msg: ok ? "" : relay ? `${min(ago)}분째 소식 없음 · 노트북·와이파이 확인 필요` : "등록된 적 없음",
      ms: null, at: relay ? relay.at : null, lastOk: relay ? relay.at : null, failSince: ok || !relay ? null : relay.at + 15 * 60 * 1000 });
  }
  // 예약 작업 (노트북이 1분마다 /w/cron, GitHub 은 예비): 10분 넘게 안 불렸으면 고장
  const cron = await env.DB.prepare("SELECT v FROM poll_meta WHERE k = 'cron:last'").first().catch(() => null);
  if (cron && cron.v) {
    const ago = now - cron.v, ok = ago < 10 * 60 * 1000;
    out.push({ name: "cron", label: "예약 작업 (1분마다)", ok, msg: ok ? "" : `${min(ago)}분째 안 돎 · 노트북 중계 확인 필요`, ms: null, at: cron.v, lastOk: cron.v, failSince: ok ? null : cron.v });
  }
  // 오늘 하루 한도를 넘은 서비스 (실제 요청에서 알아본 것)
  const rows = (await env.DB.prepare("SELECT svc, at, first FROM api_quota WHERE at >= ?").bind(kstMidnight(now)).all().catch(() => null))?.results || [];
  for (const r of rows) out.push({ name: "quota:" + r.svc, label: (QUOTA_LABELS[r.svc] || r.svc) + " 하루 한도", ok: false, msg: "오늘 하루 요청 한도 초과 · 밤 12시에 풀려요", ms: null, at: r.at, lastOk: null, failSince: r.first });
  return out;
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
    const opt = c.optional ? { optional: true } : {};
    return r ? { name: c.name, label: c.label, ok: !!r.ok, msg: r.msg || "", ms: r.ms, at: r.at, lastOk: r.last_ok, failSince: r.fail_since, ...opt } : { name: c.name, label: c.label, ok: null, ...opt };
  });
  const extra = await extraChecks(env).catch(() => []);
  return new Response(JSON.stringify({ at: Math.max(0, ...rows.map((r) => r.at || 0)) || null, checks: checks.concat(extra) }), { headers: head });
}
