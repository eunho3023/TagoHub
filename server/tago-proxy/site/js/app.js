// 틀: 주소(#…)로 화면 바꾸기(브라우저 뒤로 가기도 됨), 아래 탭, 넓은 화면은 왼쪽 목록 + 오른쪽 지도.
import { MapView } from "./map.js";
import { go, isWide, wideQ } from "./nav.js";
import { resetActs, icon, act, esc } from "./ui.js";
import { applyTheme } from "./store.js";
import { stopsIn, cityName } from "./busdata.js";
import * as home from "./home.js";
import * as bus from "./bus.js";
import * as more from "./more.js";
import { Alight } from "./alight.js";
import * as subway from "./subway.js";
import * as air from "./air.js";
import "./modes.js";

const TABS = [
  { id: "", label: "홈", icon: "home" },
  { id: "transit", label: "대중교통", icon: "bus" },
  { id: "map", label: "지도", icon: "map" },
  { id: "navi", label: "길찾기", icon: "route" },
  { id: "settings", label: "설정", icon: "gear" },
];

/** 화면 이름 → 그리는 함수 (root, params, ctx) → 정리 함수 */
const SCREENS = {
  "": home.render,
  transit: (root, p, ctx) => more.transit(root, p, ctx),
  map: bus.mapTab,
  navi: (root, p, ctx) => more.navi(root, p, ctx),
  settings: more.settings,
  notices: more.notices,
  stop: bus.stopScreen,
  route: bus.routeScreen,
  subway: subway.stationScreen,
  subwaymap: subway.subwayMap,
  airport: air.airportScreen,
  report: more.reportScreen,
};
/** 다른 모듈이 화면을 더함 (지하철·기차 등) */
export function addScreen(name, fn) { SCREENS[name] = fn; }

const app = document.getElementById("app");
const panel = document.getElementById("panel");
const side = document.getElementById("side");
const nav = document.getElementById("nav");

/** 지금 주소: #/stop/ID?lat=… → {name, id, params} */
function parse() {
  const h = location.hash.replace(/^#\/?/, "");
  const [path, q] = h.split("?");
  const [name, ...rest] = path.split("/");
  return { name: name || "", id: rest.map(decodeURIComponent).join("/") || null, params: Object.fromEntries(new URLSearchParams(q || "")) };
}


let lastTab = "";
let cleanup = null;
let mapOwned = false;
let sideCleanup = null;

function drawNav() {
  nav.innerHTML = TABS.map((t) => `<button class="nav-b ${t.id === lastTab ? "on" : ""}" ${act(() => { location.hash = "#/" + t.id; })}>
    <span class="pill">${icon(t.icon, 22)}</span><span>${t.label}</span></button>`).join("");
}

function show() {
  if (cleanup) { try { cleanup(); } catch (e) { console.error(e); } cleanup = null; }
  resetActs();
  Alight.redraw();
  const { name, id, params } = parse();
  if (TABS.some((t) => t.id === name)) lastTab = name;
  drawNav();
  mapOwned = false;
  MapView.clear();
  panel.innerHTML = "";
  panel.scrollTop = 0;
  const ctx = {
    /** 이 화면이 지도를 씀: 넓으면 오른쪽 칸, 좁으면 [slot] 안 */
    useMap(slot) { mapOwned = true; stopSide(); if (isWide()) MapView.mount(side); else if (slot) MapView.mount(slot); },
    wide: isWide(),
  };
  const fn = SCREENS[name] || SCREENS[""];
  try { cleanup = fn(panel, { id, ...params }, ctx) || null; } catch (e) { console.error(e); panel.innerHTML = `<p class="note">화면을 그리지 못했어요</p>`; }
  if (!mapOwned) {
    if (isWide()) startSide(name === "stop" ? params : null);
    else { stopSide(); MapView.el.remove(); }
  }
}

// ── 넓은 화면 오른쪽: 화면이 지도를 쓰지 않으면 정류장 지도 ──
function stopSide() { if (sideCleanup) { sideCleanup(); sideCleanup = null; } side.querySelector(".side-card")?.remove(); }
function startSide(focus) {
  stopSide();
  MapView.mount(side);
  if (focus && focus.lat) MapView.view(+focus.lat, +focus.lng, 17);
  sideCleanup = stopsMap(side, focus && focus.lat ? { id: parse().id, lat: +focus.lat, lng: +focus.lng } : null);
}

/** 정류장 지도: 확대하면 보이는 곳 정류장, 누르면 카드 → 도착 정보 (지도 위에 겹쳐 그림) */
export function stopsMap(host, focus) {
  let stops = [], picked = focus;
  let timer = null, alive = true;
  const card = document.createElement("div");
  card.className = "side-card";
  host.appendChild(card);
  const draw = () => {
    const primary = getComputedStyle(document.documentElement).getPropertyValue("--primary").trim();
    MapView.dots(
      [...stops.filter((s) => !picked || s.id !== picked.id), ...(picked && picked.name ? [picked] : [])]
        .map((s) => ({ id: s.id, lat: s.lat, lng: s.lng, color: picked && s.id === picked.id ? "#D7262E" : primary, ring: "#fff", r: picked && s.id === picked.id ? 8 : 5 })),
      (d) => { picked = stops.find((s) => s.id === d.id) || picked; draw(); },
    );
    if (picked && picked.name) {
      card.style.display = "flex";
      card.innerHTML = `<div><b>${esc(picked.name)}</b><small>${picked.no ? "정류장 번호 " + esc(picked.no) : ""}</small></div><button class="btn" ${act(() => go("stop", picked.id, { lat: picked.lat, lng: picked.lng }))}>도착 정보</button>`;
      cityName(picked.city).then((c) => { const sm = card.querySelector("small"); if (sm && c) sm.textContent = [c, picked.no ? "정류장 번호 " + picked.no : null].filter(Boolean).join(" · "); });
    } else card.style.display = "none";
  };
  const load = () => {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      const b = MapView.bounds();
      if (!b || !alive) return;
      stops = MapView.zoom() < 15 ? [] : await stopsIn(b.s, b.w, b.n, b.e).catch(() => stops);
      if (focus && !picked?.name) picked = stops.find((s) => s.id === focus.id) || picked;
      if (alive) draw();
    }, 300);
  };
  const off = MapView.onIdle(load);
  MapView.onTap(() => { picked = null; draw(); });
  MapView.ready.then(load);
  return () => { alive = false; off(); card.remove(); };
}

window.addEventListener("hashchange", show);
wideQ.addEventListener("change", show);
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", applyTheme);
applyTheme();
show();
document.getElementById("splash")?.remove();
