// TagoHub 공공데이터 중계 서버 (Cloudflare Workers, 무료 요금제로 운영).
// - 공공데이터포털 키를 앱 대신 서버(비밀 값 DATA_GO_KR_KEY)에 둠 → 앱을 뜯어봐도 키가 없음
// - 같은 요청은 잠시 저장해 두고 나눠 줌 → 모든 사용자가 키 하나를 같이 써도 호출이 덜 나감
// - 앱은 X-TagoHub-Token 헤더(비밀 값 APP_TOKEN)를 보내야 함 → 아무나 이 서버를 키 대신 쓰지 못하게
import { cacheKey, cacheable, fillKeys, hostTarget, hostTtl, ttlFor, upstreamPath, upstreamUrl } from "./proxy.js";

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname === "/health") return new Response("ok");
    if (request.method !== "GET") return new Response("GET only", { status: 405 });
    if (!env.APP_TOKEN || request.headers.get("X-TagoHub-Token") !== env.APP_TOKEN) {
      return new Response("forbidden", { status: 403 });
    }
    if (url.pathname.startsWith("/h/")) return relay(request, url, env, ctx);
    const path = upstreamPath(url.pathname);
    if (!path) return new Response("not allowed", { status: 404 });
    if (!env.DATA_GO_KR_KEY) return new Response("server key missing", { status: 500 });

    const cache = caches.default;
    const key = new Request(cacheKey(path, url.search));
    const hit = await cache.match(key);
    if (hit) {
      const r = new Response(hit.body, hit);
      r.headers.set("X-Cache", "HIT");
      return r;
    }

    const upstream = await fetch(upstreamUrl(path, url.search, env.DATA_GO_KR_KEY), {
      headers: { Accept: request.headers.get("Accept") || "application/xml, text/xml;q=0.9, */*;q=0.8" },
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
      return stored;
    }
    return new Response(body, { status: upstream.status, headers });
  },
};

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
