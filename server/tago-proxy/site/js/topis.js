// 서울 실시간 교통 (서울시 교통정보 TOPIS): 지도 위 도로 소통 색, 돌발(사고·공사), 교통량 지점.
// 도로 링크 모양은 seoul/links.json (pipeline/build_seoul_links.py), 실시간 값은 서버 /w/topis/… (src/seoultraffic.js)
import { getJson, esc } from "./core.js";
import { MapView } from "./map.js";
import { decodePolyline } from "./busdata.js";
import { offsetRight } from "./bus.js";

const SEOUL = { s: 37.41, n: 37.72, w: 126.76, e: 127.19 };
const SAVE = "tagohub.traffic";
const GREEN = "#16a34a", AMBER = "#f59e0b", RED = "#dc2626";
/** 링크 수가 많아 확대했을 때만 소통 색을 그림 */
export const TRAFFIC_ZOOM = 14;

/** 속도 → 색 (TOPIS 기준: 도시고속도로 70·30, 일반도로 25·15 km/h) */
export function speedColor(div, kmh) {
  if (kmh == null) return null;
  return div === "02" ? (kmh >= 70 ? GREEN : kmh >= 30 ? AMBER : RED) : (kmh >= 25 ? GREEN : kmh >= 15 ? AMBER : RED);
}

let linksP = null;
/** 서울 도로 링크 [{id, road, from, to, dist, div, pts, b}] (처음 한 번 받음) */
function seoulLinks() {
  if (!linksP) {
    linksP = fetch("seoul/links.json").then((r) => (r.ok ? r.json() : { links: {} })).then((d) => Object.entries(d.links || {}).map(([id, a]) => {
      const pts = decodePolyline(String(a[5] || ""), 5);
      const b = { s: 90, n: -90, w: 180, e: -180 };
      for (const [la, lo] of pts) { b.s = Math.min(b.s, la); b.n = Math.max(b.n, la); b.w = Math.min(b.w, lo); b.e = Math.max(b.e, lo); }
      return { id, road: a[0], from: a[1], to: a[2], dist: a[3], div: a[4], pts, b };
    }).filter((l) => l.pts.length >= 2)).catch(() => { linksP = null; return []; });
  }
  return linksP;
}

/** 한국 시각 날짜(YYYYMMDD)·시(HH), h 시간 전 */
function kstHour(h = 0) {
  const d = new Date(Date.now() + 9 * 3600 * 1000 - h * 3600 * 1000);
  return [d.toISOString().slice(0, 10).replace(/-/g, ""), String(d.getUTCHours()).padStart(2, "0")];
}

/**
 * 서울 실시간 교통 층. 지도에 켜기 버튼을 달고, 켜져 있으면 확대한 화면 안 링크의 속도를 받아 색을 칠함.
 * [onChange] 는 다시 그려야 할 때 부름 (선·점은 부르는 쪽이 lines()·dots() 로 받아 그림)
 */
export function trafficLayer(onChange) {
  let on = false;
  try { on = localStorage.getItem(SAVE) === "1"; } catch (e) { /* 못 읽음 */ }
  let alive = true, visible = [], acc = [], spots = [], accAt = 0, spotsAt = 0;
  const speeds = new Map(); // 링크 id → [km/h, 초, 받은 때]
  const btn = document.createElement("button");
  btn.className = "locbtn trafficbtn" + (on ? " on" : "");
  btn.title = "실시간 교통 (서울)";
  btn.setAttribute("aria-label", "실시간 교통 (서울)");
  btn.innerHTML = '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="7" y="2" width="10" height="20" rx="3"/><circle cx="12" cy="7" r="1.6" fill="currentColor"/><circle cx="12" cy="12" r="1.6" fill="currentColor"/><circle cx="12" cy="17" r="1.6" fill="currentColor"/></svg>';
  for (const ev of ["pointerdown", "mousedown", "touchstart", "dblclick"]) btn.addEventListener(ev, (e) => e.stopPropagation());
  btn.addEventListener("click", (e) => {
    e.stopPropagation();
    on = !on;
    btn.classList.toggle("on", on);
    try { localStorage.setItem(SAVE, on ? "1" : "0"); } catch (err) { /* 못 씀 */ }
    refresh();
  });
  MapView.ready.then(() => { if (alive) MapView.el.appendChild(btn); });

  const inSeoul = (b) => b && b.n > SEOUL.s && b.s < SEOUL.n && b.e > SEOUL.w && b.w < SEOUL.e;
  async function refresh() {
    if (!alive) return;
    const b = MapView.bounds();
    if (!on || !inSeoul(b)) { visible = []; onChange(); return; }
    const now = Date.now();
    if (now - accAt > 120000) {
      accAt = now;
      getJson("w/topis/acc").then((v) => { acc = Array.isArray(v) ? v : []; if (alive) onChange(); }).catch(() => { accAt = 0; });
    }
    if (!spotsAt) {
      spotsAt = now;
      getJson("w/topis/spots").then((v) => { spots = Array.isArray(v) ? v : []; if (alive) onChange(); }).catch(() => { spotsAt = 0; });
    }
    if (MapView.zoom() < TRAFFIC_ZOOM) { visible = []; onChange(); return; }
    const ls = await seoulLinks();
    if (!alive) return;
    const cla = (b.s + b.n) / 2, clo = (b.w + b.e) / 2;
    visible = ls.filter((l) => l.b.n >= b.s && l.b.s <= b.n && l.b.e >= b.w && l.b.w <= b.e)
      .map((l) => [l, Math.hypot((l.b.s + l.b.n) / 2 - cla, ((l.b.w + l.b.e) / 2 - clo) * 0.8)])
      .sort((x, y) => x[1] - y[1]).slice(0, 180).map((x) => x[0]);
    // 4분 넘은 것만 다시 물음 (서버는 5분 저장, 한 번에 60개)
    const need = visible.filter((l) => { const s = speeds.get(l.id); return !s || now - s[2] > 240000; }).map((l) => l.id);
    const jobs = [];
    for (let i = 0; i < need.length; i += 60) {
      jobs.push(getJson("w/topis/traffic", { links: need.slice(i, i + 60).join(",") }).then((v) => {
        for (const [id, x] of Object.entries(v || {})) speeds.set(id, [x[0], x[1], Date.now()]);
        if (alive) onChange();
        return Object.keys(v || {}).length;
      }).catch(() => 0));
    }
    // 서버는 한 번에 새로 묻는 링크 수가 정해져 있어 빠진 것이 있으면 몇 초 뒤 이어서 (새로 받은 것이 있을 때만, 세 번까지)
    if (jobs.length) Promise.all(jobs).then((got) => {
      const missing = need.some((id) => !speeds.has(id));
      if (alive && missing && got.some((n) => n > 0) && retries < 3) { retries++; setTimeout(refresh, 3000); } else retries = 0;
    });
    onChange();
  }
  let retries = 0;
  const timer = setInterval(refresh, 120000);
  const offIdle = MapView.onIdle(refresh);
  refresh();

  return {
    get on() { return on; },
    /** 그릴 소통 선 [{pts, color, width}] (한 방향 링크라 진행 방향 오른쪽으로 비켜 상·하행이 나란히) */
    lines() {
      if (!on || !visible.length) return [];
      const side = (MapView.metersPerPx() || 1) * 3;
      const out = [];
      for (const l of visible) {
        const s = speeds.get(l.id), color = s ? speedColor(l.div, s[0]) : null;
        if (color) out.push({ pts: offsetRight(l.pts, side), color, width: 4 });
      }
      return out;
    },
    /** 돌발·교통량 지점 점 (켜져 있고 서울일 때) */
    dots() {
      if (!on || !inSeoul(MapView.bounds())) return [];
      const z = MapView.zoom();
      const out = acc.map((a) => ({ id: "acc:" + a.id, lat: a.lat, lng: a.lng, color: RED, ring: "#fff", r: 7 }));
      if (z >= 13) for (const s of spots) out.push({ id: "spot:" + s.no, lat: s.lat, lng: s.lng, color: "#6b7280", ring: "#fff", r: 5 });
      return out;
    },
    /** 점을 눌렀을 때 카드 내용 (모르는 점이면 null). 교통량은 받는 대로 [fill] 로 채움 */
    card(id, fill) {
      if (id.startsWith("acc:")) {
        const a = acc.find((x) => "acc:" + x.id === id);
        if (!a) return null;
        const kind = [a.type, a.sub].filter(Boolean).join(" · ");
        const when = [a.from && a.from + "부터", a.to && a.to + "까지"].filter(Boolean).join(" ");
        return `<div><b>${esc(kind || "돌발")}</b><small>${esc(a.info.replace(/\n+/g, " "))}</small>${when ? `<small>${esc(when)}</small>` : ""}</div>`;
      }
      if (id.startsWith("spot:")) {
        const s = spots.find((x) => "spot:" + x.no === id);
        if (!s) return null;
        // 지금 시간대는 아직 덜 모였을 수 있어 지난 시간대도 같이
        const hours = [kstHour(1), kstHour(0)];
        Promise.all(hours.map(([ymd, hh]) => getJson("w/topis/vol", { spot: s.no, ymd, hh }).catch(() => null))).then((vs) => {
          const txt = vs.map((v, i) => (v ? `${+hours[i][1]}시 들어옴 ${v.in.toLocaleString()}대 · 나감 ${v.out.toLocaleString()}대` : null)).filter(Boolean).join(" / ");
          fill(`<div><b>${esc(s.name)} 교통량</b><small>${esc(txt || "교통량 정보를 받지 못했어요")}</small></div>`);
        });
        return `<div><b>${esc(s.name)} 교통량</b><small>받는 중...</small></div>`;
      }
      return null;
    },
    destroy() { alive = false; clearInterval(timer); offIdle(); btn.remove(); },
  };
}
