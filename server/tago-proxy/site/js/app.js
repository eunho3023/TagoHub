// 틀: 주소(#…)로 화면 바꾸기(브라우저 뒤로 가기도 됨), 아래 탭, 넓은 화면은 왼쪽 목록 + 오른쪽 지도.
// 지도 탭은 없음: 홈에 지도가 바로 보임 (좁으면 홈 안, 넓으면 오른쪽)
import { MapView } from "./map.js";
import { thinDots } from "./mapdiff.js";
import { go, isWide, wideQ } from "./nav.js";
import { resetActs, icon, act, esc } from "./ui.js";
import { cachedText, subwayColor } from "./core.js";
import { parseStations, stationsIn, pickSearchRow, stationPos } from "./stations.js";
import { applyTheme } from "./store.js";
import { stopsIn, cityName, stopRoutes, towardOf } from "./busdata.js";
/** 버스 정류장 아이콘 바탕색 (파란 둥근 네모, 폰 앱 정류장 아이콘과 같게) */
const BUS_STOP_COLOR = "#2F7CF6";
import { trafficLayer } from "./topis.js";
import * as home from "./home.js";
import * as bus from "./bus.js";
import * as more from "./more.js";
import { Alight } from "./alight.js";
import * as subway from "./subway.js";
import * as air from "./air.js";
import { fixScreen } from "./fix.js";
import { fixStopsScreen } from "./fixstops.js";
import { loginScreen } from "./login.js";
import * as Edits from "./edits.js";
import "./modes.js";

const TABS = [
  { id: "", label: "홈", icon: "home" },
  { id: "transit", label: "대중교통", icon: "bus" },
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
  stoptable: bus.stopTable,
  route: bus.routeScreen,
  subway: subway.stationScreen,
  subwaymap: subway.subwayMap,
  subwayline: subway.lineScreen,
  airport: air.airportScreen,
  report: more.reportScreen,
  fix: fixScreen,
  fixstops: fixStopsScreen,
  login: loginScreen,
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

const towardMemo = new Map();
// 지하철역 점 (data/files/subway_stations.tsv, 이 브라우저에 저장). 줌 12부터
const STATION_ZOOM = 12;
let stationsP = null;
const stationGroups = () => (stationsP ||= cachedText("data/files/subway_stations.tsv").then(parseStations).catch(() => { stationsP = null; return []; }));
/** 역 카드에서 노선을 누르면: 역 찾기로 그 노선 역 번호를 찾아 역 화면 */
async function openStation(g, line) {
  try {
    const row = pickSearchRow(await subway.searchStations(g.name), g.name, line);
    if (row) return go("subway", row.id, { name: row.name, line: row.line });
  } catch (e) { /* 아래 */ }
  alert("역 정보를 받지 못했어요. 잠시 뒤 다시 해 주세요.");
}
/** 정류장 지도: 확대하면 보이는 곳 정류장, 누르면 카드 → 도착 정보 (지도 위에 겹쳐 그림) */
export function stopsMap(host, focus) {
  let stops = [], picked = focus, stations = [];
  let timer = null, alive = true;
  /** 서울 실시간 교통 점(돌발·교통량 지점)을 눌렀을 때 카드 내용 */
  let info = null, cardSeq = 0;
  /** 관리자: 정류장 카드에 붙는 "묶기·노선 고치기" (지도 고치기 중, fixstops.js) */
  let infoFix = "";
  const fixBtn = (go2) => (Edits.editing() ? `<button class="link" ${act(go2)}>묶기·노선 고치기</button>` : "");
  // 지도 고치기 중: 정류장·역·자전거·주차장·편의시설 점을 누르고 → 지도에서 새 자리를 누르면 옮김. 옮긴 것은 모았다가 "저장하기"로 한 번에.
  // (예전엔 점마다 "위치 고치기" 화면으로 가서 하나씩 저장하거나, "정류장 한꺼번에" 화면으로 따로 가야 했음)
  /** 옮겼지만 아직 저장 안 한 점: 키 → {kind, ids, orig, to, name} */
  const moves = new Map();
  /** 옮기려고 고른 점 {key, kind, ids, orig, name} */
  let moving = null, saving = false, editMsg = "";
  const movedAt = (key, lat, lng) => moves.get(key)?.to || [lat, lng];
  const pickMove = (key, kind, ids, lat, lng, name) => {
    if (!Edits.editing() || !ids.length) { moving = null; return; }
    moving = { key, kind, ids, orig: moves.get(key)?.orig || [lat, lng], name };
    editMsg = "";
  };
  const card = document.createElement("div");
  card.className = "side-card";
  host.appendChild(card);
  // 지도 고치기 중 표시 (설정에서 켬): 점을 누르면 카드에 위치 고치기, 끝내기
  const banner = document.createElement("div");
  banner.className = "editbanner";
  host.appendChild(banner);
  const drawBanner = () => {
    const on = Edits.editing();
    banner.style.display = on ? "flex" : "none";
    if (!on) { moves.clear(); moving = null; return; }
    const hint = saving ? "저장 중..." : editMsg ? editMsg : moving ? `「${moving.name || "고른 점"}」의 새 자리를 지도에서 누르세요`
      : moves.size ? `${moves.size}곳 옮김 · 저장하기를 눌러야 반영돼요` : "점을 누르고 지도에서 새 자리를 누르면 옮겨져요";
    banner.innerHTML = `<b>✏️ 지도 고치기</b><span class="grow">${esc(hint)}</span>` +
      `<button class="btn" ${act(() => saveMoves())} ${saving || !moves.size ? "disabled" : ""}>저장하기${moves.size ? ` (${moves.size})` : ""}</button>` +
      `<button class="link" ${act(() => endEdit())}>끝내기</button>`;
  };
  async function saveMoves() {
    if (saving || !moves.size) return;
    saving = true; moving = null; drawBanner();
    let done = 0, err = "";
    for (const [key, m] of [...moves]) {
      try {
        const at = [+m.to[0].toFixed(6), +m.to[1].toFixed(6)];
        for (const id of m.ids) await Edits.save(m.kind, id, at);
        moves.delete(key); done++;
      } catch (e) { err = e.message || "저장하지 못했어요"; break; }
    }
    saving = false;
    editMsg = err ? (done ? `${done}곳 저장, ` : "") + err : `${done}곳 저장했어요. 웹과 폰 앱에 모두 보여요`;
    drawBanner(); draw(); load();
  }
  function endEdit() {
    if (moves.size && !confirm(`저장하지 않은 ${moves.size}곳이 있어요. 저장하지 않고 끝낼까요?`)) return;
    moves.clear(); moving = null; editMsg = "";
    Edits.setEditing(false);
    draw();
  }
  drawBanner();
  // 서울 실시간 교통 (켜기 버튼은 지도 오른쪽 위, 내 위치 아래): 확대하면 도로 소통 색, 돌발·교통량 지점 (topis.js). 아래 draw 를 만든 뒤에 만듦
  let traffic = null;
  const draw = () => {
    MapView.lines([...(traffic ? traffic.lines() : []), ...[...moves.values()].map((m) => ({ pts: [m.orig, m.to], color: "#9aa3b2", width: 2, dash: true }))]);
    // 점이 빽빽하면(300개 넘게) 화면 22px 칸마다 하나만 (mapdiff.js thinDots): 확대·축소 때 카카오 지도가 점 수백 개를 다시 맞추느라 멈췄음.
    // 정류장을 먼저 (교통 점보다 앞에), 고른 정류장(이름표)은 늘 남김
    MapView.dots(thinDots(
      // 지하철역이 맨 앞 (솎을 때 남게): 노선 색 점(정류장보다 크게), 줌 14부터 노선 색 이름표 (흰 점·흰 이름표는 지도에 묻혀 안 보였음)
      [...stations.map((g, i) => { const k = "station:" + g.keys.join("|"), at0 = stationPos(g, Edits.stationFix), at = movedAt(k, at0[0], at0[1]); return { id: "sub:" + i, lat: at[0], lng: at[1], color: subwayColor(g.lines[0]), ring: moves.has(k) ? "#E08A00" : "#fff", r: 8, label: MapView.zoom() >= 14 ? g.name + "역" : null }; }),
      ...[...stops.filter((s) => !picked || s.id !== picked.id), ...(picked && picked.name ? [picked] : [])]
        // 고른 정류장은 앱처럼 아이콘 위에 이름
        .map((s) => { const on = picked && s.id === picked.id, at = movedAt("stop:" + s.id, s.lat, s.lng); return { id: s.id, lat: at[0], lng: at[1], color: on ? "#D7262E" : moves.has("stop:" + s.id) ? "#E08A00" : BUS_STOP_COLOR, ring: "#fff", r: on ? 11 : 8, bus: true, label: on ? s.name : null, labelUp: on }; }),
      ...(traffic ? traffic.dots() : []).map((d) => { const m = moves.get("place:" + d.id); return m ? { ...d, lat: m.to[0], lng: m.to[1], ring: "#E08A00" } : d; })], MapView.metersPerPx()),
      (d) => {
        cardSeq++;
        // 돌발·공영자전거·주차장·편의시설·주유소·휴게소·CCTV 점은 카드 (topis.js card)
        if (d.id.startsWith("sub:")) {
          const g = stations[+d.id.slice(4)];
          if (!g) return;
          picked = null;
          info = `<div><b>${esc(g.name)}역</b><small>${esc(g.lines.join(" · "))}</small></div><div class="flexrow" style="flex-wrap:wrap">${g.lines.map((l) =>
            `<button class="btn" ${act(() => openStation(g, l))}>${esc(l)}</button>`).join("")}</div>`;
          infoFix = "";
          pickMove("station:" + g.keys.join("|"), "station", g.keys, d.lat, d.lng, g.name + "역");
          drawBanner(); draw();
          return;
        }
        if (/^(acc|bike|park|pl|cctv|rest|gas):/.test(d.id)) {
          picked = null;
          // 늦게 채워지는 카드(휴게소 상세)는 그사이 다른 점을 눌렀으면 버림
          const asked = cardSeq;
          info = traffic && traffic.card(d.id, (h) => { if (info != null && asked === cardSeq) { info = h; draw(); } });
          infoFix = "";
          if (/^(bike|park|pl):/.test(d.id)) pickMove("place:" + d.id, "place", [d.id], d.lat, d.lng, (info && /<b>([^<]*)<\/b>/.exec(info)?.[1]) || "");
          else moving = null;
          drawBanner(); draw();
          return;
        }
        info = null;
        picked = stops.find((s) => s.id === d.id) || picked;
        if (picked) pickMove("stop:" + picked.id, "stop", [picked.id], picked.lat, picked.lng, picked.name);
        drawBanner(); draw();
      },
    );
    // 옮긴 점을 다시 고르면 되돌리기
    const undo = moving && moves.has(moving.key) ? `<button class="link" ${act(() => { moves.delete(moving.key); moving = null; drawBanner(); draw(); })}>옮긴 것 되돌리기</button>` : "";
    if (info) {
      card.style.display = "flex";
      card.innerHTML = info + infoFix + undo;
    } else if (picked && picked.name) {
      card.style.display = "flex";
      card.innerHTML = `<div><b>${esc(picked.name)}</b><small>${picked.no ? "정류장 번호 " + esc(picked.no) : ""}</small></div><button class="btn" ${act(() => go("stop", picked.id, { lat: picked.lat, lng: picked.lng }))}>도착 정보</button>` +
        fixBtn(() => go("fixstops", picked.id, { lat: picked.lat, lng: picked.lng })) + undo;
      // 시군 이름과 방면(지나는 노선의 다음 정류장)을 받는 대로 채움
      const p0 = picked, info = { c: null, t: null };
      const fill = () => { const sm = card.querySelector("small"); if (sm && picked === p0) sm.textContent = [info.c, p0.no ? "정류장 번호 " + p0.no : null, info.t ? info.t + " 방면" : null].filter(Boolean).join(" · "); };
      cityName(p0.city).then((c) => { info.c = c; fill(); });
      if (!towardMemo.has(p0.id)) towardMemo.set(p0.id, stopRoutes(p0).then((rs) => towardOf(p0, rs)).catch(() => null));
      towardMemo.get(p0.id).then((t) => { info.t = t; fill(); });
    } else card.style.display = "none";
  };
  const load = () => {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      const b = MapView.bounds();
      if (!b || !alive) return;
      const z = MapView.zoom();
      [stops, stations] = await Promise.all([
        z < 15 ? [] : stopsIn(b.s, b.w, b.n, b.e).catch(() => stops),
        z < STATION_ZOOM ? [] : stationGroups().then((gs) => stationsIn(gs, b.s, b.w, b.n, b.e)),
      ]);
      if (focus && !picked?.name) picked = stops.find((s) => s.id === focus.id) || picked;
      if (alive) draw();
    }, 300);
  };
  traffic = trafficLayer(() => { if (alive) draw(); });
  const off = MapView.onIdle(load);
  // 누가 정류장 위치를 고치면 바로 다시 (지도는 그대로)
  const offEdit = Edits.onChange(() => { drawBanner(); load(); });
  MapView.onTap((ll) => {
    // 지도 고치기 중 점을 골랐으면 누른 자리로 옮김 (저장은 "저장하기"로 한 번에)
    if (Edits.editing() && moving && ll && !saving) {
      moves.set(moving.key, { kind: moving.kind, ids: moving.ids, orig: moving.orig, to: ll, name: moving.name });
      editMsg = `「${moving.name || "고른 점"}」을(를) 옮겼어요`;
      moving = null; picked = null; info = null;
      drawBanner(); draw();
      return;
    }
    picked = null; info = null; moving = null; drawBanner(); draw();
  });
  MapView.ready.then(load);
  return () => { alive = false; off(); offEdit(); card.remove(); banner.remove(); traffic.destroy(); MapView.lines([]); };
}

window.addEventListener("hashchange", show);
wideQ.addEventListener("change", show);
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", applyTheme);
applyTheme();
show();
document.getElementById("splash")?.remove();
// 열자마자 내 위치로 (정류장·노선 같은 화면 주소로 열었으면 위치 점만 켜고 지도는 그대로)
MapView.autoLocate(/^#?\/?(map)?\/?$/.test(location.hash) || location.hash === "#/home");
