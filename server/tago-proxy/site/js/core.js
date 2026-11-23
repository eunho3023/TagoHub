// 공통: 서버 묻기, 응답 해석(XML·JSON), 이 기기 저장, 시간·거리·글자 도우미.
// 서버 주소는 이 페이지를 내준 서버. 시험할 때는 ?server=https://….workers.dev/ 로 바꿀 수 있음.

const qs = new URLSearchParams(location.search);
// 타볼까(tabolkka.pages.dev)는 화면·자료·서버가 한 주소. 다른 서버에 묻게 하려면 config.js 의 TAGOHUB_SERVER 나 ?server=
export const BASE = (qs.get("server") || (typeof window !== "undefined" && window.TAGOHUB_SERVER) || new URL("../", location.href).href.replace(/\/lite\/?$/, "/")).replace(/\/?$/, "/");

/** 글자 받기 (실패하면 오류) */
export async function getText(path, query) {
  let url = path.startsWith("http") ? path : BASE + path;
  if (query) {
    const p = new URLSearchParams();
    for (const [k, v] of Object.entries(query)) if (v != null && v !== "") p.set(k, v);
    const s = p.toString();
    if (s) url += (url.includes("?") ? "&" : "?") + s;
  }
  let r;
  for (let i = 0; ; i++) {
    try { r = await fetch(url); break; }
    catch (e) {
      // 연결 자체가 실패(폰에서 웹으로 돌아온 직후처럼 통신이 막 깨어나는 중)면 조금 뒤 다시 (1초, 2.5초).
      // 꺼져 있거나 가려져 있으면 켜지거나 보일 때까지(5초까지) 기다렸다가. 서버가 준 오류(HTTP 4xx·5xx)는 그대로
      if (i >= NET_RETRY_MS.length) throw e;
      await whenUsable(5000);
      await new Promise((res) => setTimeout(res, NET_RETRY_MS[i]));
    }
  }
  const text = await r.text();
  // 공공데이터포털 하루 한도 초과: 오류 문구를 "잠시 뒤 다시"가 아니라 "밤 12시 뒤"로 (tryLater)
  if (/LIMITED_NUMBER_OF_SERVICE/.test(text.slice(0, 4000))) quotaAt = Date.now();
  if (!r.ok) throw new Error("HTTP " + r.status);
  return text;
}
const NET_RETRY_MS = [1000, 2500];
let quotaAt = 0;

/**
 * 받지 못했을 때 덧붙일 말. 방금(1분 안) 하루 요청 한도 초과 응답을 받았으면 다시 눌러도 안 되니 그렇게 알림
 * (예전엔 늘 "잠시 뒤 다시 시도해 주세요"라 계속 눌러 보게 됐음, 2026-11-22 지하철 시간표·경기 노선)
 */
export function tryLater() {
  return Date.now() - quotaAt < 60000 ? "오늘 이용이 많아 자료 한도가 다 찼어요. 밤 12시가 지나면 다시 볼 수 있어요." : "잠시 뒤 다시 시도해 주세요.";
}

/** 인터넷이 켜져 있고 화면이 보일 때까지 (최대 ms) */
function whenUsable(ms) {
  const usable = () => navigator.onLine !== false && !document.hidden;
  if (usable()) return Promise.resolve();
  return new Promise((res) => {
    const done = () => { clearTimeout(t); window.removeEventListener("online", check); document.removeEventListener("visibilitychange", check); res(); };
    const check = () => { if (usable()) done(); };
    const t = setTimeout(done, ms);
    window.addEventListener("online", check);
    document.addEventListener("visibilitychange", check);
  });
}

/**
 * 다른 앱에서 돌아오거나(보이게 됨) 인터넷이 다시 켜지면 cb. 한 번 받는 화면이 "받지 못했어요" 상태면 다시 받으려고
 * (폰에서 받는 도중 다른 앱에 가면 그 요청이 실패하고, 돌아와도 실패 문구가 그대로 남았음). 돌려주는 함수로 해제
 */
export function onReturn(cb) {
  const h = () => { if (!document.hidden) cb(); };
  document.addEventListener("visibilitychange", h);
  window.addEventListener("pageshow", h);
  window.addEventListener("online", h);
  return () => {
    document.removeEventListener("visibilitychange", h);
    window.removeEventListener("pageshow", h);
    window.removeEventListener("online", h);
  };
}

/**
 * 주기적으로 새로 받는 화면(도착·버스 위치·지하철 도착 등)용.
 * 폰에서 다른 앱에 갔다 돌아오면 통신이 깨어나기 전에 물어 실패하고, 다음 주기까지 "받지 못했어요"가 남곤 했음.
 * - 보이게 되거나 인터넷이 다시 켜지면 바로 run (마지막 성공 뒤 minGapMs 넘었을 때)
 * - 가려져 있는 동안은 주기 실행을 건너뜀
 * - run 이 false 를 주거나 오류면 실패: 2·4·6초 뒤 다시 (세 번). run({ lastTry }) 의 lastTry 가 true 일 때만 화면에 "받지 못했어요"를,
 *   그 전에는 받아 둔 것을 그대로 두거나 "다시 받는 중"을 보여 주면 됨
 * 돌려주는 { now, stop }: now() 는 바로 다시 (새로고침 단추), stop() 은 멈춤.
 * now() 를 눌렀을 때 받는 중인 것이 있으면, 그것이 끝나는 대로 한 번 더 받음 (예전엔 받는 중이면 아무것도 안 해서,
 * 공공데이터가 15초 넘게 늦을 때 새로고침이 안 먹히는 것처럼 보였음)
 */
export function livePoll(run, everyMs, { minGapMs = 5000, retries = 3, stepMs = 2000 } = {}) {
  let okAt = 0, fails = 0, retryT = null, stopped = false, busy = null, again = false;
  const go = () => {
    if (stopped) return Promise.resolve();
    if (busy) return busy;
    clearTimeout(retryT);
    busy = (async () => {
      let ok = false;
      try { ok = (await run({ lastTry: fails >= retries })) !== false; } catch (e) { ok = false; }
      busy = null;
      if (stopped) return;
      if (again) { again = false; fails = 0; go(); return; }
      if (ok) { okAt = Date.now(); fails = 0; }
      else if (fails < retries) { fails++; retryT = setTimeout(go, stepMs * fails); }
    })();
    return busy;
  };
  const timer = setInterval(() => { if (!document.hidden) go(); }, everyMs);
  const off = onReturn(() => { if (Date.now() - okAt > minGapMs) { fails = 0; go(); } });
  go();
  return {
    // 받는 중이면 끝난 뒤 한 번 더 받고, 그 두 번째가 끝날 때 끝나는 약속을 돌려줌
    now: () => { fails = 0; if (busy) { again = true; return busy.then(() => busy); } return go(); },
    stop: () => {
      stopped = true; clearInterval(timer); clearTimeout(retryT); off();
    },
  };
}

export async function getJson(path, query) { return JSON.parse(await getText(path, query)); }

// ── 이 브라우저에 저장 (Cache Storage): 다음에 열 때 서버를 기다리지 않고 바로 보여 줌 ──
// 자료 조각(data/…)은 서버 자료가 새로 만들어지면(manifest.json 의 built) 통째로 지우고 다시 받음.
// v2: 배포 뒤 한 시간 동안 받은 예전 자료(고치기 전 지하철역 좌표)를 저장한 브라우저가 있어 한 번 모두 지움
const STORE = "tagohub-store-v1", DATA_STORE = "tagohub-data-v2", BUILT_KEY = "tagohub.dataBuilt";
const storeUrl = (key) => location.origin + "/__store/" + encodeURIComponent(key);
/** 저장해 둔 값 (없거나 못 읽으면 undefined) */
export async function cacheGet(key, name = STORE) {
  try {
    if (!self.caches) return undefined;
    const r = await (await caches.open(name)).match(storeUrl(key));
    return r ? await r.json() : undefined;
  } catch (e) { return undefined; }
}
/** 값 저장 (못 하면 그냥 넘어감) */
export async function cachePut(key, value, name = STORE) {
  try {
    if (!self.caches) return;
    await (await caches.open(name)).put(storeUrl(key), new Response(JSON.stringify(value), { headers: { "Content-Type": "application/json" } }));
  } catch (e) { /* 저장 공간 없음 등 */ }
}
// 페이지를 열 때 한 번: 서버 자료가 바뀌었으면 저장해 둔 자료 조각을 지움 (뒤에서, 화면을 막지 않음)
// 서버 자료 판 번호(built): 자료 파일을 받을 때 주소에 붙임. 붙이지 않으면 브라우저·서버 앞단이 한 시간 보관한
// 예전 파일(역 좌표를 고치기 전)을 받아 이 브라우저에 다음 판까지 저장해 버렸음
let dataBuilt = "";
const dataChecked = (async () => {
  try { if (self.caches) caches.delete("tagohub-data-v1"); } catch (e) { /* 못 지움 */ }
  try {
    const m = await getJson("data/manifest.json", { t: Math.floor(Date.now() / 600000) });
    const built = String(m.built ?? m.dbVersion ?? "");
    if (!built) return;
    dataBuilt = built;
    let old = null;
    try { old = localStorage.getItem(BUILT_KEY); } catch (e) { /* 못 읽음 */ }
    if (old !== built) {
      if (self.caches) await caches.delete(DATA_STORE);
      memo.clear();
      try { localStorage.setItem(BUILT_KEY, built); } catch (e) { /* 못 씀 */ }
    }
  } catch (e) { /* 서버에 못 닿음: 가진 것 그대로 */ }
})();

/** 같은 주소는 한 번만 받기 (자료 조각). data/… 는 이 브라우저에 저장해 두고 다음부터 바로 */
const memo = new Map();
export function cached(path) {
  if (!memo.has(path)) {
    const persist = path.startsWith("data/") && path !== "data/manifest.json";
    const load = async () => {
      if (persist) {
        const have = await cacheGet(path, DATA_STORE);
        if (have !== undefined) return have;
      }
      if (persist) await dataChecked;
      const v = await getJson(path, persist && dataBuilt ? { b: dataBuilt } : undefined);
      if (persist) cachePut(path, v, DATA_STORE);
      return v;
    };
    memo.set(path, load().catch((e) => { memo.delete(path); throw e; }));
  }
  return memo.get(path);
}

/**
 * 자료 파일 글자(data/files/… 지하철역·노선 모양·시간표 같은 자주 안 바뀌는 것). 이 브라우저에 저장해 두고 다음부터 바로
 * (서버 자료가 새로 만들어지면 위 dataChecked 가 통째로 지움)
 */
export function cachedText(path) {
  const k = "text:" + path;
  if (!memo.has(k)) {
    const load = async () => {
      const have = await cacheGet(path, DATA_STORE);
      if (typeof have === "string") return have;
      await dataChecked;
      const t = await getText(path, dataBuilt ? { b: dataBuilt } : undefined);
      cachePut(path, t, DATA_STORE);
      return t;
    };
    memo.set(k, load().catch((e) => { memo.delete(k); throw e; }));
  }
  return memo.get(k);
}

/** 공공데이터 XML(<item>…</item>) → 행 목록 [{소문자 키: 값}] */
export function xmlRows(text) {
  // 공공데이터포털이 바쁠 때는 XML 을 요청해도 JSON 오류({"response":{"header":{"resultCode":99,…}}})가 옴.
  // 예전엔 이것을 "버스 없음"(빈 목록)으로 읽어 TAGO 가 멈춘 동안 "도착 예정 버스가 없어요"로 보였음
  const t = String(text ?? "").trimStart();
  if (t.startsWith("{")) {
    let h = null;
    try { h = JSON.parse(t)?.response?.header; } catch (e) { /* 아래에서 오류 */ }
    const c = h ? String(h.resultCode ?? "") : "";
    if (!h || (c !== "00" && c !== "0")) throw new Error("오류 " + (c || "응답") + (h?.resultMsg ? " " + h.resultMsg : ""));
    return [];
  }
  const doc = new DOMParser().parseFromString(text, "text/xml");
  if (doc.getElementsByTagName("parsererror").length) throw new Error("응답을 읽지 못했어요");
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
