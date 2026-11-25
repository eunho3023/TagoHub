// 공공데이터포털 실제 요청 수 세기 (저장에서 준 것은 빼고, 기관에 직접 물은 것만).
// 2026-11-25 TAGO 버스 위치 하루 한도가 아침 7시에 찼는데, 무엇이 썼는지 알 길이 없었음.
// 한국 시각 날짜·시·서비스·쓴 곳(web 웹 / app 앱 / cron 예약 작업)마다 셈. D1 쓰기를 줄이려고 모아 두었다가 20초마다 한꺼번에 씀.
// GET /w/use?day=YYYY-MM-DD → 서비스별 합계와 시간대별 수 (개인 정보 없음). 7일 넘은 줄은 지움.

import { quotaService } from "./health.js";

const FLUSH_MS = 20000;
const pending = new Map();
let lastFlush = 0, ready = false;

/** 한국 시각 "YYYY-MM-DD", 시 */
function kst(now) {
  const d = new Date(now + 9 * 3600000);
  return { day: d.toISOString().slice(0, 10), hour: d.getUTCHours() };
}

/** 저장 이름: 날짜|시(두 자리)|서비스|쓴 곳 */
export function useKey(svc, src, now = Date.now()) {
  const { day, hour } = kst(now);
  return `${day}|${String(hour).padStart(2, "0")}|${svc}|${src}`;
}

async function ensure(db) {
  if (ready) return;
  await db.prepare("CREATE TABLE IF NOT EXISTS api_use (k TEXT PRIMARY KEY, n INTEGER NOT NULL) WITHOUT ROWID").run();
  ready = true;
}

/** 모아 둔 수를 D1 에 씀 */
export async function flushUse(db, now = Date.now()) {
  if (!db || !pending.size) return 0;
  const rows = [...pending];
  pending.clear();
  lastFlush = now;
  try {
    await ensure(db);
    const st = db.prepare("INSERT INTO api_use (k, n) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET n = n + excluded.n");
    await db.batch(rows.map(([k, n]) => st.bind(k, n)));
    return rows.length;
  } catch (_) { return 0; }
}

/**
 * 기관에 한 번 물었음. target 은 "1613000/BusLcInfoInqireService/..." 같은 경로나 주소 (공공데이터포털 것만 셈).
 * now 가 지난 번 쓴 때보다 20초 넘게 지났거나 force 면 바로 씀 (예약 작업은 force)
 */
export async function noteUse(db, target, src, now = Date.now(), force = false) {
  const svc = quotaService(target);
  if (!db || !svc) return false;
  const k = useKey(svc, src, now);
  pending.set(k, (pending.get(k) || 0) + 1);
  if (force || now - lastFlush >= FLUSH_MS) await flushUse(db, now);
  return true;
}

/** 하루치 보기: { day, total: {서비스: 수}, bySrc: {서비스: {쓴 곳: 수}}, hours: [{h, svc, src, n}] } */
export async function useReport(db, day) {
  await ensure(db);
  await flushUse(db);
  const rows = ((await db.prepare("SELECT k, n FROM api_use WHERE k >= ? AND k < ?").bind(day + "|", day + "|~").all()).results || []);
  const total = {}, bySrc = {}, hours = [];
  for (const r of rows) {
    const [, h, svc, src] = r.k.split("|");
    total[svc] = (total[svc] || 0) + r.n;
    (bySrc[svc] ||= {})[src] = (bySrc[svc][src] || 0) + r.n;
    hours.push({ h: +h, svc, src, n: r.n });
  }
  hours.sort((a, b) => a.h - b.h || a.svc.localeCompare(b.svc) || a.src.localeCompare(b.src));
  return { day, total, bySrc, hours };
}

/** 7일 넘은 줄 지움 */
export async function pruneUse(db, now = Date.now()) {
  await ensure(db);
  return db.prepare("DELETE FROM api_use WHERE k < ?").bind(kst(now - 7 * 86400000).day + "|").run();
}

/** 시험용 */
export function _resetUse() { pending.clear(); lastFlush = 0; ready = false; }
