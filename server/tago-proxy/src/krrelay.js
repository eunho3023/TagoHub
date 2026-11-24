/**
 * 국내 중계 (집 노트북 Ubuntu 에서 도는 server/relay/relay.mjs, 예전엔 이 PC, Cloudflare 빠른 터널 *.trycloudflare.com)
 *
 * 왜: Cloudflare → 공공데이터포털(apis.data.go.kr) 연결이 자주 매달림 (2026-11-22 잰 것: 10번 중 8번 15초 넘게 답 없음,
 * 새 연결로 다시 물어도 같음). 같은 키로 국내 PC 에서 물으면 늘 0.2~2.6초.
 * → 공공데이터포털 요청은 국내 중계를 먼저 거치고, 중계가 꺼져 있거나 답이 없으면 예전처럼 바로 물음 (hedgedFetch).
 *
 * 중계는 켜질 때와 5분마다 POST /w/relay/register {url} (X-Relay-Key) 로 지금 터널 주소를 알림 (빠른 터널은 켤 때마다 주소가 바뀜).
 * 15분 넘게 소식이 없으면 꺼진 것으로 봄.
 */
import { hedgedFetch } from "./hedge.js";

// 브이월드(api.vworld.kr)는 Cloudflare 에서 물으면 HTTP 520·빈 답 (2026-11-23 배포 뒤 잰 것, 국내 PC 에서는 잘 됨)
// 제주 버스정보(bus.jeju.go.kr)는 Cloudflare 에서 바로 물으면 504 (2026-11-24 시간표·노선 정보 모두) → 중계로
export const RELAY_HOSTS = new Set(["apis.data.go.kr", "api.vworld.kr", "bus.jeju.go.kr"]);
const TUNNEL = /^https:\/\/[a-z0-9-]{1,80}\.trycloudflare\.com$/;
const STALE_MS = 15 * 60 * 1000;
// 중계가 기관에 스스로 겹쳐 물으므로(1.2초마다 최대 3개, relay.mjs askUpstream) 중계에 다시 묻는 건 4초 뒤 (터널이 잠깐 끊겼을 때용)
export const RELAY_HEDGE_MS = 4000, RELAY_TOTAL_MS = 13000;
const DOWN_MS = 30 * 1000, FAILS_TO_SKIP = 3, DIRECT_TOTAL_MS = 10000;

let memo = { at: 0, url: null };
let downUntil = 0, fails = 0;

/** 같은 문자열인지 (시간 차로 값을 알아내지 못하게) */
function same(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || !a || a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

async function table(db) {
  await db.prepare("CREATE TABLE IF NOT EXISTS kr_relay (id INTEGER PRIMARY KEY, url TEXT NOT NULL, at INTEGER NOT NULL)").run();
}

/** POST /w/relay/register: 중계가 지금 주소를 알림. GET /w/relay/status: 켜져 있는지 (주소는 내보내지 않음) */
export async function handleRelay(request, url, env) {
  const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", "Access-Control-Allow-Origin": "*" } });
  if (!env.DB) return json({ error: "no db" }, 500);
  if (url.pathname === "/w/relay/status") {
    const r = await currentRow(env).catch(() => null);
    return json({ on: !!r, at: r ? r.at : null });
  }
  if (url.pathname !== "/w/relay/register" || request.method !== "POST") return json({ error: "not allowed" }, 404);
  if (!same(request.headers.get("X-Relay-Key"), env.RELAY_KEY)) return json({ error: "forbidden" }, 403);
  const body = await request.json().catch(() => ({}));
  const u = String(body.url || "").replace(/\/$/, "");
  if (!TUNNEL.test(u)) return json({ error: "bad url" }, 400);
  await table(env.DB);
  await env.DB.prepare("INSERT INTO kr_relay (id, url, at) VALUES (1, ?, ?) ON CONFLICT(id) DO UPDATE SET url = excluded.url, at = excluded.at").bind(u, Date.now()).run();
  memo = { at: Date.now(), url: u };
  downUntil = 0;
  return json({ ok: true });
}

async function currentRow(env) {
  await table(env.DB);
  const r = await env.DB.prepare("SELECT url, at FROM kr_relay WHERE id = 1").first();
  return r && Date.now() - r.at < STALE_MS ? r : null;
}

/** 지금 쓸 중계 주소 (30초 기억). 없거나 꺼졌으면 null */
export async function relayUrl(env) {
  if (!env.DB || !env.RELAY_KEY) return null;
  if (Date.now() - memo.at < 30000) return memo.url;
  const r = await currentRow(env).catch(() => null);
  memo = { at: Date.now(), url: r ? r.url : null };
  return memo.url;
}

/**
 * 바깥 기관에 묻기: 공공데이터포털 GET 은 국내 중계 먼저, 중계가 꺼져 있으면 바로 (hedgedFetch).
 *
 * 중계에 물을 때도 4초(예전 2.5초) 안에 답이 없으면 하나 더 물음 (최대 2번, 13초): 전주시 도착처럼 기관이 가끔 7초씩 늦게 답해서
 * (2026-11-22 잰 것: 같은 정류장 0.15~7초). 예전엔 6초에 끊고 바로 묻기로 넘어가 15~20초를 더 기다렸고(바로 묻기는 거의 안 됨),
 * 그 뒤 1분 동안 중계를 건너뛰어 그동안 모든 요청이 느렸음.
 * - 중계가 답함(X-Relay-Upstream, 기관이 늦거나 오류여도): 그 답을 씀. 중계는 살아 있으니 건너뛰지 않음
 * - 중계가 꺼짐(연결 실패·터널 오류처럼 X-Relay-Upstream 없는 답): 1분 동안 건너뛰고 바로 물음
 * - 13초 안에 아무 답이 없음: 중계가 멈춘 것으로 보고 1분 건너뜀, 이번 요청은 504 (바로 묻기로 15초 더 기다리지 않음)
 */
export async function upstreamFetch(target, init = {}, env = {}, { fetcher = fetch, hedgeMs = RELAY_HEDGE_MS, totalMs = RELAY_TOTAL_MS } = {}) {
  let host = "";
  try { host = new URL(target).hostname; } catch { /* 아래에서 바로 물음 */ }
  const get = !init.method || init.method === "GET";
  if (!get || !RELAY_HOSTS.has(host)) return hedgedFetch(target, init, { fetcher });
  // 왜 바로 물었는지 (X-Relay-Why): 배포 뒤 바로 묻기가 섞이는 까닭을 보려고
  let why = Date.now() < downUntil ? "down" : null;
  const base = why ? null : await relayUrl(env);
  if (!why && !base) why = "nourl";
  if (base) {
    let err = null;
    const res = await hedgedFetch(base + "/f?u=" + encodeURIComponent(target), { headers: { ...(init.headers || {}), "X-Relay-Key": env.RELAY_KEY } },
      { fetcher, hedgeMs, tries: 2, totalMs }).catch((e) => { err = e; return null; });
    if (res && res.headers.get("X-Relay-Upstream")) {
      fails = 0;
      const out = new Response(res.body, res);
      out.headers.set("X-Via", "kr-relay");
      return out;
    }
    // 한 번 실패로는 건너뛰지 않음: 잇달아 FAILS_TO_SKIP 번 실패해야 잠깐 건너뜀
    // (예전엔 한 번 실패하면 1분 동안 그 서버 실행 단위의 모든 요청이 바로 묻기(대부분 15초 매달림)로 갔음)
    if (++fails >= FAILS_TO_SKIP) { downUntil = Date.now() + DOWN_MS; fails = 0; }
    if (res && res.headers.get("X-Timeout")) return tag(new Response("relay timeout", { status: 504 }), "kr-relay", "timeout");
    why = res ? "status " + res.status : "error " + String((err && (err.name + " " + err.message)) || "").slice(0, 80);
  }
  // 바로 묻기는 Cloudflare → 공공데이터포털이 자주 매달려서 10초까지만
  const out = await hedgedFetch(target, init, { fetcher, totalMs: DIRECT_TOTAL_MS });
  return tag(out, "direct", why);
}
function tag(res, via, why) {
  const out = new Response(res.body, res);
  out.headers.set("X-Via", via);
  if (why) out.headers.set("X-Relay-Why", why.replace(/[^ -~]/g, "?"));
  return out;
}

/** 시험용: 기억한 것 지우기 */
export function _reset() { memo = { at: 0, url: null }; downUntil = 0; fails = 0; }
