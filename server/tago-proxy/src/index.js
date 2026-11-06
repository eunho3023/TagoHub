// TagoHub 공공데이터 중계 서버 (Cloudflare Workers, 무료 요금제로 운영).
// - 공공데이터포털 키를 앱 대신 서버(비밀 값 DATA_GO_KR_KEY)에 둠 → 앱을 뜯어봐도 키가 없음
// - 같은 요청은 잠시 저장해 두고 나눠 줌 → 모든 사용자가 키 하나를 같이 써도 호출이 덜 나감
// - 앱은 X-TagoHub-Token 헤더(비밀 값 APP_TOKEN)를 보내야 함 → 아무나 이 서버를 키 대신 쓰지 못하게
import { cacheKey, cacheable, fillKeys, hostTarget, hostTtl, ttlFor, upstreamPath, upstreamUrl, webRequest, exWebRequest, exUrl, seoulWebRequest, seoulArrivalUrl, kakaoWebRequest, kacWebRequest, encodeKey, signalWebRequest, busWebRequest, icnWebRequest } from "./proxy.js";
import { handleLog, handleWebLog, hitsFromBusLc } from "./log.js";
import { handleShape, shapeCron } from "./shape.js";
import { recordGps } from "./gps.js";
import { handleReport } from "./report.js";

export default {
  // 예약 작업 (wrangler.toml [triggers]): 1분마다 노선 모양 하나를 미리 만들어 저장 (src/shape.js shapeCron)
  async scheduled(event, env, ctx) {
    ctx.waitUntil(shapeCron(env).catch(() => null));
  },
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname === "/health") return new Response("ok");
    if (url.pathname.startsWith("/w/")) return web(request, url, env, ctx);
    if (!env.APP_TOKEN || request.headers.get("X-TagoHub-Token") !== env.APP_TOKEN) {
      return new Response("forbidden", { status: 403 });
    }
    if (url.pathname.startsWith("/log/")) return handleLog(request, url, env, ctx);
    if (request.method !== "GET") return new Response("GET only", { status: 405 });
    if (url.pathname.startsWith("/h/")) return relay(request, url, env, ctx);
    const path = upstreamPath(url.pathname);
    if (!path) return new Response("not allowed", { status: 404 });
    if (!env.DATA_GO_KR_KEY) return new Response("server key missing", { status: 500 });

    return dataGo(path, url.search, request.headers.get("Accept"), env, ctx, {});
  },
};

const WEB_HEADERS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "GET, POST", "Access-Control-Allow-Headers": "Content-Type" };

/** 웹 버전: 토큰 없이 실시간 버스 도착·위치만 (proxy.js webRequest), 주소(IP)마다 분당 요청 수 제한 */
async function web(request, url, env, ctx) {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: WEB_HEADERS });
  // 앱 제보 (POST, 주소마다 분당 요청 수 제한을 같이 씀) — src/report.js
  if (url.pathname === "/w/report") {
    if (env.WEB_LIMIT) {
      const { success } = await env.WEB_LIMIT.limit({ key: "report:" + (request.headers.get("CF-Connecting-IP") || "unknown") });
      if (!success) return new Response("too many requests", { status: 429, headers: { ...WEB_HEADERS, "Retry-After": "60" } });
    }
    return handleReport(request, env, ctx);
  }
  if (request.method !== "GET") return new Response("GET only", { status: 405, headers: WEB_HEADERS });
  if (env.WEB_LIMIT && (url.pathname.startsWith("/w/shape/") || url.pathname.startsWith("/w/log/"))) {
    const { success } = await env.WEB_LIMIT.limit({ key: request.headers.get("CF-Connecting-IP") || "unknown" });
    if (!success) return new Response("too many requests", { status: 429, headers: { ...WEB_HEADERS, "Retry-After": "60" } });
  }
  if (url.pathname.startsWith("/w/shape/")) return handleShape(request, url, env, ctx);
  // 정류장 지난 운행 기록 (정류장 시간표, src/log.js)
  if (url.pathname === "/w/log/stop") return handleWebLog(url, env);
  if (url.pathname.startsWith("/w/ex/")) return exWeb(request, url, env, ctx);
  if (url.pathname.startsWith("/w/seoul/")) return seoulWeb(request, url, env, ctx);
  if (url.pathname.startsWith("/w/kakao/")) return kakaoWeb(request, url, env, ctx);
  if (url.pathname.startsWith("/w/kac/")) return kacWeb(request, url, env, ctx);
  if (url.pathname.startsWith("/w/signal/")) return signalWeb(request, url, env, ctx);
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

  const upstream = await fetch(upstreamUrl(path, search, env.DATA_GO_KR_KEY), {
    headers: { Accept: accept || "application/xml, text/xml;q=0.9, */*;q=0.8" },
  });
  const body = await upstream.text();
  const headers = {
    "Content-Type": upstream.headers.get("Content-Type") || "application/xml; charset=utf-8",
    "X-Cache": "MISS",
  };
  if (cacheable(upstream.status, body)) {
    const ttl = ttlFor(path);
    const stored = new Response(body, { status: 200, headers: { ...headers, "Cache-Control": `public, max-age=${ttl}` } });
    ctx.waitUntil(cache.put(key, stored.clone()));
    // 노선별 버스 위치(GPS)는 노선 모양 자료로 모음 (src/gps.js, 차량 번호·시각은 저장하지 않음)
    if (env.DB && /getRouteAcctoBusLcList/.test(path)) {
      const q = new URLSearchParams(search);
      ctx.waitUntil(recordGps(env.DB, q.get("routeId"), q.get("cityCode"), body).catch(() => 0));
      // 정류장을 옮겨 간 버스 → 지난 운행 기록 (정류장 시간표)
      ctx.waitUntil(hitsFromBusLc(env.DB, caches.default, q.get("routeId"), body).catch(() => 0));
    }
    const out = new Response(stored.body, stored);
    for (const [k, v] of Object.entries(extra)) out.headers.set(k, v);
    return out;
  }
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
  const upstream = await fetch(target, { headers });
  const body = await upstream.arrayBuffer();
  const text = new TextDecoder().decode(body.slice(0, 4000));
  const out = { "Content-Type": upstream.headers.get("Content-Type") || "application/octet-stream", "X-Cache": "MISS" };
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
async function cachedJson(request, env, ctx, cacheUrl, upstream, ttl, ok, extraHeaders = {}, allowXml = false) {
  const cache = caches.default;
  const key = new Request(cacheUrl);
  const hit = await cache.match(key);
  if (hit) {
    const out = new Response(hit.body, hit);
    out.headers.set("X-Cache", "HIT");
    for (const [k, v] of Object.entries(WEB_HEADERS)) out.headers.set(k, v);
    return out;
  }
  if (env.WEB_LIMIT) {
    const { success } = await env.WEB_LIMIT.limit({ key: request.headers.get("CF-Connecting-IP") || "unknown" });
    if (!success) return new Response("too many requests", { status: 429, headers: { ...WEB_HEADERS, "Retry-After": "60" } });
  }
  if (!upstream) return new Response("server key missing", { status: 500, headers: WEB_HEADERS });
  const headers = { "Content-Type": "application/json; charset=utf-8", "X-Cache": "MISS", ...WEB_HEADERS };
  let res, body;
  try {
    res = await fetch(upstream, { headers: { "User-Agent": MOBILE_UA, Accept: "application/json", ...extraHeaders } });
    body = await res.text();
  } catch {
    return new Response("{\"code\":\"ERROR\",\"message\":\"upstream fetch failed\"}", { status: 502, headers });
  }
  // allowXml: XML 로만 답하는 기관(전주시 버스)은 XML 도 그대로 저장·전달
  const json = /^[[{]/.test(body.trimStart()) || (allowXml && body.trimStart().startsWith("<"));
  if (!res.ok || !json || !ok(body)) {
    return new Response(json ? body : "{\"code\":\"ERROR\",\"message\":\"upstream " + res.status + "\"}", { status: 502, headers });
  }
  const stored = new Response(body, { status: 200, headers: { ...headers, "Cache-Control": "public, max-age=" + ttl } });
  ctx.waitUntil(cache.put(key, stored.clone()));
  return stored;
}

/** 웹 버전 한국도로공사: 허락한 기능만, 서버 키로 묻고 잠시 저장 (proxy.js exWebRequest) */
async function exWeb(request, url, env, ctx) {
  const r = exWebRequest(url.pathname, url.search);
  if (!r) return new Response("not allowed", { status: 404, headers: WEB_HEADERS });
  return cachedJson(request, env, ctx, "https://cache.tagohub/ex/" + r.path + "?pageNo=" + r.page,
    env.EX_API_KEY ? exUrl(r.path, r.page, env.EX_API_KEY) : null, r.ttl, (b) => /"code"\s*:\s*"SUCCESS"/i.test(b));
}

/** 웹 버전 서울 실시간 지하철 역 도착 (proxy.js seoulWebRequest). "데이터 없음"(INFO-200)도 정상으로 저장 */
async function seoulWeb(request, url, env, ctx) {
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
    : (b) => /"resultCode"\s*:\s*"?[04]"?\s*[,}]/.test(b);
  return cachedJson(request, env, ctx, "https://cache.tagohub/" + r.cacheKey, raw ? r.url.replace("KEY", encodeKey(raw)) : null, r.ttl, ok,
    r.area === "jeonju" ? { Accept: "application/xml" } : {}, r.area === "jeonju");
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
