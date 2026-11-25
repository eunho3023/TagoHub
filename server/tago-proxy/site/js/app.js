// 틀: 주소(#…)로 화면 바꾸기(브라우저 뒤로 가기도 됨), 아래 탭, 넓은 화면은 왼쪽 목록 + 오른쪽 지도.
// 지도 탭은 없음: 홈에 지도가 바로 보임 (좁으면 홈 안, 넓으면 오른쪽)
import { MapView } from "./map.js";
import { thinDots } from "./mapdiff.js";
import { go, isWide, wideQ } from "./nav.js";
import { resetActs, icon, act, esc } from "./ui.js";
import { cachedText, subwayColor, getText, busColor, livePoll } from "./core.js";
import { pickPlace, KAKAO_ROUTING } from "./places.js";
import { parseStations, stationsIn, pickSearchRow, stationPos } from "./stations.js";
import { applyTheme } from "./store.js";
import { stopsIn, cityName, stopRoutes, towardOf, positions, klidBuses, klidCovers, inSeoulView, routeIdByNo, routeSums, headingTracker, declutterCaptions, LowFloor } from "./busdata.js";
/** 버스 정류장 아이콘 바탕색 (파란 둥근 네모, 폰 앱 정류장 아이콘과 같게) */
export const BUS_STOP_COLOR = "#2F7CF6";
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
/** 지금 지도 화면 안 정류장·지하철역 (지도 탭과 같은 기준: 정류장 줌 15, 역 줌 12부터). 노선 화면이 함께 보여 줌 */
export async function nearbyMarks() {
  const b = MapView.bounds();
  if (!b) return { stops: [], stations: [] };
  const z = MapView.zoom();
  const [stops, stations] = await Promise.all([
    z < 15 ? [] : stopsIn(b.s, b.w, b.n, b.e).catch(() => []),
    z < STATION_ZOOM ? [] : stationGroups().then((gs) => stationsIn(gs, b.s, b.w, b.n, b.e)).catch(() => []),
  ]);
  return { stops, stations };
}
/** 지하철역 점 자리 (지도 고치기로 옮긴 자리) */
export const stationAt = (g) => stationPos(g, Edits.stationFix);
/** 역 카드에서 노선을 누르면: 역 찾기로 그 노선 역 번호를 찾아 역 화면 */
export async function openStation(g, line) {
  try {
    const row = pickSearchRow(await subway.searchStations(g.name), g.name, line);
    if (row) return go("subway", row.id, { name: row.name, line: row.line });
  } catch (e) { /* 아래 */ }
  alert("역 정보를 받지 못했어요. 잠시 뒤 다시 해 주세요.");
}
/** 정류장 지도: 확대하면 보이는 곳 정류장, 누르면 카드 → 도착 정보 (지도 위에 겹쳐 그림) */
/** 지도 버스 다시 받는 주기: 15초 (사용자 요청 2026-11-25, 5초였을 때 TAGO 버스 위치 하루 한도가 아침에 바닥남). 사이엔 새로고침 단추 */
const MAP_BUS_MS = 15000;
/** 초정밀 버스가 없는 지역: 이 확대부터 화면 안 정류장 노선마다 버스 위치를 물음, 노선은 가운데 정류장부터 이만큼 (한 번에 묻는 수) */
const VIEW_BUS_ZOOM = 15, VIEW_ROUTES = 40;
export function stopsMap(host, focus) {
  let stops = [], picked = focus, stations = [];
  let timer = null, alive = true;
  /**
   * 지도 버스 (입체 버스 모양, 15초마다·새로고침 단추): 정류장을 골랐으면 그 정류장 노선들의 버스(차량번호·저상까지),
   * 아니면 지도 범위 버스(초정밀 버스, 울산). 누르면 그 노선 화면 (정류장에서 노선 번호를 누른 것처럼)
   */
  let busDots = [], busSeq = 0;
  const heading = headingTracker();
  /** 화면 안 정류장(가운데부터)에 서는 노선들의 버스 위치 → 지도 점 */
  const viewBuses = async (b) => {
    const cla = (b.s + b.n) / 2, clo = (b.w + b.e) / 2, ids = [], city = new Map();
    for (const s of [...stops].sort((x, y) => Math.hypot(x.lat - cla, x.lng - clo) - Math.hypot(y.lat - cla, y.lng - clo))) {
      // 경기 노선(GGB)은 위치 좌표를 주지 않아 지도에 못 그림: 묻지 않음
      for (const id of s.routes || []) if (!city.has(id) && !id.startsWith("GGB") && ids.length < VIEW_ROUTES) { ids.push(id); city.set(id, s.city); }
      if (ids.length >= VIEW_ROUTES) break;
    }
    if (!ids.length) return [];
    const sums = await routeSums(ids).catch(() => ({}));
    const got = await Promise.all(ids.map((id) => positions({ id, city: city.get(id) }).then((r) => ({ id, r })).catch(() => null)));
    const out = [];
    for (const g of got) {
      if (!g || g.r.state !== "ok") continue;
      const s = sums[g.id] || {};
      for (const x of g.r.buses) {
        if (x.lat == null || x.lng == null || x.lat < b.s || x.lat > b.n || x.lng < b.w || x.lng > b.e) continue;
        if (LowFloor.on() && x.kind && x.kind !== "저상") continue;
        const key = g.id + "|" + (x.vehicleNo || out.length);
        out.push({ id: "vb:" + key, lat: x.lat, lng: x.lng, color: busColor(s.type), ring: "#fff", r: 8,
          bus3d: { no: s.no || "버스", caption: [x.kind, x.vehicleNo].filter(Boolean).join(" · ") || null, dir: heading(key, x.lat, x.lng) },
          go: { id: g.id, city: city.get(g.id), no: s.no, type: s.type } });
      }
    }
    return out;
  };
  const busTick = async () => {
    if (!alive) return true;
    const my = ++busSeq, p0 = picked, b = MapView.bounds(), z = MapView.zoom();
    let out = [];
    try {
      if (p0 && p0.name && p0.id) {
        const sums = await stopRoutes(p0).catch(() => []);
        const got = await Promise.all(sums.filter((s) => s.id).map((s) => positions({ id: s.id, city: s.city || p0.city }).then((r) => ({ s, r })).catch(() => null)));
        for (const g of got) {
          if (!g || g.r.state !== "ok") continue;
          for (const x of g.r.buses) {
            if (x.lat == null || x.lng == null) continue;
            // 저상버스만 보기: 차 종류를 아는 지역만 거름 (모르는 지역은 그대로)
            if (LowFloor.on() && x.kind && x.kind !== "저상") continue;
            const key = g.s.id + "|" + (x.vehicleNo || out.length);
            out.push({ id: "sb:" + key, lat: x.lat, lng: x.lng, color: busColor(g.s.type), ring: "#fff", r: 8,
              bus3d: { no: g.s.no, caption: [x.kind, x.vehicleNo].filter(Boolean).join(" · ") || null, dir: heading(key, x.lat, x.lng) },
              go: { id: g.s.id, city: g.s.city || p0.city, no: g.s.no, type: g.s.type, from: p0.id } });
          }
        }
      } else if (b && z >= 13) {
        out = (await klidBuses(b)).map((k) => ({ id: "kb:" + k.key, lat: k.lat, lng: k.lng, color: busColor(k.type), ring: "#fff", r: 8,
          bus3d: { no: k.no || "버스", caption: null, dir: heading(k.key, k.lat, k.lng) }, go: { id: k.routeId, city: k.city, no: k.no, type: k.type } }));
        // 초정밀 버스가 없는 지역(서울 빼고), 줌 15부터: 화면 안 정류장에 서는 노선마다 버스 위치 (가운데 정류장 노선부터 VIEW_ROUTES 개)
        if (z >= VIEW_BUS_ZOOM && !klidCovers(b) && !inSeoulView(b)) out.push(...await viewBuses(b));
      }
    } catch (e) { return false; }
    if (my !== busSeq || !alive || picked !== p0) return true;
    busDots = out;
    draw();
    return true;
  };
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
      [...declutterCaptions(busDots, MapView.zoom(), MapView.metersPerPx()), ...stations.map((g, i) => { const k = "station:" + g.keys.join("|"), at0 = stationPos(g, Edits.stationFix), at = movedAt(k, at0[0], at0[1]); return { id: "sub:" + i, lat: at[0], lng: at[1], color: subwayColor(g.lines[0]), ring: moves.has(k) ? "#E08A00" : "#fff", r: 8, label: MapView.zoom() >= 14 ? g.name + "역" : null }; }),
      ...[...stops.filter((s) => !picked || s.id !== picked.id), ...(picked && picked.name ? [picked] : [])]
        // 고른 정류장은 앱처럼 아이콘 위에 이름
        .map((s) => { const on = picked && s.id === picked.id, at = movedAt("stop:" + s.id, s.lat, s.lng); return { id: s.id, lat: at[0], lng: at[1], color: on ? "#D7262E" : moves.has("stop:" + s.id) ? "#E08A00" : BUS_STOP_COLOR, ring: "#fff", r: on ? 11 : 8, bus: true, label: on ? s.name : null, labelUp: on }; }),
      ...(traffic ? traffic.dots() : []).map((d) => { const m = moves.get("place:" + d.id); return m ? { ...d, lat: m.to[0], lng: m.to[1], ring: "#E08A00" } : d; })], MapView.metersPerPx()),
      (d) => {
        // 지도 버스: 그 노선 화면 (정류장에서 노선 번호를 누른 것처럼)
        if (d.go) {
          // 초정밀 버스 노선 id 가 우리 id 와 다른 지역(구미·양산)은 노선 번호로 찾음
          const open = (id) => { if (id) go("route", id, { from: d.go.from, city: d.go.city, no: d.go.no, type: d.go.type }); };
          if (d.go.id) open(d.go.id); else routeIdByNo(d.go.city, d.go.no).then(open);
          return;
        }
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
        if (/^(acc|bike|park|pl|cctv|rest|gas|st):/.test(d.id)) {
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
        busDots = []; busPoll?.now();
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
      card.innerHTML = `<div><b>${esc(picked.name)}</b><small>${picked.no ? "정류장 번호 " + esc(picked.no) : ""}</small></div><button class="btn" ${act(() => go("stop", picked.id, { lat: picked.lat, lng: picked.lng }))}>도착 정보</button><button class="link" ${act(busNow)}>↻ 버스 새로고침</button>` +
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
      // 지도를 옮기면 지도 범위 버스도 바로 (정류장 노선 버스는 15초마다 그대로)
      if (alive && !picked?.name) busPoll?.now();
    }, 300);
  };
  let busPoll = null, busAsked = 0;
  /** 버스 새로고침 단추: 바로 다시 받음 (3초 안에 여러 번 누르면 한 번만) */
  const busNow = () => { if (Date.now() - busAsked < 3000) return; busAsked = Date.now(); busPoll?.now(); };
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
    picked = null; info = null; moving = null; busDots = []; busPoll?.now(); drawBanner(); draw();
  });
  MapView.ready.then(() => { load(); if (alive) busPoll = livePoll(busTick, MAP_BUS_MS); });
  return () => { alive = false; busPoll?.stop(); off(); offEdit(); card.remove(); banner.remove(); traffic.destroy(); MapView.lines([]); };
}

// ── 지도 오른쪽 클릭(PC)·꾹 누르기(폰·태블릿): 그 자리를 출발·경유·도착으로 (모든 지도 화면) ──
const pickCard = document.createElement("div");
pickCard.className = "side-card pick-card";
pickCard.style.zIndex = "650";
for (const ev of ["pointerdown", "mousedown", "touchstart", "dblclick", "contextmenu", "click"]) pickCard.addEventListener(ev, (e) => e.stopPropagation());
let pickSeq = 0;
MapView.onContext((ll) => {
  if (!KAKAO_ROUTING) return;
  const my = ++pickSeq;
  const place = { name: "지도에서 고른 곳", lat: +ll[0].toFixed(6), lng: +ll[1].toFixed(6), address: null };
  // 이름·주소 (브이월드, 서버 /w/pub/where): 받는 대로 카드에 채움. 고르기 전에 못 받으면 "지도에서 고른 곳"
  const named = getText("w/pub/where", { lat: place.lat.toFixed(6), lng: place.lng.toFixed(6) }).then((t) => {
    const w = JSON.parse(t) || {};
    if (w.name) place.name = w.name; else if (w.road || w.parcel) place.name = w.road || w.parcel;
    place.address = w.road || w.parcel || null;
  }).catch(() => {});
  const fill = () => {
    if (my !== pickSeq) return;
    pickCard.innerHTML = `<div><b>${esc(place.name)}</b><small>${esc(place.address && place.address !== place.name ? place.address : "")}</small></div>` +
      `<button class="btn" data-k="from">출발</button><button class="btn" data-k="via">경유</button><button class="btn" data-k="to">도착</button><button class="link" data-k="x">닫기</button>`;
    pickCard.querySelectorAll("button").forEach((b) => b.addEventListener("click", async () => {
      const k = b.dataset.k;
      pickCard.style.display = "none";
      if (k === "x") return;
      await Promise.race([named, new Promise((r) => setTimeout(r, 1500))]);
      pickPlace(k, { ...place });
    }));
  };
  MapView.el.appendChild(pickCard);
  pickCard.style.display = "flex";
  fill();
  named.then(fill);
});
// 다른 화면으로 가면 고르기 카드는 닫음
window.addEventListener("hashchange", () => { pickSeq++; pickCard.style.display = "none"; });

window.addEventListener("hashchange", show);
wideQ.addEventListener("change", show);
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", applyTheme);
applyTheme();
show();
document.getElementById("splash")?.remove();
// 열자마자 내 위치로 (정류장·노선 같은 화면 주소로 열었으면 위치 점만 켜고 지도는 그대로)
MapView.autoLocate(/^#?\/?(map)?\/?$/.test(location.hash) || location.hash === "#/home");
