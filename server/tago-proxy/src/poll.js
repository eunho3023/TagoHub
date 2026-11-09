// 서버가 스스로 통과 기록 모으기 (정류장 시간표용).
// 사람들이 연 노선(관심도)을 골라, 예약 작업이 2분 간격으로 TAGO 노선별 버스 위치를 물어
// 정류장을 옮겨 간 버스의 통과 시각을 저장 (src/log.js hitsFromBusLc). 노선 모양용 GPS 칸도 같이 (src/gps.js).
// 공공데이터 하루 사용량을 지키려고 하루 POLL_PER_DAY(기본 3,000)번까지만, 오전 5시~자정(한국 시각)에만 물음.

import { upstreamUrl, webRequest, cacheable } from "./proxy.js";
import { hitsFromBusLc, prevStore } from "./log.js";
import { recordGps } from "./gps.js";
import { bucket } from "./shape.js";

const PER_DAY = 3000, PER_RUN = 3, ACTIVE = 6, GAP_MS = 2 * 60000, KEEP_MS = 7 * 86400000;
const ID = /^[A-Za-z0-9_-]{1,32}$/;
let ready = false;

async function ensure(db) {
  if (ready) return;
  await db.prepare("CREATE TABLE IF NOT EXISTS route_interest (route_id TEXT PRIMARY KEY, city TEXT, score REAL NOT NULL DEFAULT 0, at INTEGER NOT NULL, polled INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID").run();
  await db.prepare("CREATE TABLE IF NOT EXISTS poll_meta (k TEXT PRIMARY KEY, v INTEGER NOT NULL) WITHOUT ROWID").run();
  ready = true;
}

/** 누군가 이 노선의 버스 위치를 보면 관심도 +1 (예약 작업이 많이 보는 노선부터 모음) */
export async function noteInterest(db, routeId, city, nowMs = Date.now()) {
  if (!db || !ID.test(routeId || "") || !/^\d{1,6}$/.test(city || "")) return;
  await ensure(db);
  await db.prepare("INSERT INTO route_interest (route_id, city, score, at) VALUES (?, ?, 1, ?) ON CONFLICT(route_id) DO UPDATE SET score = score + 1, at = excluded.at, city = excluded.city")
    .bind(routeId, city, nowMs).run();
}

/** 한국 시각 날짜 문자열과 시 */
function kst(nowMs) {
  const d = new Date(nowMs + 9 * 3600000);
  return { day: d.toISOString().slice(0, 10), hour: d.getUTCHours(), minute: d.getUTCMinutes() };
}

/** 노선 정류장 순번 → 정류장 id (자료 조각 노선, 사이 정류장 통과 시각 채우기용) */
async function ordMap(env, routeId) {
  try {
    const r = await env.ASSETS.fetch(new Request(`https://assets.local/data/routes/${bucket(routeId)}.json`));
    if (!r.ok) return null;
    const rt = (await r.json())[routeId];
    if (!rt || !Array.isArray(rt.stops)) return null;
    return new Map(rt.stops.filter((s) => s[1]).map((s) => [+s[0], String(s[1])]));
  } catch (_) { return null; }
}

/** 예약 작업 (1분마다): 관심 노선 중 2분 넘게 안 물은 것 3개까지 물어 기록. {polled, left} 또는 쉬는 까닭 */
export async function pollCron(env, fetcher = fetch, nowMs = Date.now()) {
  const db = env.DB;
  if (!db || !env.DATA_GO_KR_KEY) return { skip: "no db/key" };
  const { day, hour, minute } = kst(nowMs);
  await ensure(db);
  // 관심도는 하루에 한 번(새벽 4시) 반으로 줄여 요즘 보는 노선을 앞으로
  if (hour === 4 && minute === 0) await db.prepare("UPDATE route_interest SET score = score / 2").run();
  if (hour < 5) return { skip: "night" };
  const limit = +env.POLL_PER_DAY > 0 ? +env.POLL_PER_DAY : PER_DAY;
  const used = (await db.prepare("SELECT v FROM poll_meta WHERE k = ?").bind("day:" + day).first())?.v || 0;
  if (used >= limit) return { skip: "daily limit", used };
  const rows = await db.prepare("SELECT route_id, city, polled FROM route_interest WHERE at > ? ORDER BY score DESC LIMIT ?").bind(nowMs - KEEP_MS, ACTIVE).all();
  const due = (rows.results || []).filter((r) => nowMs - r.polled >= GAP_MS - 10000).sort((a, b) => a.polled - b.polled).slice(0, Math.min(PER_RUN, limit - used));
  let n = 0;
  for (const r of due) {
    const w = webRequest("/w/dg/1613000/BusLcInfoInqireService/getRouteAcctoBusLcList", `?cityCode=${r.city}&routeId=${encodeURIComponent(r.route_id)}`);
    if (!w) continue;
    n++;
    try {
      const res = await fetcher(upstreamUrl(w.path, w.search, env.DATA_GO_KR_KEY), { headers: { Accept: "application/xml" } });
      const body = await res.text();
      if (cacheable(res.status, body)) {
        await hitsFromBusLc(db, prevStore(db), r.route_id, body, nowMs, await ordMap(env, r.route_id));
        await recordGps(db, r.route_id, r.city, body).catch(() => 0);
      }
    } catch (_) { /* 다음에 */ }
    await db.prepare("UPDATE route_interest SET polled = ? WHERE route_id = ?").bind(nowMs, r.route_id).run();
  }
  if (n) await db.prepare("INSERT INTO poll_meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = v + excluded.v").bind("day:" + day, n).run();
  return { polled: n, used: used + n };
}
