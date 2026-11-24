// 이 기기에만 남기는 것: 즐겨찾기·최근 본 것·집/회사·화면 모드.
// 저장 이름과 모양은 Compose 웹 버전(WebStore.kt)과 같아서 바꾼 뒤에도 그대로 이어짐 (칸은 탭, 줄은 줄바꿈).
import { storeGet, storeSet } from "./core.js";

const clean = (s) => String(s ?? "").replace(/[\t\n]/g, " ");
const lines = (k) => (storeGet(k) || "").split("\n").filter((l) => l.trim());

// 정류장: id, 이름, 위도, 경도, 번호, 시군
export function encStop(s) { return [s.id, s.name, s.lat, s.lng, s.no || "", s.city].map(clean).join("\t"); }
export function decStop(l) {
  const f = l.split("\t");
  if (f.length < 6) return null;
  const lat = parseFloat(f[2]), lng = parseFloat(f[3]);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  return { id: f[0], name: f[1], lat, lng, no: f[4] || null, city: f[5], routes: [] };
}
// 지하철역: id, 이름, 노선
export function encStation(s) { return [s.id, s.name, s.line].map(clean).join("\t"); }
export function decStation(l) { const f = l.split("\t"); return f.length < 3 ? null : { id: f[0], name: f[1], line: f[2] }; }
// 장소: 이름, 위도, 경도, 주소
export function encPlace(p) { return [p.name, p.lat, p.lng, p.address || ""].map(clean).join("\t"); }
export function decPlace(l) {
  const f = l.split("\t");
  if (f.length < 3) return null;
  const lat = parseFloat(f[1]), lng = parseFloat(f[2]);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  return { name: f[0], lat, lng, address: f[3] || null };
}

function list(key, dec, enc, max, front) {
  let items = lines(key).map(dec).filter(Boolean);
  const save = () => storeSet(key, items.map(enc).join("\n"));
  return {
    get items() { return items; },
    add(x, same) {
      items = items.filter((y) => !same(y, x));
      items = front ? [x, ...items].slice(0, max) : [...items, x].slice(-max);
      save();
    },
    remove(same) { items = items.filter((y) => !same(y)); save(); },
    clear() { items = []; save(); },
  };
}

const byId = (a, b) => a.id === b.id;
const samePlace = (a, b) => a.name === b.name && a.lat === b.lat && a.lng === b.lng;

export const Favorites = {
  stops: list("favStops", decStop, encStop, 30, false),
  stations: list("favStations", decStation, encStation, 30, false),
  hasStop(s) { return this.stops.items.some((x) => x.id === s.id); },
  hasStation(s) { return this.stations.items.some((x) => x.id === s.id); },
  toggleStop(s) { this.hasStop(s) ? this.stops.remove((x) => x.id === s.id) : this.stops.add({ ...s, routes: [] }, byId); },
  toggleStation(s) { this.hasStation(s) ? this.stations.remove((x) => x.id === s.id) : this.stations.add(s, byId); },
};

export const Recent = {
  stops: list("recentStops", decStop, encStop, 10, true),
  stations: list("recentStations", decStation, encStation, 10, true),
  places: list("recentPlaces", decPlace, encPlace, 10, true),
  addStop(s) { this.stops.add(s, byId); },
  addStation(s) { this.stations.add(s, byId); },
  addPlace(p) { this.places.add(p, samePlace); },
};

export const HomeWork = {
  get home() { return decPlace(storeGet("home") || ""); },
  get work() { return decPlace(storeGet("work") || ""); },
  get school() { return decPlace(storeGet("school") || ""); },
  saveHome(p) { storeSet("home", encPlace(p)); },
  saveWork(p) { storeSet("work", encPlace(p)); },
  saveSchool(p) { storeSet("school", encPlace(p)); },
};

/** 화면 모드: auto(기기 설정) · light · dark */
export const Theme = {
  get mode() { const m = storeGet("theme"); return ["auto", "light", "dark"].includes(m) ? m : "auto"; },
  set(m) { storeSet("theme", m); applyTheme(); },
};

export function applyTheme() {
  const m = Theme.mode;
  const dark = m === "dark" || (m === "auto" && matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
}

// ── 시간대별 홈: 정류장을 언제 여는지 (평일·주말 × 시각 24칸, 한국 시각). 이 기기에만 ──
const USAGE_MAX = 80;
/** 칸 번호: 평일 0~23, 주말 24~47 */
export function slotOf(ms) { const d = new Date(ms + 9 * 3600e3), we = d.getUTCDay() === 0 || d.getUTCDay() === 6; return (we ? 24 : 0) + d.getUTCHours(); }
/** 지금 시간대에 얼마나 자주 열었는지 (같은 시각 2배, 앞뒤 한 시간 1배) */
export function timeScore(counts, ms) {
  if (!counts) return 0;
  const s = slotOf(ms), base = s - (s % 24), h = s % 24;
  return 2 * (counts[base + h] || 0) + (counts[base + ((h + 23) % 24)] || 0) + (counts[base + ((h + 1) % 24)] || 0);
}
/** 지금 시간대에 자주 연 것을 앞으로 (점수가 같으면 원래 순서) */
export function byTime(items, usage, ms) {
  return items.map((x, i) => ({ x, i, v: timeScore(usage[x.id], ms) })).sort((a, b) => b.v - a.v || a.i - b.i).map((o) => o.x);
}
export const Usage = {
  load() { try { const u = JSON.parse(storeGet("usage") || "{}"); return u && typeof u === "object" ? u : {}; } catch (e) { return {}; } },
  visit(id, ms = Date.now()) {
    if (!id) return;
    const u = this.load(), c = Array.isArray(u[id]) ? u[id] : new Array(48).fill(0);
    c[slotOf(ms)] = Math.min(999, (c[slotOf(ms)] || 0) + 1);
    u[id] = c;
    const ids = Object.keys(u);
    if (ids.length > USAGE_MAX) {
      const sum = (k) => u[k].reduce((a, b) => a + (b || 0), 0);
      ids.sort((a, b) => sum(a) - sum(b)).slice(0, ids.length - USAGE_MAX).forEach((k) => { if (k !== id) delete u[k]; });
    }
    storeSet("usage", JSON.stringify(u));
  },
};
