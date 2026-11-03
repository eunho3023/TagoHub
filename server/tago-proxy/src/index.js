// TagoHub 공공데이터 중계 서버 (Cloudflare Workers, 무료 요금제로 운영).
// - 공공데이터포털 키를 앱 대신 서버(비밀 값 DATA_GO_KR_KEY)에 둠 → 앱을 뜯어봐도 키가 없음
// - 같은 요청은 잠시 저장해 두고 나눠 줌 → 모든 사용자가 키 하나를 같이 써도 호출이 덜 나감
// - 앱은 X-TagoHub-Token 헤더(비밀 값 APP_TOKEN)를 보내야 함 → 아무나 이 서버를 키 대신 쓰지 못하게
import { cacheKey, cacheable, ttlFor, upstreamPath, upstreamUrl } from "./proxy.js";

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname === "/health") return new Response("ok");
    if (request.method !== "GET") return new Response("GET only", { status: 405 });
    if (!env.APP_TOKEN || request.headers.get("X-TagoHub-Token") !== env.APP_TOKEN) {
      return new Response("forbidden", { status: 403 });
    }
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
