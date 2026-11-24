// 지도 하나 (화면 전체에서 같은 지도를 옮겨 씀). 11월 23일부터(core.js PUB_FROM): 그리는 도구는 Leaflet(타볼까가 직접 줌, site/vendor),
// 지도 그림은 브이월드(국토교통부), 브이월드 키가 없거나 그림이 안 오면 OpenStreetMap.
// 그 전까지는 카카오 지도(키·도메인이 안 맞으면 Leaflet + OpenStreetMap). 확대 단계는 웹 지도 기준 z (카카오 레벨 = 20 - z).
// dots: [{id, lat, lng, color, ring, r, label, bus}], lines: [{pts:[[위도,경도]...], color, width}]
// bus: 버스 정류장 아이콘 (둥근 네모 + 흰 버스, color 바탕)
import { follow } from "./geo.js";
import { reconcile, dotKey, lineKey } from "./mapdiff.js";
import { PUB_ON } from "./core.js";

const el = document.createElement("div");
el.className = "map";
let impl = null;           // { view, fit, lines, dots, bounds, zoom, relayout }
let idleCbs = [], tapCb = null, dotCb = null;
// 지도 오른쪽 클릭(PC)·꾹 누르기(폰·태블릿): 출발·도착·경유 고르기 (app.js 가 한 번 정함, 화면이 바뀌어도 그대로)
let ctxCb = null, ctxAt = 0;
const fireCtx = (ll) => { if (!ll || !ctxCb || Date.now() - ctxAt < 700) return; ctxAt = Date.now(); ctxCb(ll); };
let pendingView = null;
// 지난번 내 위치 (이 기기 브라우저에만 저장): 지도를 처음부터 그 자리로 열어 바로 보이게
const LAST_KEY = "tagohub.lastPos";
const START = (() => {
  try { const v = JSON.parse(localStorage.getItem(LAST_KEY)); if (Array.isArray(v) && Number.isFinite(v[0]) && Number.isFinite(v[1])) return v; } catch (e) { /* 없음 */ }
  return [36.3504, 127.3845];
})();
let savedAt = 0;
function saveLast(p) {
  if (Date.now() - savedAt < 30000) return;
  savedAt = Date.now();
  try { localStorage.setItem(LAST_KEY, JSON.stringify([+p[0].toFixed(5), +p[1].toFixed(5)])); } catch (e) { /* 못 씀 */ }
}

function loadScript(src) {
  return new Promise((res, rej) => { const s = document.createElement("script"); s.src = src; s.onload = res; s.onerror = rej; document.head.appendChild(s); });
}

function dotEl(d) {
  // 노선 선 위 진행 방향 화살표 (d.chevron: 북쪽 0도, 시계 방향). 눌러지지 않음
  if (d.chevron != null) {
    const a = document.createElement("div");
    a.className = "mline-arrow";
    a.innerHTML = `<svg width="12" height="12" viewBox="0 0 10 10" style="transform:rotate(${Math.round(d.chevron)}deg)"><path d="M2 7 L5 3 L8 7" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
    return a;
  }
  const size = d.r * 2;
  const wrap = document.createElement("div");
  wrap.className = "mdot-wrap";
  const dot = document.createElement("div");
  dot.className = "mdot";
  dot.style.cssText = `width:${size}px;height:${size}px;background:${d.color};border-color:${d.ring || "#fff"}`;
  if (d.bus) {
    dot.className = "mdot mbus";
    dot.innerHTML = `<svg width="${Math.round(size * 0.78)}" height="${Math.round(size * 0.78)}" viewBox="0 0 24 24"><path fill="#fff" d="M4 16c0 .88.39 1.67 1 2.22V20c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h8v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1.78c.61-.55 1-1.34 1-2.22V6c0-3.5-3.58-4-8-4s-8 .5-8 4v10zm3.5 1c-.83 0-1.5-.67-1.5-1.5S6.67 14 7.5 14s1.5.67 1.5 1.5S8.33 17 7.5 17zm9 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm1.5-6H6V6h12v5z"/></svg>`;
  }
  // 진행 방향 화살표 (d.dir: 북쪽 0도, 시계 방향)
  if (d.dir != null && Number.isFinite(d.dir)) {
    dot.style.display = "flex"; dot.style.alignItems = "center"; dot.style.justifyContent = "center";
    dot.innerHTML = `<svg width="${size - 4}" height="${size - 4}" viewBox="0 0 10 10" style="transform:rotate(${Math.round(d.dir)}deg)"><path d="M2 7 L5 2.5 L8 7" fill="none" stroke="${d.arrow || "#fff"}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
  }
  wrap.appendChild(dot);
  if (d.label) {
    const t = document.createElement("div");
    t.className = d.labelUp ? "mdot-label up" : "mdot-label";
    t.textContent = d.label;
    t.style.cssText = d.labelUp ? `bottom:${d.r + 8}px;background:${d.labelColor || d.color}` : `left:${d.r + 6}px;background:${d.labelColor || d.color}`;
    wrap.appendChild(t);
  }
  // 지도가 마우스 누름을 지도 끌기로 가져가지 않게, 점을 누른 것은 직접 받음 (끌다가 놓은 것은 무시)
  let down = null;
  wrap.addEventListener("pointerdown", (e) => { down = [e.clientX, e.clientY]; e.stopPropagation(); });
  wrap.addEventListener("pointerup", (e) => {
    if (!down) return;
    const moved = Math.abs(e.clientX - down[0]) + Math.abs(e.clientY - down[1]);
    down = null;
    e.stopPropagation();
    if (moved < 8 && dotCb) dotCb(d);
  });
  wrap.addEventListener("click", (e) => e.stopPropagation());
  return wrap;
}

/** 내 위치 점 (파란 점 + 퍼지는 테두리) */
function meEl() {
  const d = document.createElement("div");
  d.className = "me-dot";
  return d;
}

// 카카오 지도: 11월 22일까지 모두가 쓰던 것. 지금은 관리자가 "지도 고치기" 중일 때만 (브이월드 그림에는 버스 정류장 표시가 없어
// 정류장 자리를 맞춰 볼 수 없음). 관리자 자신의 브라우저에서만 카카오로 지도 화면을 받음
async function kakaoImpl() {
  const key = window.TAGOHUB_KAKAO_JS_KEY || "";
  if (!key) throw new Error("키 없음");
  await Promise.race([
    loadScript("https://dapi.kakao.com/v2/maps/sdk.js?autoload=false&appkey=" + encodeURIComponent(key)).then(() => new Promise((r) => kakao.maps.load(r))),
    new Promise((_, rej) => setTimeout(() => rej(new Error("시간 초과")), 10000)),
  ]);
  const K = kakao.maps;
  const map = new K.Map(el, { center: new K.LatLng(START[0], START[1]), level: 4 });
  // 도메인이 등록되지 않으면 지도 그림이 안 나옴: 타일이 하나라도 그려지는지 잠깐 봄
  await new Promise((res, rej) => {
    let ok = false;
    K.event.addListener(map, "tilesloaded", () => { ok = true; res(); });
    setTimeout(() => (ok ? res() : rej(new Error("지도 그림 없음"))), 6000);
  });
  map.addControl(new K.ZoomControl(), K.ControlPosition.RIGHT);
  K.event.addListener(map, "idle", () => idleCbs.forEach((f) => f()));
  K.event.addListener(map, "click", (e) => tapCb && tapCb(e && e.latLng ? [e.latLng.getLat(), e.latLng.getLng()] : null));
  K.event.addListener(map, "rightclick", (e) => fireCtx(e && e.latLng ? [e.latLng.getLat(), e.latLng.getLng()] : null));
  let lineObjs = new Map(), dotObjs = new Map(), meObj = null, meRing = null;
  const lv = (z) => Math.min(14, Math.max(1, 20 - z));
  return {
    view(lat, lng, z) { map.setLevel(lv(z)); map.setCenter(new K.LatLng(lat, lng)); },
    fit(pts) { if (!pts.length) return; const b = new K.LatLngBounds(); pts.forEach((p) => b.extend(new K.LatLng(p[0], p[1]))); map.setBounds(b, 40, 40, 40, 40); },
    lines(ls) {
      lineObjs = reconcile(lineObjs, ls, lineKey, (l) => {
        const path = l.pts.map((p) => new K.LatLng(p[0], p[1]));
        // 흰 테두리는 l.casing === false 면 빼고 하나만 (서울 소통 선 수백 개: 그리는 수를 반으로)
        return [...(l.casing === false ? [] : [new K.Polyline({ map, path, strokeWeight: l.width + 4, strokeColor: "#ffffff", strokeOpacity: 0.9 })]),
          new K.Polyline({ map, path, strokeWeight: l.width, strokeColor: l.color, strokeOpacity: 1, strokeStyle: l.dash ? "shortdash" : "solid" })];
      }, (o) => o.forEach((x) => x.setMap(null)));
    },
    dots(ds) {
      dotObjs = reconcile(dotObjs, ds, dotKey,
        (d) => new K.CustomOverlay({ map, position: new K.LatLng(d.lat, d.lng), content: dotEl(d), xAnchor: 0.5, yAnchor: 0.5, zIndex: d.chevron != null ? 1 : d.label ? 3 : 2, clickable: d.chevron == null }),
        (o) => o.setMap(null));
    },
    bounds() { const b = map.getBounds(), sw = b.getSouthWest(), ne = b.getNorthEast(); return { s: sw.getLat(), w: sw.getLng(), n: ne.getLat(), e: ne.getLng() }; },
    zoom() { return 20 - map.getLevel(); },
    center() { const c = map.getCenter(); return [c.getLat(), c.getLng()]; },
    relayout() { map.relayout(); },
    /** 지도 종류: normal 일반 · sky 위성(도로명·지명 함께) */
    setType(t) { map.setMapTypeId(t === "sky" ? K.MapTypeId.HYBRID : K.MapTypeId.ROADMAP); },
    /** 내 위치 점 (null 이면 지움) + 정확도 원 */
    me(p) {
      if (!p) { meObj?.setMap(null); meRing?.setMap(null); meObj = meRing = null; return; }
      const at = new K.LatLng(p[0], p[1]);
      if (!meObj) {
        meObj = new K.CustomOverlay({ map, position: at, content: meEl(), xAnchor: 0.5, yAnchor: 0.5, zIndex: 5 });
        meRing = new K.Circle({ map, center: at, radius: p[2] || 0, strokeWeight: 1, strokeColor: "#2563eb", strokeOpacity: 0.4, fillColor: "#2563eb", fillOpacity: 0.12 });
      } else { meObj.setPosition(at); meRing.setPosition(at); meRing.setRadius(p[2] || 0); }
    },
  };
}

const LEAFLET = "vendor/leaflet-1.9.4/";
const dark = () => document.documentElement.dataset.theme === "dark";

/** OpenStreetMap 그림 (브이월드 키가 없거나 브이월드가 안 될 때). 위성은 Esri */
function osmSet(L) {
  const day = L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap" });
  return { vworld: false, day, night: day, sky: L.tileLayer("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", { maxZoom: 19, attribution: "Imagery © Esri" }) };
}

/**
 * 브이월드(국토교통부 공간정보 오픈플랫폼) WMTS 그림: 일반(Base)·야간(midnight)·위성(Satellite)+글자(Hybrid).
 * 우리나라만 그려져 있어 6단계보다 멀리는 못 봄. 키는 웹 페이지에 들어가는 공개 키(config.js, deploy.ps1)
 */
function tileSet(L) {
  const key = (window.TAGOHUB_VWORLD_KEY || "").replace(/[^A-Za-z0-9-]/g, "");
  if (!key || !PUB_ON()) return osmSet(L);
  const url = (layer, ext) => `https://api.vworld.kr/req/wmts/1.0.0/${key}/${layer}/{z}/{y}/{x}.${ext}`;
  const opt = { minZoom: 6, maxZoom: 19, attribution: "© 브이월드(국토교통부)" };
  return {
    vworld: true,
    day: L.tileLayer(url("Base", "png"), opt),
    night: L.tileLayer(url("midnight", "png"), opt),
    sky: L.layerGroup([L.tileLayer(url("Satellite", "jpeg"), opt), L.tileLayer(url("Hybrid", "png"), opt)]),
  };
}

async function leafletImpl() {
  const css = document.createElement("link");
  css.rel = "stylesheet"; css.href = LEAFLET + "leaflet.css";
  document.head.appendChild(css);
  await loadScript(LEAFLET + "leaflet.js");
  const L = window.L;
  // renderer padding 1: 선(노선·길찾기)을 화면 둘레 한 화면씩 더 그려 둠. 기본(0.1)은 지도를 끄는 동안 화면 밖에 있던 부분이 비어 있다가
  // 손을 떼야 다시 그려져서, 노선 화면에서 지도를 누르고 움직이면 선이 사라졌다 나오는 것처럼 보였음 (2026-11-24)
  // tapHold: 폰·태블릿에서 꾹 누르면 contextmenu (출발·도착·경유 고르기). 안드로이드 크롬은 브라우저도 contextmenu 를 내서 fireCtx 가 한 번만 받음
  const map = L.map(el, { zoomControl: true, renderer: L.svg({ padding: 1 }), tapHold: true }).setView([START[0], START[1]], 16);
  const tiles = tileSet(L);
  let base = null, type = "normal";
  /** 지금 지도 종류·밝기에 맞는 그림 층 (브이월드 그림이 안 오면 OpenStreetMap 으로 바꿈) */
  const paint = () => {
    const want = type === "sky" ? tiles.sky : dark() ? tiles.night : tiles.day;
    if (want === base) return;
    if (base) map.removeLayer(base);
    base = want.addTo(map);
  };
  // 처음 8초 동안 브이월드 그림이 하나도 안 오고 실패만 있으면(키·서비스 주소 문제, 브이월드 고장) OpenStreetMap 으로.
  // 바다·나라 밖 칸은 원래 그림이 없어 실패하므로 하나라도 오면 그대로 둠
  if (tiles.vworld) {
    let loaded = 0, failed = 0;
    const watch = (layer) => (layer.eachLayer ? layer.eachLayer(watch) : layer.on({ tileload: () => { loaded++; }, tileerror: () => { failed++; } }));
    [tiles.day, tiles.night, tiles.sky].forEach(watch);
    setTimeout(() => {
      if (loaded || !failed) return;
      if (base) map.removeLayer(base);
      base = null; Object.assign(tiles, osmSet(L)); paint();
    }, 8000);
  }
  // 웹 설정에서 밝게·어둡게를 바꾸면 지도 그림도 (브이월드 야간 지도)
  new MutationObserver(paint).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
  map.on("moveend", () => idleCbs.forEach((f) => f()));
  map.on("click", (e) => tapCb && tapCb(e && e.latlng ? [e.latlng.lat, e.latlng.lng] : null));
  map.on("contextmenu", (e) => fireCtx(e && e.latlng ? [e.latlng.lat, e.latlng.lng] : null));
  const lineLayer = L.layerGroup().addTo(map), dotLayer = L.layerGroup().addTo(map), meLayer = L.layerGroup().addTo(map);
  paint();
  let meMark = null, meCircle = null, lineObjs = new Map(), dotObjs = new Map();
  return {
    view(lat, lng, z) { map.setView([lat, lng], z); },
    fit(pts) { if (pts.length) map.fitBounds(pts, { padding: [40, 40] }); },
    lines(ls) {
      lineObjs = reconcile(lineObjs, ls, lineKey, (l) => [
        ...(l.casing === false ? [] : [L.polyline(l.pts, { color: "#fff", weight: l.width + 4, opacity: 0.9 }).addTo(lineLayer)]),
        L.polyline(l.pts, { color: l.color, weight: l.width, dashArray: l.dash ? "6 6" : null }).addTo(lineLayer),
      ], (o) => o.forEach((x) => lineLayer.removeLayer(x)));
    },
    dots(ds) {
      dotObjs = reconcile(dotObjs, ds, dotKey,
        (d) => L.marker([d.lat, d.lng], { icon: L.divIcon({ html: dotEl(d), className: "", iconSize: [0, 0] }), zIndexOffset: d.label ? 1000 : 0 }).addTo(dotLayer),
        (o) => dotLayer.removeLayer(o));
    },
    bounds() { const b = map.getBounds(); return { s: b.getSouth(), w: b.getWest(), n: b.getNorth(), e: b.getEast() }; },
    zoom() { return map.getZoom(); },
    center() { const c = map.getCenter(); return [c.lat, c.lng]; },
    relayout() { map.invalidateSize(); },
    /** 지도 종류: normal 일반(어두운 화면이면 야간) · sky 위성(도로명·지명 함께) */
    setType(t) { type = t === "sky" ? "sky" : "normal"; paint(); },
    me(p) {
      if (!p) { meLayer.clearLayers(); meMark = meCircle = null; return; }
      if (!meMark) {
        meCircle = L.circle([p[0], p[1]], { radius: p[2] || 0, weight: 1, color: "#2563eb", opacity: 0.4, fillOpacity: 0.12 }).addTo(meLayer);
        meMark = L.marker([p[0], p[1]], { icon: L.divIcon({ html: meEl(), className: "", iconSize: [0, 0] }), zIndexOffset: 2000, interactive: false }).addTo(meLayer);
      } else { meMark.setLatLng([p[0], p[1]]); meCircle.setLatLng([p[0], p[1]]); meCircle.setRadius(p[2] || 0); }
    },
  };
}

let pendLines = null, pendDots = null, paintAsked = false;
function paintSoon() {
  if (paintAsked) return;
  paintAsked = true;
  ready.then((m) => {
    const run = () => {
      paintAsked = false;
      const ls = pendLines, ds = pendDots;
      pendLines = pendDots = null;
      if (ls) m.lines(ls);
      if (ds) m.dots(ds);
    };
    // 화면이 숨겨져 있으면 requestAnimationFrame 이 멈춰서 바로 그림
    if (document.hidden || typeof requestAnimationFrame !== "function") run(); else requestAnimationFrame(run);
  });
}

// 지도 종류 (normal 일반 · sky 위성): 이 브라우저에 기억
const MAPTYPE = "tagohub.mapType";
let mapType = "normal";
try { mapType = localStorage.getItem(MAPTYPE) === "sky" ? "sky" : "normal"; } catch (e) { /* 못 읽음 */ }

/** 지도 고치기 중인지 (edits.js editing 과 같은 저장 칸, 관리자만 켤 수 있음). 켜고 끌 때 edits.js 가 페이지를 다시 읽어 지도를 바꿈 */
const editingMap = () => { try { return sessionStorage.getItem("tagohub.editing") === "1"; } catch (e) { return false; } };

const ready = (async () => {
  // 크기가 생긴 뒤에 지도를 만듦
  while (!el.isConnected || el.clientWidth === 0) await new Promise((r) => setTimeout(r, 100));
  if (PUB_ON() && !editingMap()) impl = await leafletImpl();
  else try { impl = await kakaoImpl(); } catch (e) { el.innerHTML = ""; impl = await leafletImpl(); }
  new ResizeObserver(() => impl.relayout()).observe(el);
  el.appendChild(locBtn);
  if (mapType !== "normal") impl.setType(mapType);
  if (myPos) impl.me(myPos);
  if (pendingView) { const v = pendingView; pendingView = null; v(); }
  return impl;
})();

// ── 내 위치 (브라우저 GPS, 켜 두면 계속 따라 움직임) ──
let watchId = null, myPos = null, firstFix = false;
const locBtn = document.createElement("button");
locBtn.className = "locbtn";
locBtn.title = "내 위치";
locBtn.setAttribute("aria-label", "내 위치");
locBtn.innerHTML = '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/><circle cx="12" cy="12" r="8"/></svg>';
// 지도 끌기로 넘어가지 않게
for (const ev of ["pointerdown", "mousedown", "touchstart", "dblclick"]) locBtn.addEventListener(ev, (e) => e.stopPropagation());
locBtn.addEventListener("click", (e) => {
  e.stopPropagation();
  if (watchId == null) return startMe();
  // 켜져 있으면: 지도가 내 위치에서 벗어나 있으면 다시 가운데로, 이미 가운데면 끄기
  const c = impl?.center?.();
  if (myPos && c && Math.hypot((c[0] - myPos[0]) * 111000, (c[1] - myPos[1]) * 88000) > 30) impl.view(myPos[0], myPos[1], Math.max(16, impl.zoom()));
  else stopMe();
});
function showMe(p) {
  myPos = [p[0], p[1], Math.min(p[2], 2000)];
  locBtn.classList.remove("wait");
  if (impl) impl.me(myPos); else ready.then((m) => myPos && m.me(myPos));
  if (firstFix) { firstFix = false; MapView.view(myPos[0], myPos[1], 16); }
  saveLast(myPos);
}
/** 내 위치 켜기. center: 처음 받은 위치로 지도를 옮김, quiet: 못 받아도 알림창 없이 끔 (앱을 열 때 저절로 켤 때) */
function startMe(center = true, quiet = false) {
  firstFix = center;
  locBtn.classList.add("on", "wait");
  watchId = follow(
    (p) => {
      showMe(p);
    },
    (msg) => { stopMe(); if (!quiet) alert(msg); },
  );
}
function stopMe() {
  if (watchId != null) watchId();
  watchId = null; myPos = null;
  locBtn.classList.remove("on", "wait");
  impl?.me(null);
}

export const MapView = {
  el,
  ready,
  /** 앱을 열 때: 위치를 막아 두지 않았으면 내 위치를 켬 (center 면 지도도 내 위치로). 못 받으면 조용히 */
  async autoLocate(center) {
    if (watchId != null || !("geolocation" in navigator)) return;
    try { if ((await navigator.permissions.query({ name: "geolocation" })).state === "denied") return; } catch (e) { /* 모르면 물어봄 */ }
    startMe(center, true);
    // 휴대폰이 이미 알고 있는 위치(10분 안)를 바로 받아 먼저 보여 줌. 정확한 GPS 는 따라가기로 이어서
    navigator.geolocation.getCurrentPosition(
      (p) => { if (watchId != null && !myPos) showMe([p.coords.latitude, p.coords.longitude, p.coords.accuracy || 0]); },
      () => {}, { enableHighAccuracy: false, maximumAge: 600000, timeout: 4000 },
    );
  },
  /** 지도를 이 칸 안으로 옮김 */
  mount(container) { if (el.parentElement !== container) { container.appendChild(el); impl?.relayout(); } },
  view(lat, lng, z) { if (impl) impl.view(lat, lng, z); else pendingView = () => impl.view(lat, lng, z); },
  fit(pts) { if (impl) impl.fit(pts); else pendingView = () => impl.fit(pts); },
  // 그리기는 다음 화면 갱신 때 한 번만 (자료가 잇달아 오면 마지막 것만 그림: 정류장·소통 속도·자전거·주차장·편의시설이 따로 옴)
  lines(ls) { pendLines = ls; paintSoon(); },
  dots(ds, onDot) { dotCb = onDot || null; pendDots = ds; paintSoon(); },
  clear() { dotCb = null; tapCb = null; pendLines = []; pendDots = []; paintSoon(); },
  onTap(f) { tapCb = f; },
  /** 오른쪽 클릭·꾹 누르기 [위도, 경도] (앱 전체에서 하나, clear 로 지워지지 않음) */
  onContext(f) { ctxCb = f; },
  /** 지도를 옮기거나 확대할 때마다 (돌려준 함수로 그만 받기) */
  onIdle(f) { idleCbs.push(f); return () => { idleCbs = idleCbs.filter((x) => x !== f); }; },
  /** 지도를 옮기지 않고 그림을 다시 (고친 정류장 위치를 바로 보이게) */
  redraw() { idleCbs.forEach((f) => f()); },
  bounds() { return impl ? impl.bounds() : null; },
  /** 지금 화면에서 1px 이 몇 m 인지 (모르면 null) */
  metersPerPx() {
    const b = impl ? impl.bounds() : null;
    if (!b || !el.clientWidth) return null;
    return ((b.e - b.w) * 111000 * Math.cos((((b.s + b.n) / 2) * Math.PI) / 180)) / el.clientWidth;
  },
  zoom() { return impl ? impl.zoom() : 0; },
  /** 지도 종류 normal · sky */
  mapType() { return mapType; },
  setMapType(t) {
    mapType = t === "sky" ? "sky" : "normal";
    try { localStorage.setItem(MAPTYPE, mapType); } catch (e) { /* 못 씀 */ }
    if (impl) impl.setType(mapType);
  },
};
