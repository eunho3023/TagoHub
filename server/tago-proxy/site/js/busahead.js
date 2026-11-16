// 실시간 버스를 늦은 만큼 앞으로 당겨 그리기 (앱 BusAhead.kt 와 같은 규칙).
// 버스 위치 API 는 받는 순간 이미 15~30초 전 위치다 (2026-11-16 실측: 서울은 약 14초마다 바뀌고 받을 때 보통 28초 전,
// TAGO 는 보통 23~27초마다 바뀜). 마지막 두 위치로 속도·방향을 구해 "자료 나이 + 받은 뒤 지난 시간" 만큼 그 방향으로 더 간 자리에 그림.
// 다음 정류장은 넘지 않고, 멈춘 버스·너무 옛 자료는 당기지 않음.

export const UNKNOWN_AGE_MS = 15000, MAX_AGE_MS = 60000, MAX_LEAD_MS = 40000, MAX_AHEAD_M = 250;
export const MAX_SPEED_MPS = 70 / 3.6, MIN_SPEED_MPS = 1, STOP_SIDE_M = 35, STOP_MIN_AHEAD_M = 10;
// 달리던 속도로 늦은 시간의 절반만 당김 (정류장·신호에서 서는 시간 때문. 서울 실측: 받은 자리 그대로 평균 109m, 다 당김 103m, 절반 94m)
export const LEAD_FACTOR = 0.5;
const KY = 110540, kx = (lat) => 111320 * Math.cos((lat * Math.PI) / 180);

export const meters = (a, b) => Math.hypot((a[0] - b[0]) * KY, (a[1] - b[1]) * kx(a[0]));

/** a → b 방위(도, 북=0) */
export const bearing = (a, b) => (Math.atan2((b[1] - a[1]) * kx(a[0]), (b[0] - a[0]) * KY) * 180) / Math.PI;

/** from → to 를 durMs 동안 간 속도(m/s). 모르거나 튀면 0 */
export function speed(from, to, durMs) {
  if (!from || !(durMs > 0)) return 0;
  const v = meters(from, to) / (durMs / 1000);
  return v < MIN_SPEED_MPS || v > MAX_SPEED_MPS ? 0 : v;
}

/** 받은 순간 자료 나이(ms): 자료 시각을 알면 그것, 모르면 UNKNOWN_AGE_MS */
export const ageAt = (receivedMs, dataAtMs) => (dataAtMs == null ? UNKNOWN_AGE_MS : Math.max(0, receivedMs - dataAtMs));

/** now 에 받은 위치에서 얼마나(m) 앞으로 그릴지 */
export function aheadM(speedMps, receivedMs, ageMs, nowMs, capM = MAX_AHEAD_M) {
  if (!(speedMps > 0) || ageMs > MAX_AGE_MS) return 0;
  const lead = Math.max(0, Math.min(MAX_LEAD_MS, ageMs + (nowMs - receivedMs)));
  return Math.max(0, Math.min(((speedMps * lead) / 1000) * LEAD_FACTOR, capM, MAX_AHEAD_M));
}

/** p 에서 deg 쪽으로 곧장 갈 때 만나는 가장 가까운 정류장까지 거리(m). 없으면 null */
export function stopAhead(p, deg, stops, maxM = MAX_AHEAD_M) {
  const r = (deg * Math.PI) / 180, ux = Math.sin(r), uy = Math.cos(r), k = kx(p[0]);
  let best = null;
  for (const s of stops) {
    const dx = (s[1] - p[1]) * k, dy = (s[0] - p[0]) * KY, along = dx * ux + dy * uy;
    if (along < STOP_MIN_AHEAD_M || along > maxM || Math.abs(dx * uy - dy * ux) > STOP_SIDE_M) continue;
    if (best == null || along < best) best = along;
  }
  return best;
}

/** p 에서 deg 쪽으로 m 미터 간 점 */
export function move(p, deg, m) {
  if (!(m > 0)) return p;
  const r = (deg * Math.PI) / 180;
  return [p[0] + (m * Math.cos(r)) / KY, p[1] + (m * Math.sin(r)) / kx(p[0])];
}

/** 서울 dataTm "20261116201530" (한국 시각) → epoch ms */
export function seoulTime(s) {
  const t = String(s ?? "").trim();
  if (!/^\d{14}$/.test(t)) return null;
  const ms = Date.parse(`${t.slice(0, 4)}-${t.slice(4, 6)}-${t.slice(6, 8)}T${t.slice(8, 10)}:${t.slice(10, 12)}:${t.slice(12, 14)}+09:00`);
  return Number.isFinite(ms) ? ms : null;
}

/**
 * 버스 한 대의 기록에 새 위치를 넣음. t = {raw, at, dataAt, v, deg, age, still} (처음이면 null).
 * 위치가 그대로면: 자료 시각이 새로우면 멈춤(still), 같거나 모르면 그대로 둠 (TAGO 는 자료가 23~90초마다 바뀌어 멈춤을 알 수 없음)
 */
export function update(t, p, now, dataAt = null, minMoveM = 8) {
  if (!t) return { raw: p, at: now, dataAt, v: 0, deg: null, age: ageAt(now, dataAt), still: false };
  const d = meters(t.raw, p);
  if (d < minMoveM) {
    const newer = dataAt != null && (t.dataAt == null || dataAt > t.dataAt);
    return newer ? { ...t, still: true } : t;
  }
  const dt = dataAt != null && t.dataAt != null && dataAt > t.dataAt ? dataAt - t.dataAt : Math.max(5000, Math.min(30000, now - t.at));
  return { raw: p, at: now, dataAt, v: speed(t.raw, p, dt), deg: d >= 15 ? bearing(t.raw, p) : t.deg, age: ageAt(now, dataAt), still: false };
}

/** 지금 그릴 자리 */
export function predict(t, stops, now = Date.now()) {
  if (!t || t.still || t.deg == null || !(t.v > 0)) return t ? t.raw : null;
  const cap = stopAhead(t.raw, t.deg, stops) ?? MAX_AHEAD_M;
  return move(t.raw, t.deg, aheadM(t.v, t.at, t.age, now, cap));
}
