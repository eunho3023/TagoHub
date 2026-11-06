// 지도 하나 (화면 전체에서 같은 지도를 옮겨 씀). 카카오 지도(폰 앱과 같음), 키·도메인이 안 맞으면 OpenStreetMap(Leaflet).
// 확대 단계는 웹 지도 기준 z (카카오 레벨 = 20 - z).
// dots: [{id, lat, lng, color, ring, r, label}], lines: [{pts:[[위도,경도]...], color, width}]
import { follow } from "./geo.js";

const el = document.createElement("div");
el.className = "map";
let impl = null;           // { view, fit, lines, dots, bounds, zoom, relayout }
let idleCbs = [], tapCb = null, dotCb = null;
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
  const size = d.r * 2;
  const wrap = document.createElement("div");
  wrap.className = "mdot-wrap";
  const dot = document.createElement("div");
  dot.className = "mdot";
  dot.style.cssText = `width:${size}px;height:${size}px;background:${d.color};border-color:${d.ring || "#fff"}`;
  // 진행 방향 화살표 (d.dir: 북쪽 0도, 시계 방향)
  if (d.dir != null && Number.isFinite(d.dir)) {
    dot.style.display = "flex"; dot.style.alignItems = "center"; dot.style.justifyContent = "center";
    dot.innerHTML = `<svg width="${size - 4}" height="${size - 4}" viewBox="0 0 10 10" style="transform:rotate(${Math.round(d.dir)}deg)"><path d="M2 7 L5 2.5 L8 7" fill="none" stroke="${d.arrow || "#fff"}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
  }
  wrap.appendChild(dot);
  if (d.label) {
    const t = document.createElement("div");
    t.className = "mdot-label";
    t.textContent = d.label;
    t.style.cssText = `left:${d.r + 6}px;background:${d.color}`;
    wrap.appendChild(t);
  }
  // 카카오 지도는 마우스 누름을 지도 끌기로 가져가서, 점을 누른 것은 직접 받음 (끌다가 놓은 것은 무시)
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
  K.event.addListener(map, "click", () => tapCb && tapCb());
  let lineObjs = [], dotObjs = [], meObj = null, meRing = null;
  const lv = (z) => Math.min(14, Math.max(1, 20 - z));
  return {
    view(lat, lng, z) { map.setLevel(lv(z)); map.setCenter(new K.LatLng(lat, lng)); },
    fit(pts) { if (!pts.length) return; const b = new K.LatLngBounds(); pts.forEach((p) => b.extend(new K.LatLng(p[0], p[1]))); map.setBounds(b, 40, 40, 40, 40); },
    lines(ls) {
      lineObjs.forEach((o) => o.setMap(null)); lineObjs = [];
      for (const l of ls) {
        const path = l.pts.map((p) => new K.LatLng(p[0], p[1]));
        lineObjs.push(new K.Polyline({ map, path, strokeWeight: l.width + 4, strokeColor: "#ffffff", strokeOpacity: 0.9 }));
        lineObjs.push(new K.Polyline({ map, path, strokeWeight: l.width, strokeColor: l.color, strokeOpacity: 1, strokeStyle: l.dash ? "shortdash" : "solid" }));
      }
    },
    dots(ds) {
      dotObjs.forEach((o) => o.setMap(null));
      dotObjs = ds.map((d) => new K.CustomOverlay({ map, position: new K.LatLng(d.lat, d.lng), content: dotEl(d), xAnchor: 0.5, yAnchor: 0.5, zIndex: d.label ? 3 : 2, clickable: true }));
    },
    bounds() { const b = map.getBounds(), sw = b.getSouthWest(), ne = b.getNorthEast(); return { s: sw.getLat(), w: sw.getLng(), n: ne.getLat(), e: ne.getLng() }; },
    zoom() { return 20 - map.getLevel(); },
    center() { const c = map.getCenter(); return [c.getLat(), c.getLng()]; },
    relayout() { map.relayout(); },
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

async function leafletImpl() {
  const css = document.createElement("link");
  css.rel = "stylesheet"; css.href = "https://unpkg.com/leaflet@1.9.4/dist/leaflet.css";
  document.head.appendChild(css);
  await loadScript("https://unpkg.com/leaflet@1.9.4/dist/leaflet.js");
  const L = window.L;
  const map = L.map(el, { zoomControl: true }).setView([START[0], START[1]], 16);
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap" }).addTo(map);
  map.on("moveend", () => idleCbs.forEach((f) => f()));
  map.on("click", () => tapCb && tapCb());
  const lineLayer = L.layerGroup().addTo(map), dotLayer = L.layerGroup().addTo(map), meLayer = L.layerGroup().addTo(map);
  let meMark = null, meCircle = null;
  return {
    view(lat, lng, z) { map.setView([lat, lng], z); },
    fit(pts) { if (pts.length) map.fitBounds(pts, { padding: [40, 40] }); },
    lines(ls) {
      lineLayer.clearLayers();
      for (const l of ls) {
        L.polyline(l.pts, { color: "#fff", weight: l.width + 4, opacity: 0.9 }).addTo(lineLayer);
        L.polyline(l.pts, { color: l.color, weight: l.width, dashArray: l.dash ? "6 6" : null }).addTo(lineLayer);
      }
    },
    dots(ds) {
      dotLayer.clearLayers();
      for (const d of ds) L.marker([d.lat, d.lng], { icon: L.divIcon({ html: dotEl(d), className: "", iconSize: [0, 0] }), zIndexOffset: d.label ? 1000 : 0 }).addTo(dotLayer);
    },
    bounds() { const b = map.getBounds(); return { s: b.getSouth(), w: b.getWest(), n: b.getNorth(), e: b.getEast() }; },
    zoom() { return map.getZoom(); },
    center() { const c = map.getCenter(); return [c.lat, c.lng]; },
    relayout() { map.invalidateSize(); },
    me(p) {
      if (!p) { meLayer.clearLayers(); meMark = meCircle = null; return; }
      if (!meMark) {
        meCircle = L.circle([p[0], p[1]], { radius: p[2] || 0, weight: 1, color: "#2563eb", opacity: 0.4, fillOpacity: 0.12 }).addTo(meLayer);
        meMark = L.marker([p[0], p[1]], { icon: L.divIcon({ html: meEl(), className: "", iconSize: [0, 0] }), zIndexOffset: 2000, interactive: false }).addTo(meLayer);
      } else { meMark.setLatLng([p[0], p[1]]); meCircle.setLatLng([p[0], p[1]]); meCircle.setRadius(p[2] || 0); }
    },
  };
}

const ready = (async () => {
  // 크기가 생긴 뒤에 지도를 만듦
  while (!el.isConnected || el.clientWidth === 0) await new Promise((r) => setTimeout(r, 100));
  try { impl = await kakaoImpl(); } catch (e) { el.innerHTML = ""; impl = await leafletImpl(); }
  new ResizeObserver(() => impl.relayout()).observe(el);
  el.appendChild(locBtn);
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
  lines(ls) { ready.then((m) => m.lines(ls)); },
  dots(ds, onDot) { dotCb = onDot || null; ready.then((m) => m.dots(ds)); },
  clear() { dotCb = null; tapCb = null; ready.then((m) => { m.lines([]); m.dots([]); }); },
  onTap(f) { tapCb = f; },
  /** 지도를 옮기거나 확대할 때마다 (돌려준 함수로 그만 받기) */
  onIdle(f) { idleCbs.push(f); return () => { idleCbs = idleCbs.filter((x) => x !== f); }; },
  bounds() { return impl ? impl.bounds() : null; },
  zoom() { return impl ? impl.zoom() : 0; },
};
