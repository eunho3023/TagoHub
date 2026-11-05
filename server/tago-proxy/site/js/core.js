// 공통: 서버 묻기, 응답 해석(XML·JSON), 이 기기 저장, 시간·거리·글자 도우미.
// 서버 주소는 이 페이지를 내준 서버. 시험할 때는 ?server=https://….workers.dev/ 로 바꿀 수 있음.

const qs = new URLSearchParams(location.search);
export const BASE = (qs.get("server") || new URL("../", location.href).href.replace(/\/lite\/?$/, "/")).replace(/\/?$/, "/");

/** 글자 받기 (실패하면 오류) */
export async function getText(path, query) {
  let url = path.startsWith("http") ? path : BASE + path;
  if (query) {
    const p = new URLSearchParams();
    for (const [k, v] of Object.entries(query)) if (v != null && v !== "") p.set(k, v);
    const s = p.toString();
    if (s) url += (url.includes("?") ? "&" : "?") + s;
  }
  const r = await fetch(url);
  if (!r.ok) throw new Error("HTTP " + r.status);
  return r.text();
}

export async function getJson(path, query) { return JSON.parse(await getText(path, query)); }

/** 같은 주소는 한 번만 받기 (자료 조각) */
const memo = new Map();
export function cached(path) {
  if (!memo.has(path)) memo.set(path, getJson(path).catch((e) => { memo.delete(path); throw e; }));
  return memo.get(path);
}

/** 공공데이터 XML(<item>…</item>) → 행 목록 [{소문자 키: 값}] */
export function xmlRows(text) {
  const doc = new DOMParser().parseFromString(text, "text/xml");
  const code = doc.querySelector("resultCode")?.textContent?.trim();
  const auth = doc.querySelector("returnAuthMsg")?.textContent?.trim();
  if (auth) throw new Error(auth);
  const items = [...doc.getElementsByTagName("item")];
  if (!items.length && code && code !== "00" && code !== "0") throw new Error("오류 " + code);
  return items.map((it) => {
    const o = {};
    for (const c of it.children) o[c.tagName.toLowerCase()] = c.textContent.trim();
    return o;
  });
}

/** JSON 에서 [key] 를 가진 객체들이 든 첫 배열 (응답 모양을 몰라도) */
export function findRows(root, key) {
  const walk = (n) => {
    if (Array.isArray(n)) {
      const objs = n.filter((x) => x && typeof x === "object" && !Array.isArray(x));
      if (objs.some((o) => key in o)) return objs;
      for (const x of n) { const r = walk(x); if (r) return r; }
      return null;
    }
    if (n && typeof n === "object") {
      if (key in n && !Object.values(n).some(Array.isArray)) return [n];
      for (const v of Object.values(n)) { const r = walk(v); if (r) return r; }
    }
    return null;
  };
  return walk(root) || [];
}

export const num = (v) => { const n = parseFloat(v); return Number.isFinite(n) ? n : null; };
export const int = (v) => { const n = num(v); return n == null ? null : Math.trunc(n); };
export const str = (v) => (v == null ? null : String(v).trim() || null);

// ── 이 기기에만 저장 (브라우저 localStorage, Compose 웹 버전과 같은 이름이라 그대로 이어짐) ──
export function storeGet(k) { try { return localStorage.getItem("tagohub." + k); } catch (e) { return null; } }
export function storeSet(k, v) { try { localStorage.setItem("tagohub." + k, v); } catch (e) { /* 개인 창 등 */ } }

// ── 시간 (한국 시각) ──
export function seoulNow() {
  const d = new Date(Date.now() + 9 * 3600 * 1000);
  return { y: d.getUTCFullYear(), m: d.getUTCMonth() + 1, d: d.getUTCDate(), h: d.getUTCHours(), mi: d.getUTCMinutes(), dow: d.getUTCDay() };
}
export const pad2 = (n) => String(n).padStart(2, "0");
export function ymd(t = seoulNow()) { return `${t.y}${pad2(t.m)}${pad2(t.d)}`; }
export function addDays(t, n) { const d = new Date(Date.UTC(t.y, t.m - 1, t.d + n)); return { y: d.getUTCFullYear(), m: d.getUTCMonth() + 1, d: d.getUTCDate(), h: 0, mi: 0, dow: d.getUTCDay() }; }
/** 요일 번호 (월 1 … 일 7) */
export function isoDay(t = seoulNow()) { return t.dow === 0 ? 7 : t.dow; }

/** 두 점 사이 거리(m), 짧은 거리용 근사 */
export function meters(la1, lo1, la2, lo2) {
  const dy = (la2 - la1) * 111000, dx = (lo2 - lo1) * 111000 * Math.cos(la1 * Math.PI / 180);
  return Math.sqrt(dx * dx + dy * dy);
}
export function distText(m) { return m >= 1000 ? (Math.round(m / 100) / 10 + "km").replace(".0km", "km") : Math.round(m / 10) * 10 + "m"; }

export const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

/** "10" < "100", "2" < "10" 처럼 숫자는 크기로 */
export function naturalCompare(a, b) {
  const na = parseInt(a, 10), nb = parseInt(b, 10);
  const da = /^\d/.test(a), db = /^\d/.test(b);
  if (da && db && na !== nb) return na - nb;
  if (da && !db) return -1;
  if (!da && db) return 1;
  return a < b ? -1 : a > b ? 1 : 0;
}

/** 버스 종류 → 노선 색 (앱 busRouteColor 와 같음) */
export function busColor(t) {
  if (!t) return "#607080";
  if (t.includes("간선")) return "#3D5BAB";
  if (t.includes("지선")) return "#3E9A2E";
  if (["광역", "급행", "직행", "좌석"].some((k) => t.includes(k))) return "#D7262E";
  if (t.includes("마을")) return "#7FA92A";
  if (t.includes("순환")) return "#C9940A";
  if (t === "일반") return "#14A0A8";
  if (t.includes("일반형시내")) return "#1E9E5A";
  if (t.includes("심야") || t.includes("올빼미")) return "#2B2B5E";
  if (t === "농어촌") return "#E5622C";
  if (["외곽", "농어촌", "시외"].some((k) => t.includes(k))) return "#8E6E3B";
  if (t.includes("공항")) return "#0090D2";
  return "#607080";
}

/** 지하철 노선 색 (이름으로) */
export function subwayColor(name) {
  const n = name || "";
  const m = /^(\d)호선/.exec(n.replace(/^(서울|수도권)\s*/, ""));
  const byNo = { 1: "#0052A4", 2: "#00A84D", 3: "#EF7C1C", 4: "#00A5DE", 5: "#996CAC", 6: "#CD7C2F", 7: "#747F00", 8: "#E6186C", 9: "#BDB092" };
  if (m && !/(부산|대구|광주|대전)/.test(n)) return byNo[m[1]];
  if (/인천\s?1/.test(n)) return "#7CA8D5";
  if (/인천\s?2/.test(n)) return "#ED8B00";
  if (n.includes("공항")) return "#0090D2";
  if (n.includes("GTX") || n.includes("광역급행")) return "#9A6292";
  if (n.includes("신분당")) return "#D4003B";
  if (n.includes("수인") || n.includes("분당")) return "#E0A800";
  if (n.includes("경의")) return "#5FB59A";
  if (n.includes("경춘")) return "#0C8E72";
  if (n.includes("우이")) return "#B7C452";
  if (n.includes("서해")) return "#8FC31F";
  if (n.includes("김포")) return "#A17800";
  if (n.includes("신림")) return "#6789CA";
  if (n.includes("에버라인") || n.includes("용인")) return "#56AD2D";
  if (n.includes("의정부")) return "#FDA600";
  return "#4B5563";
}
