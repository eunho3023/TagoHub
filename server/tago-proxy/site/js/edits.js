// 지도 고치기: 사람이 손으로 고친 버스 노선 모양·정류장 위치 (서버 /w/edits, src/edits.js). 폰 앱(MapEdits.kt)과 같은 자료.
// 화면을 열 때 한 번 받아 두고(1분 기억), 노선 모양·정류장 좌표를 그릴 때 고친 것이 있으면 그것을 씀.
import { BASE, getJson } from "./core.js";

let data = null, at = 0, loading = null;

// ── 관리자 키: 고치기는 관리자만 (서버 비밀 값 EDIT_ADMIN_KEY 와 같아야 저장됨). 이 브라우저에만 저장 ──
const ADMIN_KEY = "tagohub.adminKey";
export function adminKey() { try { return localStorage.getItem(ADMIN_KEY) || ""; } catch (e) { return ""; } }
/** 관리자 키를 넣은 기기인지 (고치기 단추를 보일지) */
export const isAdmin = () => adminKey().length >= 16;
/** 키 저장 전 서버에 맞는지 물어봄 → true/false (빈 글자면 지우고 false) */
export async function setAdminKey(k) {
  k = String(k || "").trim();
  if (!k) { try { localStorage.removeItem(ADMIN_KEY); } catch (e) { /* 못 씀 */ } return false; }
  const r = await fetch(BASE + "w/edits/check", { headers: { "X-TagoHub-Admin": k } }).catch(() => null);
  if (!r || !r.ok) return false;
  try { localStorage.setItem(ADMIN_KEY, k); } catch (e) { /* 못 씀 */ }
  return true;
}
const EMPTY = { v: 0, bus: {}, rail: {}, stop: {}, station: {} };

/** 고친 것 목록 (1분 기억, 못 받으면 빈 목록) */
export function ready(force = false) {
  if (!force && data && Date.now() - at < 60000) return Promise.resolve(data);
  if (!force && loading) return loading;
  loading = getJson("w/edits").then((d) => ({ ...EMPTY, ...d })).catch(() => data || EMPTY)
    .then((d) => { data = d; at = Date.now(); loading = null; return d; });
  return loading;
}

/** 받아 둔 목록에서 바로 (없으면 null) */
export function stopFix(id) { return (data && id && (data.stop[id] || null)) || null; }
export function busFix(id) { return (data && id && data.bus[id]) || null; }

/** 저장 (data 가 null 이면 원래대로). 성공하면 목록을 다시 받음 */
export async function save(kind, id, payload) {
  const r = await fetch(BASE + "w/edits", { method: "POST", headers: { "Content-Type": "application/json", "X-TagoHub-Admin": adminKey() }, body: JSON.stringify({ kind, id, data: payload }) });
  if (!r.ok) throw new Error(r.status === 429 ? "잠시 뒤에 다시 저장해 주세요" : r.status === 403 ? "관리자만 고칠 수 있어요. 설정에서 관리자 키를 확인해 주세요" : "저장하지 못했어요 (" + r.status + ")");
  // 서버 목록은 30초 저장돼서 방금 고친 것을 바로 반영
  if (data) {
    if (kind === "bus" || kind === "rail") { if (payload) data[kind][id] = encodePolyline(payload); else delete data[kind][id]; }
    else if (payload) data[kind][id] = payload; else delete data[kind][id];
  }
  return true;
}

// ── 선 고치기 계산 (앱 data/EditPath.kt 와 같음) ──
const mx = (lat) => 111000 * Math.cos((lat * Math.PI) / 180);
/** 두 점 사이 m */
export function dist(a, b) { const dy = (b[0] - a[0]) * 111000, dx = (b[1] - a[1]) * mx(a[0]); return Math.hypot(dx, dy); }
/** 점 p 에서 선분 a-b 까지 m 와 가장 가까운 비율 t */
export function toSeg(p, a, b) {
  const k = mx(p[0]), ax = (a[1] - p[1]) * k, ay = (a[0] - p[0]) * 111000, bx = (b[1] - p[1]) * k, by = (b[0] - p[0]) * 111000;
  const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
  const t = l2 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2)) : 0;
  return { d: Math.hypot(ax + t * dx, ay + t * dy), t };
}
/** 점 줄이기 (더글러스-포커, tol m): 고칠 점이 너무 많지 않게 */
export function simplify(pts, tol = 6) {
  if (pts.length < 3) return pts.slice();
  const keep = new Uint8Array(pts.length); keep[0] = keep[pts.length - 1] = 1;
  const stack = [[0, pts.length - 1]];
  while (stack.length) {
    const [i, j] = stack.pop();
    let best = -1, bd = tol;
    for (let k = i + 1; k < j; k++) { const d = toSeg(pts[k], pts[i], pts[j]).d; if (d > bd) { bd = d; best = k; } }
    if (best >= 0) { keep[best] = 1; stack.push([i, best], [best, j]); }
  }
  return pts.filter((_, i) => keep[i]);
}
/** 누른 곳에 새 점 넣기: 가장 가까운 선분 사이에 (선에서 maxM 넘게 떨어지면 가까운 끝에 이음) → 넣은 자리 */
export function insertNear(pts, p, maxM = 80) {
  let best = -1, bd = Infinity;
  for (let i = 0; i < pts.length - 1; i++) { const d = toSeg(p, pts[i], pts[i + 1]).d; if (d < bd) { bd = d; best = i; } }
  if (best >= 0 && bd <= maxM) { pts.splice(best + 1, 0, p); return best + 1; }
  if (!pts.length || dist(p, pts[0]) < dist(p, pts[pts.length - 1])) { pts.unshift(p); return 0; }
  pts.push(p); return pts.length - 1;
}

/** [[위도,경도]] → 폴리라인 글자 (정밀도 5) */
export function encodePolyline(pts) {
  let out = "", pl = 0, pg = 0;
  const num = (v) => { let n = v < 0 ? -2 * v - 1 : 2 * v, s = ""; while (n >= 0x20) { s += String.fromCharCode((0x20 | (n % 32)) + 63); n = Math.floor(n / 32); } return s + String.fromCharCode(n + 63); };
  for (const [la, lo] of pts) { const a = Math.round(la * 1e5), b = Math.round(lo * 1e5); out += num(a - pl) + num(b - pg); pl = a; pg = b; }
  return out;
}
