// TAGO 노선 정보(노선 정류장 목록·노선 기본 정보) 응답을 D1 에 저장해 나눠 씀.
// 공공데이터포털 TAGO 노선정보 서비스는 하루 요청 수가 정해져 있어, 서버 예약 작업(노선 모양·버스 자리 모양)이
// 저장 없이 매번 물으면 아침에 이미 한도가 차서 웹에서 노선이 열리지 않았음 (2026-11-16 "일일 서비스 요청제한 횟수 초과").
//  - 서버 안에서 쓸 때: 사흘 안에 받은 것이 있으면 다시 묻지 않음
//  - 웹 중계: 하루 안에 받은 것이 있으면 그것을 주고, 공공데이터가 한도 초과 등으로 실패하면 30일 안의 지난 응답을 줌

const KEEP = /getRouteAcctoThrghSttnList|getRouteInfoIem/;
export const DAY_MS = 24 * 3600 * 1000;
let ready = false;

async function ensure(db) {
  if (ready) return;
  await db.prepare("CREATE TABLE IF NOT EXISTS tago_store (k TEXT PRIMARY KEY, body TEXT NOT NULL, at INTEGER NOT NULL) WITHOUT ROWID").run();
  ready = true;
}

/** 저장할 요청이면 열쇠 ("stops:<도시>:<노선>" / "info:<도시>:<노선>"), 아니면 null */
export function storeKey(path, search) {
  if (!KEEP.test(path || "")) return null;
  const q = new URLSearchParams(search || "");
  const city = q.get("cityCode"), id = q.get("routeId");
  if (!/^\d{1,6}$/.test(city || "") || !/^[A-Za-z0-9_-]{1,32}$/.test(id || "")) return null;
  return (/ThrghSttn/.test(path) ? "stops:" : "info:") + city + ":" + id;
}

/** 저장해 둔 응답 (maxAgeMs 안에 받은 것만, 없으면 null) */
export async function getStored(db, k, maxAgeMs, now = Date.now()) {
  if (!db || !k) return null;
  try {
    await ensure(db);
    const r = await db.prepare("SELECT body, at FROM tago_store WHERE k = ?").bind(k).first();
    return r && now - r.at <= maxAgeMs ? r.body : null;
  } catch (_) { return null; }
}

/** 정상 응답 저장 */
export async function putStored(db, k, body, now = Date.now()) {
  if (!db || !k || !body) return;
  try {
    await ensure(db);
    await db.prepare("INSERT OR REPLACE INTO tago_store (k, body, at) VALUES (?, ?, ?)").bind(k, body, now).run();
  } catch (_) { /* 저장 못 함: 그냥 넘어감 */ }
}
