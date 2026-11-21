// 서울 실시간 교통 (서울시 교통정보 TOPIS): 지도 위 도로 소통 색, 돌발(사고·공사), 공영자전거 대여소(빌릴 수 있는 대수, 서울 밖도: 대전 타슈·세종 어울링·창원 누비자 등),
// 더 확대하면 주차장(전국주차장정보표준데이터, parking.js), 화장실·전동휠체어 충전기·졸음쉼터·교통약자 이동지원센터·환승센터(amenity.js).
// 도로 링크 모양은 seoul/links.json (pipeline/build_seoul_links.py), 실시간 값은 서버 /w/topis/… (src/seoultraffic.js)
import { getJson, esc, onReturn } from "./core.js";
import { placeFix } from "./edits.js";
import { MapView } from "./map.js";
import { decodePolyline } from "./busdata.js";
import { offsetRight } from "./sideline.js";
import { BIKE_ZOOM, bikeDots } from "./bikes.js";
import { PARKING_ZOOM, parkingDots, parkingIn, parkingLines } from "./parking.js";
import { PLACE_KINDS, placeDots, placesIn } from "./amenity.js";

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

/**
 * 한 요청에 묻는 링크 수. Workers 는 한 요청에서 바깥 연결을 동시에 6개만 열어(나머지는 줄 서서 기다림)
 * 30개를 한 요청에 물으면 서울 API 를 5번 차례로 기다렸음 → 6개씩 나눠 여러 요청을 한꺼번에 보내면 한 차례로 끝남
 */
const GROUP = 6;
/** 한꺼번에 보내는 요청 수 (1920px 화면 줌 14: 링크 1436개 = 묶음 240개 남짓) */
const GROUPS_MAX = 300;
/**
 * 그리는 소통 링크 수 (화면 가운데에서 가까운 것부터). 예전 180: 넓은 화면·줌 14~15 에서 가장자리 도로가 칠해지지 않았음
 * (2026-11-21 "확대나 축소했을 때 교통정보가 다 안 떠"). 1920px 화면 줌 14 에 1436개, 줌 15 에 458개. 흰 테두리 없이 한 겹으로
 */
const LINKS_MAX = 1600;
/** 이 시간 안에 다 오지 않으면 받은 것까지 먼저 칠함 (ms) */
const PAINT_WAIT_MS = 6000;
let linksP = null;
/**
 * 서울 도로 링크 [{id, road, from, to, dist, div, pts, b, g}] (처음 한 번 받음).
 * g 는 묶음 번호: 약 1km 칸마다 링크를 6개씩 묶어, 늘 같은 묶음으로 물어 서버 저장을 여러 사람이 같이 씀
 */
function seoulLinks() {
  if (!linksP) {
    linksP = fetch("seoul/links.json").then((r) => (r.ok ? r.json() : { links: {} })).then((d) => {
      const ls = Object.entries(d.links || {}).map(([id, a]) => {
        const pts = decodePolyline(String(a[5] || ""), 5);
        const b = { s: 90, n: -90, w: 180, e: -180 };
        for (const [la, lo] of pts) { b.s = Math.min(b.s, la); b.n = Math.max(b.n, la); b.w = Math.min(b.w, lo); b.e = Math.max(b.e, lo); }
        return { id, road: a[0], from: a[1], to: a[2], dist: a[3], div: a[4], pts, b, g: -1 };
      }).filter((l) => l.pts.length >= 2);
      const cells = new Map();
      for (const l of ls) {
        const c = Math.floor((l.b.s + l.b.n) / 2 / 0.01) + "," + Math.floor((l.b.w + l.b.e) / 2 / 0.0125);
        if (!cells.has(c)) cells.set(c, []);
        cells.get(c).push(l);
      }
      const groups = [];
      for (const c of [...cells.keys()].sort()) {
        const members = cells.get(c).sort((x, y) => (x.id < y.id ? -1 : 1));
        for (let i = 0; i < members.length; i += GROUP) {
          const part = members.slice(i, i + GROUP);
          for (const l of part) l.g = groups.length;
          groups.push(part.map((l) => l.id));
        }
      }
      return { ls, groups };
    }).catch(() => { linksP = null; return { ls: [], groups: [] }; });
  }
  return linksP;
}

/**
 * 서울 실시간 교통 층. 지도에 켜기 버튼을 달고, 켜져 있으면 확대한 화면 안 링크의 속도를 받아 색을 칠함.
 * [onChange] 는 다시 그려야 할 때 부름 (선·점은 부르는 쪽이 lines()·dots() 로 받아 그림)
 */
export function trafficLayer(onChange) {
  let on = false;
  try { on = localStorage.getItem(SAVE) === "1"; } catch (e) { /* 못 읽음 */ }
  let alive = true, visible = [], acc = [], accAt = 0, bikes = [], bikeAt = 0, parks = [], parkSeq = 0;
  const places = {}, placeSeq = {};
  const speeds = new Map(); // 링크 id → [km/h, 초]
  const groupAt = new Map(); // 묶음 번호 → 받은 때 (받는 중이면 -1)
  const btn = document.createElement("button");
  btn.className = "locbtn trafficbtn" + (on ? " on" : "");
  btn.title = "실시간 교통 (서울) · 공영자전거 · 주차장 · 편의시설";
  btn.setAttribute("aria-label", "실시간 교통 (서울) · 공영자전거 · 주차장 · 편의시설");
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
    const now = Date.now();
    // 공영자전거(서울 따릉이 + 대전·세종·창원 등): 서울 밖도 있음. 서버가 1분 저장하므로 1분마다
    if (on && MapView.zoom() >= BIKE_ZOOM && now - bikeAt > 60000) {
      bikeAt = now;
      getJson("w/topis/bike").then((v) => { bikes = Array.isArray(v) ? v : []; if (alive) onChange(); }).catch(() => { bikeAt = 0; });
    }
    // 주차장: 고정 자료라 칸마다 한 번만 받음. 지도를 옮기는 사이 먼저 물은 것이 늦게 오면 버림 (마지막에 물은 것만)
    if (on && b && MapView.zoom() >= PARKING_ZOOM) {
      const seq = ++parkSeq;
      parkingIn(b).then((v) => { if (seq !== parkSeq) return; parks = v; if (alive) onChange(); }).catch(() => {});
    }
    // 편의시설: 주차장과 같은 방식, 종류마다 보이기 시작하는 확대가 다름
    for (const [kind, k] of Object.entries(PLACE_KINDS)) {
      if (!on || !b || MapView.zoom() < k.zoom) continue;
      const seq = placeSeq[kind] = (placeSeq[kind] || 0) + 1;
      placesIn(kind, b).then((v) => { if (seq !== placeSeq[kind]) return; places[kind] = v; if (alive) onChange(); }).catch(() => {});
    }
    if (!on || !inSeoul(b)) { visible = []; onChange(); return; }
    if (now - accAt > 120000) {
      accAt = now;
      getJson("w/topis/acc").then((v) => { acc = Array.isArray(v) ? v : []; if (alive) onChange(); }).catch(() => { accAt = 0; });
    }
    if (MapView.zoom() < TRAFFIC_ZOOM) { visible = []; onChange(); return; }
    const { ls, groups } = await seoulLinks();
    if (!alive) return;
    const cla = (b.s + b.n) / 2, clo = (b.w + b.e) / 2;
    visible = ls.filter((l) => l.b.n >= b.s && l.b.s <= b.n && l.b.e >= b.w && l.b.w <= b.e)
      .map((l) => [l, Math.hypot((l.b.s + l.b.n) / 2 - cla, ((l.b.w + l.b.e) / 2 - clo) * 0.8)])
      .sort((x, y) => x[1] - y[1]).slice(0, LINKS_MAX).map((x) => x[0]);
    // 4분 넘은 묶음만 한꺼번에 물음 (서버는 묶음마다 5분 저장). 화면 가운데에 가까운 묶음부터
    const need = [];
    for (const l of visible) {
      const at = groupAt.get(l.g);
      if (!need.includes(l.g) && at !== -1 && (at == null || now - at > 240000)) need.push(l.g);
    }
    const ask = need.slice(0, GROUPS_MAX);
    for (const g of ask) groupAt.set(g, -1);
    // 받는 대로 칠하지 않고 다 받은 뒤 한 번에 칠함 (조각조각 칠해지지 않게). 늦는 요청이 있으면 PAINT_WAIT_MS 뒤 받은 것까지 먼저
    let failed = false;
    const jobs = ask.map((g) => getJson("w/topis/traffic", { links: groups[g].join(",") }).then((v) => {
      for (const id of groups[g]) speeds.delete(id);
      for (const [id, x] of Object.entries(v || {})) speeds.set(id, [x[0], x[1]]);
      groupAt.set(g, Date.now());
    }).catch(() => { groupAt.delete(g); failed = true; }));
    if (jobs.length) {
      const early = setTimeout(() => { if (alive) onChange(); }, PAINT_WAIT_MS);
      Promise.all(jobs).then(() => {
        clearTimeout(early);
        if (!alive) return;
        onChange();
        // 못 받았거나 남은 묶음은 몇 초 뒤 다시 (세 번까지)
        if ((failed || need.length > ask.length) && retries < 3) { retries++; setTimeout(refresh, 3000); } else retries = 0;
      });
    }
    onChange();
  }
  let retries = 0;
  const timer = setInterval(refresh, 120000);
  const offIdle = MapView.onIdle(refresh);
  // 다른 앱에 갔다 돌아오면 2분을 기다리지 않고 오래된 묶음만 바로 새로 (그사이 못 받은 묶음도)
  const offReturn = onReturn(() => { retries = 0; refresh(); });
  refresh();

  return {
    get on() { return on; },
    /** 그릴 소통 선 [{pts, color, width}] (한 방향 링크라 진행 방향 오른쪽으로 비켜 상·하행이 나란히) */
    lines() {
      if (!on || !visible.length) return [];
      // 비키는 거리는 0.5m 단위로: 지도를 옮기기만 해도 1px 당 m 가 아주 조금씩 바뀌어 선 꼭짓점이 달라지면
      // 지도가 같은 선을 매번 지우고 새로 그림 (map.js 는 꼭짓점이 같은 선을 그대로 둠)
      const side = Math.max(0.5, Math.round((MapView.metersPerPx() || 1) * 3 * 2) / 2);
      const out = [];
      for (const l of visible) {
        const s = speeds.get(l.id), color = s ? speedColor(l.div, s[0]) : null;
        if (!color) continue;
        if (l.offAt !== side) { l.off = offsetRight(l.pts, side); l.offAt = side; }   // 같은 거리면 지난번에 비킨 선 그대로
        out.push({ pts: l.off, color, width: 4, casing: false });
      }
      return out;
    },
    /** 돌발 점(서울일 때), 확대했으면 공영자전거 대여소·주차장·편의시설 점(어디서나) — 켜져 있을 때 */
    dots() {
      const b = MapView.bounds();
      if (!on || !b) return [];
      const out = inSeoul(b) ? acc.map((a) => ({ id: "acc:" + a.id, lat: a.lat, lng: a.lng, color: RED, ring: "#fff", r: 7 })) : [];
      for (const [kind, k] of Object.entries(PLACE_KINDS)) if (MapView.zoom() >= k.zoom) out.push(...placeDots(places[kind] || [], b));
      if (MapView.zoom() >= PARKING_ZOOM) out.push(...parkingDots(parks, b));
      if (MapView.zoom() >= BIKE_ZOOM) out.push(...bikeDots(bikes, b));
      // 관리자가 지도 고치기로 옮긴 점은 고친 자리에 (fixpoint.js)
      return out.map((d) => { const f = placeFix(d.id); return f ? { ...d, lat: f[0], lng: f[1] } : d; });
    },
    /** 점을 눌렀을 때 카드 내용 (모르는 점이면 null) */
    card(id, fill) {
      if (id.startsWith("acc:")) {
        const a = acc.find((x) => "acc:" + x.id === id);
        if (!a) return null;
        const kind = [a.type, a.sub].filter(Boolean).join(" · ");
        const when = [a.from && a.from + "부터", a.to && a.to + "까지"].filter(Boolean).join(" ");
        return `<div><b>${esc(kind || "돌발")}</b><small>${esc(a.info.replace(/\n+/g, " "))}</small>${when ? `<small>${esc(when)}</small>` : ""}</div>`;
      }
      if (id.startsWith("park:")) {
        const x = parks.find((v) => "park:" + v.id === id);
        if (!x) return null;
        return `<div><b>${esc(x.name)}</b>${parkingLines(x).map((l) => `<small>${esc(l)}</small>`).join("")}</div>`;
      }
      if (id.startsWith("pl:")) {
        const x = (places[id.split(":")[1]] || []).find((v) => "pl:" + v.id === id);
        if (!x) return null;
        return `<div><b>${esc(x.name)}</b>${x.lines.map((l) => `<small>${esc(l)}</small>`).join("")}</div>`;
      }
      if (id.startsWith("bike:")) {
        const x = bikes.find((v) => "bike:" + v.id === id);
        if (!x) return null;
        const now = x.bikes == null ? "실시간 대수 없음" : `대여 가능 ${x.bikes}대`;
        return `<div><b>${esc(x.name)}</b><small>${now}${x.racks > 0 ? " · 거치대 " + x.racks : ""}</small>${x.info ? `<small>${esc(x.info)}</small>` : ""}</div>`;
      }
      return null;
    },
    destroy() { alive = false; clearInterval(timer); offIdle(); offReturn(); btn.remove(); },
  };
}
