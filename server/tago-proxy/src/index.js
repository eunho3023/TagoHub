// TagoHub 공공데이터 중계 서버 (Cloudflare Workers, 무료 요금제로 운영).
// - 공공데이터포털 키를 앱 대신 서버(비밀 값 DATA_GO_KR_KEY)에 둠 → 앱을 뜯어봐도 키가 없음
// - 같은 요청은 잠시 저장해 두고 나눠 줌 → 모든 사용자가 키 하나를 같이 써도 호출이 덜 나감
// - 앱은 X-TagoHub-Token 헤더(비밀 값 APP_TOKEN)를 보내야 함 → 아무나 이 서버를 키 대신 쓰지 못하게
import { cacheKey, cacheable, fillKeys, hostTarget, hostTtl, ttlFor, upstreamPath, upstreamUrl, webRequest, exWebRequest, exUrl, seoulWebRequest, seoulArrivalUrl, seoulPositionRequest, seoulPositionUrl, kakaoWebRequest, kacWebRequest, ovWebRequest, cctvRows, encodeKey, signalWebRequest, busWebRequest, icnWebRequest } from "./proxy.js";
import { handleLog, handleWebLog, hitsFromBusLc, prevStore } from "./log.js";
import { handleShape, shapeCron } from "./shape.js";
import { recordGps, gpsInfo } from "./gps.js";
import { storeKey, getStored, putStored, DAY_MS } from "./tagostore.js";
import { handleSeoulTraffic } from "./seoultraffic.js";
import { handlePub } from "./pubgeo.js";
import { noteInterest, pollCron, pollStatus } from "./poll.js";
import { handleJeonjuTime, handleBusTime } from "./timetable.js";
import { handleReport } from "./report.js";
import { handleEdits } from "./edits.js";
import { handleAuth } from "./auth.js";
import { handleAppShape } from "./appshape.js";
import { healthCron, healthDue, handleHealth, noteQuota, isQuotaBody } from "./health.js";
import { upstreamFetch, handleRelay } from "./krrelay.js";

/** 서버 코드가 받는 길 (나머지는 화면·자료 파일). pages/_routes.json 과 같음 */
export const API_PREFIXES = ["/health", "/w/", "/log/", "/appshape/", "/h/", "/dg/"];
export const isApiPath = (p) => API_PREFIXES.some((x) => (x.endsWith("/") ? p.startsWith(x) : p === x));

/**
 * 예약 작업 한 번 (1분에 한 번 부름). 마지막 고장 확인이 29분 넘게 지났으면 그 번엔 자료 제공처 고장 확인만 (src/health.js):
 * 한 번 실행의 외부 요청 수 한도(50번)를 다른 작업과 나누지 않게.
 * 그 밖에는 관심 노선 버스 위치 모으기(정류장 시간표, src/poll.js) → 노선 모양 하나 만들기(src/shape.js).
 * 부르는 곳: 집 노트북 국내 중계가 1분마다(server/relay/relay.mjs, CRON_KEY 있을 때), 예비로 GitHub Actions(.github/workflows/server-cron.yml, 실제로는 몇 시간에 한 번꼴)
 */
export async function runCron(env, now = Date.now(), { fetcher = fetch } = {}) {
  // 예약 작업의 바깥 요청은 모두 실제 요청처럼 국내 중계를 거침 (공공데이터포털은 Cloudflare 에서 바로 물으면 매달리거나 막힘).
  // upstreamFetch 는 apis.data.go.kr 만 중계로 보내고 나머지는 바로 물음
  const viaRelay = fetcher === fetch ? (u, i) => upstreamFetch(u, i || {}, env) : fetcher;
  if (await healthDue(env, now).catch(() => false)) return { health: await healthCron(env, viaRelay).catch(() => null) != null };
  // 버스 위치 모으기·노선 모양 만들기도 중계를 거침: 바로 물어서 실패가 성공보다 많았음 (2026-11-23 bad 250, hits 240)
  await pollCron(env, viaRelay).catch(() => null);
  await shapeCron(env, viaRelay).catch(() => null);
  return { poll: true, shape: true };
}

/** 두 글자가 같은지 (걸린 시간으로 맞힐 수 없게 끝까지 비교) */
function sameText(a, b) {
  a = String(a || ""); b = String(b || "");
  let d = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) d |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  return d === 0 && a.length > 0;
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname === "/health") return new Response("ok");
    if (url.pathname.startsWith("/w/")) return web(request, url, env, ctx);
    if (!env.APP_TOKEN || request.headers.get("X-TagoHub-Token") !== env.APP_TOKEN) {
      return new Response("forbidden", { status: 403 });
    }
    if (url.pathname.startsWith("/log/")) return handleLog(request, url, env, ctx);
    // 앱이 만든 노선 모양 나눠 쓰기 (src/appshape.js)
    if (url.pathname.startsWith("/appshape/")) return handleAppShape(request, url, env);
    if (request.method !== "GET") return new Response("GET only", { status: 405 });
    if (url.pathname.startsWith("/h/")) return relay(request, url, env, ctx);
    const path = upstreamPath(url.pathname);
    if (!path) return new Response("not allowed", { status: 404 });
    if (!env.DATA_GO_KR_KEY) return new Response("server key missing", { status: 500 });

    return dataGo(path, url.search, request.headers.get("Accept"), env, ctx, {});
  },
};

const WEB_HEADERS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "GET, POST", "Access-Control-Allow-Headers": "Content-Type, X-TagoHub-Admin, Authorization" };

/** 웹 버전: 토큰 없이 실시간 버스 도착·위치만 (proxy.js webRequest), 주소(IP)마다 분당 요청 수 제한 */
async function web(request, url, env, ctx) {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: WEB_HEADERS });
  // 예약 작업 (GitHub Actions 가 1분마다, 비밀 값 CRON_KEY). 끝날 때까지 기다렸다 답함
  if (url.pathname === "/w/cron") {
    if (request.method !== "POST" || !sameText(request.headers.get("X-Cron-Key"), env.CRON_KEY)) return new Response("forbidden", { status: 403 });
    const r = await runCron(env).catch((e) => ({ error: String(e && e.message || e) }));
    return new Response(JSON.stringify(r), { headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
  }
  // 국내 중계(집 노트북)가 지금 주소를 알림·켜져 있는지 (src/krrelay.js)
  if (url.pathname.startsWith("/w/relay/")) return handleRelay(request, url, env);
  // 앱 제보 (POST, 주소마다 분당 요청 수 제한을 같이 씀) — src/report.js
  if (url.pathname === "/w/report") {
    if (env.WEB_LIMIT) {
      const { success } = await env.WEB_LIMIT.limit({ key: "report:" + (request.headers.get("CF-Connecting-IP") || "unknown") });
      if (!success) return new Response("too many requests", { status: 429, headers: { ...WEB_HEADERS, "Retry-After": "60" } });
    }
    return handleReport(request, env, ctx);
  }
  // 지도 고치기 (노선 모양·정류장·역 위치, src/edits.js). 저장(POST)은 주소마다 분당 제한
  // 아이디 회원 (src/auth.js)
  if (url.pathname.startsWith("/w/auth/")) return handleAuth(request, url, env, ctx);
  if (url.pathname === "/w/edits" || url.pathname === "/w/edits/check") {
    if ((request.method === "POST" || url.pathname === "/w/edits/check") && env.WEB_LIMIT) {
      const { success } = await env.WEB_LIMIT.limit({ key: "edit:" + (request.headers.get("CF-Connecting-IP") || "unknown") });
      if (!success) return new Response("too many requests", { status: 429, headers: { ...WEB_HEADERS, "Retry-After": "60" } });
    }
    return handleEdits(request, env, ctx, url.pathname);
  }
  if (request.method !== "GET") return new Response("GET only", { status: 405, headers: WEB_HEADERS });
  // 자료 제공처 연결 상태 (src/health.js, 30분마다 확인한 기록)
  if (url.pathname === "/w/health") return handleHealth(env);
  if (env.WEB_LIMIT && (url.pathname.startsWith("/w/shape/") || url.pathname.startsWith("/w/log/") || url.pathname.startsWith("/w/poll/") || url.pathname.startsWith("/w/gps/") || url.pathname.startsWith("/w/topis/"))) {
    // 서울 교통은 화면 하나에 작은 요청을 30~40개씩 한꺼번에 보내서, 다른 기능(노선 모양 등)과 한도를 나누지 않게 따로 셈
    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    const { success } = await env.WEB_LIMIT.limit({ key: url.pathname.startsWith("/w/topis/") ? "topis:" + ip : ip });
    if (!success) return new Response("too many requests", { status: 429, headers: { ...WEB_HEADERS, "Retry-After": "60" } });
  }
  if (url.pathname.startsWith("/w/shape/")) return handleShape(request, url, env, ctx);
  // 정류장 지난 운행 기록 (정류장 시간표, src/log.js)
  if (url.pathname === "/w/log/stop") return handleWebLog(url, env);
  if (url.pathname.startsWith("/w/gps/")) return new Response(JSON.stringify(await gpsInfo(env.DB, decodeURIComponent(url.pathname.slice("/w/gps/".length))).catch(() => null)), { headers: { ...WEB_HEADERS, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" } });
  if (url.pathname === "/w/poll/status") return new Response(JSON.stringify(await pollStatus(env)), { headers: { ...WEB_HEADERS, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" } });
  if (url.pathname.startsWith("/w/ex/")) return exWeb(request, url, env, ctx);
  // 서울시 교통정보(TOPIS): 실시간 도로 소통·돌발·교통량 (src/seoultraffic.js)
  if (url.pathname.startsWith("/w/topis/")) return handleSeoulTraffic(url, env, ctx);
  if (url.pathname.startsWith("/w/seoul/")) return seoulWeb(request, url, env, ctx);
  if (url.pathname.startsWith("/w/kakao/")) return kakaoWeb(request, url, env, ctx);
  // 공공기관 장소·주소·주유소 (src/pubgeo.js, 브이월드·오피넷): 카카오 대신. 저장에 없을 때만 주소마다 분당 제한
  if (url.pathname.startsWith("/w/pub/")) {
    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    return handlePub(request, url, env, ctx, { fetcher: (u, i) => upstreamFetch(u, i || {}, env), limit: env.WEB_LIMIT ? async () => (await env.WEB_LIMIT.limit({ key: "pub:" + ip })).success : null });
  }
  if (url.pathname.startsWith("/w/ov/")) return ovWeb(request, url, env, ctx);
  if (url.pathname.startsWith("/w/kac/")) return kacWeb(request, url, env, ctx);
  if (url.pathname.startsWith("/w/signal/")) return signalWeb(request, url, env, ctx);
  // 지자체 공식 배차시간표 (전주·울산·천안·창원·대구·제주·진주·구미 버스정보 사이트, src/timetable.js)
  if (url.pathname.startsWith("/w/bus/jeonju/time/")) return handleJeonjuTime(url.pathname.slice("/w/bus/jeonju/time/".length), ctx);
  if (url.pathname.startsWith("/w/bus/time/")) { const [area, id] = url.pathname.slice("/w/bus/time/".length).split("/"); return handleBusTime(area, id, ctx, fetch, url.searchParams.get("no")); }
  if (url.pathname.startsWith("/w/bus/")) return busWeb(request, url, env, ctx);
  if (url.pathname.startsWith("/w/icn/")) return icnWeb(request, url, env, ctx);
  const r = webRequest(url.pathname, url.search);
  if (!r) return new Response("not allowed", { status: 404, headers: WEB_HEADERS });
  if (env.WEB_LIMIT) {
    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    const { success } = await env.WEB_LIMIT.limit({ key: ip });
    if (!success) return new Response("too many requests", { status: 429, headers: { ...WEB_HEADERS, "Retry-After": "60" } });
  }
  if (!env.DATA_GO_KR_KEY) return new Response("server key missing", { status: 500, headers: WEB_HEADERS });
  return dataGo(r.path, r.search, "application/xml", env, ctx, WEB_HEADERS);
}

/** 공공데이터포털에 서버 키로 묻고, 정상 응답은 잠시 저장해 같은 요청에 나눠 줌. [extra] 는 응답에 덧붙일 헤더 */
async function dataGo(path, search, accept, env, ctx, extra) {
  const cache = caches.default;
  const key = new Request(cacheKey(path, search));
  const hit = await cache.match(key);
  if (hit) {
    const r = new Response(hit.body, hit);
    r.headers.set("X-Cache", "HIT");
    for (const [k, v] of Object.entries(extra)) r.headers.set(k, v);
    return r;
  }

  // TAGO 노선 정보는 D1 에 저장해 둔 것(하루 안)을 먼저: 하루 요청 한도를 아낌 (src/tagostore.js)
  const sk = env.DB ? storeKey(path, search) : null;
  const fresh = sk ? await getStored(env.DB, sk, DAY_MS) : null;
  if (fresh) {
    const stored = new Response(fresh, { status: 200, headers: { "Content-Type": "application/xml; charset=utf-8", "X-Cache": "STORE", "Cache-Control": `public, max-age=${ttlFor(path)}` } });
    ctx.waitUntil(cache.put(key, stored.clone()));
    const out = new Response(stored.body, stored);
    for (const [k, v] of Object.entries(extra)) out.headers.set(k, v);
    return out;
  }

  // 국내 중계(집 노트북) 먼저, 안 되면 바로 묻되 3초마다 새 연결로 (Cloudflare → 공공데이터포털 연결이 자주 매달림, src/krrelay.js)
  const upstream = await upstreamFetch(upstreamUrl(path, search, env.DATA_GO_KR_KEY), {
    headers: { Accept: accept || "application/xml, text/xml;q=0.9, */*;q=0.8" },
  }, env);
  const body = await upstream.text();
  // 하루 한도 초과면 서비스별로 적어 둠 (/w/health 에 보임, src/health.js)
  if (env.DB && isQuotaBody(body)) ctx.waitUntil(noteQuota(env, path, body));
  const headers = {
    "Content-Type": upstream.headers.get("Content-Type") || "application/xml; charset=utf-8",
    "X-Cache": "MISS",
    "X-Tries": upstream.headers.get("X-Tries") || "1",
    "X-Via": upstream.headers.get("X-Via") || "direct",
    ...(upstream.headers.get("X-Relay-Why") ? { "X-Relay-Why": upstream.headers.get("X-Relay-Why") } : {}),
  };
  if (cacheable(upstream.status, body)) {
    const ttl = ttlFor(path);
    const stored = new Response(body, { status: 200, headers: { ...headers, "Cache-Control": `public, max-age=${ttl}` } });
    ctx.waitUntil(cache.put(key, stored.clone()));
    if (sk) ctx.waitUntil(putStored(env.DB, sk, body));
    // 노선별 버스 위치(GPS)는 노선 모양 자료로 모음 (src/gps.js, 차량 번호·시각은 저장하지 않음)
    if (env.DB && /getRouteAcctoBusLcList/.test(path)) {
      const q = new URLSearchParams(search);
      ctx.waitUntil(recordGps(env.DB, q.get("routeId"), q.get("cityCode"), body).catch(() => 0));
      // 정류장을 옮겨 간 버스 → 지난 운행 기록 (정류장 시간표)
      ctx.waitUntil(hitsFromBusLc(env.DB, prevStore(env.DB), q.get("routeId"), body).catch(() => 0));
      // 이 노선을 보는 사람이 있음 → 예약 작업이 스스로 모을 노선 후보
      ctx.waitUntil(noteInterest(env.DB, q.get("routeId"), q.get("cityCode")).catch(() => null));
    }
    const out = new Response(stored.body, stored);
    for (const [k, v] of Object.entries(extra)) out.headers.set(k, v);
    return out;
  }
  // 공공데이터가 한도 초과 등으로 실패: 저장해 둔 지난 응답(30일 안)이 있으면 그것 (노선 정류장은 거의 안 바뀜)
  const stale = sk ? await getStored(env.DB, sk, 30 * DAY_MS) : null;
  if (stale) return new Response(stale, { status: 200, headers: { "Content-Type": "application/xml; charset=utf-8", "X-Cache": "STALE", "Cache-Control": "public, max-age=600", ...extra } });
  return new Response(body, { status: upstream.status, headers: { ...headers, ...extra } });
}

/** 여러 기관 중계: 자리표시를 키로 바꿔 원래 호스트에 묻고, 정상 응답은 잠시 저장 */
async function relay(request, url, env, ctx) {
  const t = hostTarget(url.pathname);
  if (!t) return new Response("not allowed", { status: 404 });
  // 저장 이름은 자리표시가 든 그대로 (진짜 키가 저장 이름에 들어가지 않게)
  const key = new Request(`https://cache.tagohub/${t.scheme}/${t.host}${t.path}${url.search}`);
  const auth = request.headers.get("Authorization");
  // 카카오 Authorization 헤더도 자리표시만 들어 있어서 모든 사용자가 같음 → 같은 요청이면 저장한 것을 나눠 줘도 됨
  const cache = caches.default;
  const hit = await cache.match(key);
  if (hit) {
    const r = new Response(hit.body, hit);
    r.headers.set("X-Cache", "HIT");
    return r;
  }
  let target, headers;
  try {
    target = `${t.scheme}://${t.host}${fillKeys(t.path, env, false)}${fillKeys(url.search, env, true)}`;
    headers = { Accept: request.headers.get("Accept") || "*/*", "User-Agent": "TagoHub" };
    if (auth) headers.Authorization = fillKeys(auth, env, false);
    for (const h of ["Content-Type", "KA"]) { const v = request.headers.get(h); if (v) headers[h] = v; }
  } catch (e) {
    return new Response("server key missing: " + e.message.replace("missing ", ""), { status: 500 });
  }
  const upstream = await upstreamFetch(target, { headers }, env);
  const body = await upstream.arrayBuffer();
  const text = new TextDecoder().decode(body.slice(0, 4000));
  if (env.DB && isQuotaBody(text)) ctx.waitUntil(noteQuota(env, target, text));
  const out = { "Content-Type": upstream.headers.get("Content-Type") || "application/octet-stream", "X-Cache": "MISS", "X-Tries": upstream.headers.get("X-Tries") || "1", "X-Via": upstream.headers.get("X-Via") || "direct", ...(upstream.headers.get("X-Relay-Why") ? { "X-Relay-Why": upstream.headers.get("X-Relay-Why") } : {}) };
  if (upstream.status === 200 && cacheable(200, text)) {
    const ttl = hostTtl(t.host, t.path);
    const stored = new Response(body, { status: 200, headers: { ...out, "Cache-Control": `public, max-age=${ttl}` } });
    ctx.waitUntil(cache.put(key, stored.clone()));
    return stored;
  }
  return new Response(body, { status: upstream.status, headers: out });
}

const MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36";

/**
 * 웹 버전 JSON 중계 공통: 저장해 둔 것이 있으면 바로, 없으면 주소마다 분당 제한을 지나 [upstream] 에 묻고
 * [ok] 를 통과한 응답만 [ttl] 초 저장. 키가 든 주소는 응답·오류에 내보내지 않음
 */
async function cachedJson(request, env, ctx, cacheUrl, upstream, ttl, ok, extraHeaders = {}, allowXml = false, post = null) {
  const cache = caches.default;
  const key = new Request(cacheUrl);
  const hit = await cache.match(key);
  if (hit) {
    const out = new Response(hit.body, hit);
    out.headers.set("X-Cache", "HIT");
    for (const [k, v] of Object.entries(WEB_HEADERS)) out.headers.set(k, v);
    return out;
  }
  // 하루씩 두는 자료(경기 노선 정류장·정류장 경유 노선, 전주 노선 등)는 D1 에도 저장: Cloudflare 임시 저장은 곳마다 따로라 금방 비고,
  // 공공데이터포털 하루 한도가 차면 "받지 못했어요"가 떴음 (2026-11-22 경기 노선 정류장). 하루 안 것은 바로, 실패하면 30일 안 것
  const sk = env.DB && ttl >= 3600 && !post ? "web:" + cacheUrl.replace(/^https:\/\/cache\.tagohub\//, "") : null;
  const storedResponse = (body, tag) => new Response(body, { status: 200, headers: { "Content-Type": body.trimStart().startsWith("<") ? "application/xml; charset=utf-8" : "application/json; charset=utf-8", "X-Cache": tag, "Cache-Control": "public, max-age=" + (tag === "STORE" ? ttl : 600), ...WEB_HEADERS } });
  const fresh = sk ? await getStored(env.DB, sk, DAY_MS) : null;
  if (fresh) { const r = storedResponse(fresh, "STORE"); ctx.waitUntil(cache.put(key, r.clone())); return r; }
  if (env.WEB_LIMIT) {
    const { success } = await env.WEB_LIMIT.limit({ key: request.headers.get("CF-Connecting-IP") || "unknown" });
    if (!success) return new Response("too many requests", { status: 429, headers: { ...WEB_HEADERS, "Retry-After": "60" } });
  }
  if (!upstream) return new Response("server key missing", { status: 500, headers: WEB_HEADERS });
  const headers = { "Content-Type": "application/json; charset=utf-8", "X-Cache": "MISS", ...WEB_HEADERS };
  let res, body;
  try {
    // post: 폼 내용을 POST 로 (제주 누리집 노선 정보처럼 GET 으로는 답하지 않는 곳)
    res = await upstreamFetch(upstream, post == null ? { headers: { "User-Agent": MOBILE_UA, Accept: "application/json", ...extraHeaders } }
      : { method: "POST", body: post, headers: { "User-Agent": MOBILE_UA, Accept: "application/json", "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8", ...extraHeaders } }, env);
    body = await res.text();
    headers["X-Tries"] = res.headers.get("X-Tries") || "1";
    headers["X-Via"] = res.headers.get("X-Via") || "direct";
    if (res.headers.get("X-Relay-Why")) headers["X-Relay-Why"] = res.headers.get("X-Relay-Why");
  } catch {
    const stale = sk ? await getStored(env.DB, sk, 30 * DAY_MS) : null;
    if (stale) return storedResponse(stale, "STALE");
    return new Response("{\"code\":\"ERROR\",\"message\":\"upstream fetch failed\"}", { status: 502, headers });
  }
  // allowXml: XML 로만 답하는 기관(전주시 버스)은 XML 도 그대로 저장·전달
  const json = /^[[{]/.test(body.trimStart()) || (allowXml && body.trimStart().startsWith("<"));
  if (!res.ok || !json || !ok(body)) {
    const quota = isQuotaBody(body);
    if (quota && env.DB) ctx.waitUntil(noteQuota(env, upstream, body));
    const stale = sk ? await getStored(env.DB, sk, 30 * DAY_MS) : null;
    if (stale) return storedResponse(stale, "STALE");
    // 하루 한도 초과는 XML 로 와서 예전엔 "upstream 200" 만 보냈음 → 웹이 "밤 12시 뒤" 안내를 하도록 이름을 그대로 (site/js/core.js tryLater)
    if (quota) return new Response("{\"code\":\"LIMITED_NUMBER_OF_SERVICE\",\"message\":\"daily quota exceeded\"}", { status: 502, headers });
    return new Response(json ? body : "{\"code\":\"ERROR\",\"message\":\"upstream " + res.status + "\"}", { status: 502, headers });
  }
  const stored = new Response(body, { status: 200, headers: { ...headers, "Cache-Control": "public, max-age=" + ttl } });
  ctx.waitUntil(cache.put(key, stored.clone()));
  if (sk) ctx.waitUntil(putStored(env.DB, sk, body));
  return stored;
}

/** 웹 버전 한국도로공사: 허락한 기능만, 서버 키로 묻고 잠시 저장 (proxy.js exWebRequest) */
async function exWeb(request, url, env, ctx) {
  const r = exWebRequest(url.pathname, url.search);
  if (!r) return new Response("not allowed", { status: 404, headers: WEB_HEADERS });
  return cachedJson(request, env, ctx, "https://cache.tagohub/ex/" + r.path + "?pageNo=" + r.page + r.extra,
    env.EX_API_KEY ? exUrl(r.path, r.page, env.EX_API_KEY, r.extra) : null, r.ttl, (b) => /"code"\s*:\s*"SUCCESS"/i.test(b));
}

/** 웹 버전 서울 실시간 지하철 역 도착 (proxy.js seoulWebRequest). "데이터 없음"(INFO-200)도 정상으로 저장 */
async function seoulWeb(request, url, env, ctx) {
  const pr = seoulPositionRequest(url.pathname);
  if (pr) {
    return cachedJson(request, env, ctx, "https://cache.tagohub/seoul/position/" + encodeURIComponent(pr.line),
      env.SEOUL_SUBWAY_KEY ? seoulPositionUrl(pr.line, env.SEOUL_SUBWAY_KEY) : null, pr.ttl, (b) => /"code"\s*:\s*"INFO-(000|200)"/.test(b));
  }
  const r = seoulWebRequest(url.pathname);
  if (!r) return new Response("not allowed", { status: 404, headers: WEB_HEADERS });
  return cachedJson(request, env, ctx, "https://cache.tagohub/seoul/arrival/" + encodeURIComponent(r.name),
    env.SEOUL_SUBWAY_KEY ? seoulArrivalUrl(r.name, env.SEOUL_SUBWAY_KEY) : null, r.ttl, (b) => /"code"\s*:\s*"INFO-(000|200)"/.test(b));
}

/** 웹 버전 카카오 장소 찾기·대중교통 길찾기 (proxy.js kakaoWebRequest). REST 키는 Authorization 헤더로 */
async function kakaoWeb(request, url, env, ctx) {
  const r = kakaoWebRequest(url.pathname, url.search);
  if (!r) return new Response("not allowed", { status: 404, headers: WEB_HEADERS });
  const key = (env.KAKAO_REST_KEY || "").trim();
  return cachedJson(request, env, ctx, "https://cache.tagohub/kakao/" + r.cacheKey, key ? r.url : null, r.ttl,
    (b) => /"(documents|routes|status)"\s*:/.test(b), { Authorization: "KakaoAK " + key });
}

/** 웹 버전 CCTV 목록 (proxy.js ovWebRequest): 집 PC 가 올린 전국 목록 파일을 받아 [[이름, 위도, 경도, 영상 주소]] 로 줄여 10분 저장 */
const OV_RELEASE = "https://github.com/eunho3023/TagoHub/releases/download/data-latest/";
async function ovWeb(request, url, env, ctx) {
  const r = ovWebRequest(url.pathname);
  if (!r) return new Response("not allowed", { status: 404, headers: WEB_HEADERS });
  const cache = caches.default;
  const key = new Request("https://cache.tagohub/ov/" + r.file);
  const hit = await cache.match(key);
  if (hit) {
    const out = new Response(hit.body, hit);
    for (const [k, v] of Object.entries(WEB_HEADERS)) out.headers.set(k, v);
    return out;
  }
  if (env.WEB_LIMIT) {
    const { success } = await env.WEB_LIMIT.limit({ key: request.headers.get("CF-Connecting-IP") || "unknown" });
    if (!success) return new Response("too many requests", { status: 429, headers: { ...WEB_HEADERS, "Retry-After": "60" } });
  }
  const headers = { "Content-Type": "application/json; charset=utf-8", ...WEB_HEADERS };
  try {
    const res = await fetch(OV_RELEASE + r.file, { headers: { "User-Agent": "TagoHub" } });
    if (!res.ok || !res.body) throw new Error("HTTP " + res.status);
    const xml = await new Response(res.body.pipeThrough(new DecompressionStream("gzip"))).text();
    const rows = cctvRows(xml);
    if (!rows.length) throw new Error("empty");
    const stored = new Response(JSON.stringify(rows), { status: 200, headers: { ...headers, "Cache-Control": "public, max-age=" + r.ttl } });
    ctx.waitUntil(cache.put(key, stored.clone()));
    return stored;
  } catch (e) {
    return new Response(JSON.stringify({ code: "ERROR", message: String(e.message || e) }), { status: 502, headers });
  }
}

/** 웹 버전 한국공항공사 (proxy.js kacWebRequest). 키는 KAC_API_KEY (없으면 공공데이터포털 키) */
async function kacWeb(request, url, env, ctx) {
  const r = kacWebRequest(url.pathname, url.search);
  if (!r) return new Response("not allowed", { status: 404, headers: WEB_HEADERS });
  const raw = env.KAC_API_KEY || env.DATA_GO_KR_KEY;
  const upstream = raw ? `https://apis.data.go.kr/B551178/${r.path}?serviceKey=${encodeKey(raw)}${r.search}` : null;
  return cachedJson(request, env, ctx, "https://cache.tagohub/kac/" + r.path + "?" + r.search, upstream, r.ttl,
    (b) => /"resultCode"\s*:\s*"0*0"/.test(b));
}

/** 웹 버전 서울·경기 버스 (proxy.js busWebRequest). 키는 공공데이터포털 키. 정상(서울 headerCd 0·4, 경기 resultCode 0·4)만 저장 */
async function busWeb(request, url, env, ctx) {
  const r = busWebRequest(url.pathname);
  if (!r) return new Response("not allowed", { status: 404, headers: WEB_HEADERS });
  const raw = env.DATA_GO_KR_KEY;
  const ok = r.area === "seoul" ? (b) => /"headerCd"\s*:\s*"[04]"/.test(b)
    : r.area === "jeonju" ? (b) => /<code[^>]*>000</.test(b)
    // 제주는 배열로 답함 (노선 정류장·정류장 도착 모두. 도착은 STATION_ID 대신 ARRV_STATION_ID, 버스가 없으면 []). 노선 정보(line)만 객체
    : r.area === "jeju" ? (r.post ? (b) => /"routeId"\s*:/.test(b) : (b) => b.trimStart().startsWith("["))
    // 울산 BIS 는 XML(tableInfo), 정상은 resultCode 200
    : r.area === "ulsan" ? (b) => /<resultCode>200<\/resultCode>/.test(b)
    // 대전 BIS 도 XML(ServiceResult), 정상은 headerCd 0 (4 = 결과 없음)
    : r.area === "daejeon" ? (b) => /<headerCd>[04]<\/headerCd>/.test(b)
    : (b) => /"resultCode"\s*:\s*"?[04]"?\s*[,}]/.test(b);
  const upstream = !r.url.includes("KEY") ? r.url : raw ? r.url.replace("KEY", encodeKey(raw)) : null;
  return cachedJson(request, env, ctx, "https://cache.tagohub/" + r.cacheKey, upstream, r.ttl, ok,
    r.area === "jeonju" ? { Accept: "application/xml" } : {}, r.area === "jeonju" || r.area === "ulsan" || r.area === "daejeon", r.post ?? null);
}

/** 웹 버전 인천공항 운항 (proxy.js icnWebRequest). 정상(resultCode 00)만 1분 저장 */
async function icnWeb(request, url, env, ctx) {
  const r = icnWebRequest(url.pathname, url.search);
  if (!r) return new Response("not allowed", { status: 404, headers: WEB_HEADERS });
  const raw = env.DATA_GO_KR_KEY;
  return cachedJson(request, env, ctx, "https://cache.tagohub/" + r.cacheKey, raw ? r.url.replace("KEY", encodeKey(raw)) : null, r.ttl,
    (b) => /"resultCode"\s*:\s*"00"/.test(b));
}

/** 웹 버전 실시간 신호등 (proxy.js signalWebRequest). 서울은 SEOUL_SIGNAL_KEY, 울산은 KLID_BUS_KEY(없으면 공공데이터포털 키) */
async function signalWeb(request, url, env, ctx) {
  const r = signalWebRequest(url.pathname);
  if (!r) return new Response("not allowed", { status: 404, headers: WEB_HEADERS });
  if (r.kind === "seoul") {
    const key = (env.SEOUL_SIGNAL_KEY || "").trim();
    const upstream = key ? `https://t-data.seoul.go.kr/apig/apiman-gateway/tapi/v2xSignalPhaseTimingFusionCurrentInfo/1.0?apikey=${encodeURIComponent(key)}&itstId=${r.id}&type=json&pageNo=1&numOfRows=5` : null;
    return cachedJson(request, env, ctx, "https://cache.tagohub/signal/seoul/" + r.id, upstream, r.ttl, (b) => /itstId/.test(b));
  }
  const raw = env.KLID_BUS_KEY || env.DATA_GO_KR_KEY;
  const upstream = raw ? `https://apis.data.go.kr/B551982/rti/${r.op}?serviceKey=${encodeKey(raw)}&type=json&pageNo=1&numOfRows=1000&stdgCd=3100000000` : null;
  return cachedJson(request, env, ctx, "https://cache.tagohub/signal/ulsan/" + r.op, upstream, r.ttl, (b) => /crsrdId/.test(b));
}
