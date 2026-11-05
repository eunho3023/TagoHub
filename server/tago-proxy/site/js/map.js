// 지도 하나 (화면 전체에서 같은 지도를 옮겨 씀). 카카오 지도(폰 앱과 같음), 키·도메인이 안 맞으면 OpenStreetMap(Leaflet).
// 확대 단계는 웹 지도 기준 z (카카오 레벨 = 20 - z).
// dots: [{id, lat, lng, color, ring, r, label}], lines: [{pts:[[위도,경도]...], color, width}]

const el = document.createElement("div");
el.className = "map";
let impl = null;           // { view, fit, lines, dots, bounds, zoom, relayout }
let idleCbs = [], tapCb = null, dotCb = null;
let pendingView = null;

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

async function kakaoImpl() {
  const key = window.TAGOHUB_KAKAO_JS_KEY || "";
  if (!key) throw new Error("키 없음");
  await Promise.race([
    loadScript("https://dapi.kakao.com/v2/maps/sdk.js?autoload=false&appkey=" + encodeURIComponent(key)).then(() => new Promise((r) => kakao.maps.load(r))),
    new Promise((_, rej) => setTimeout(() => rej(new Error("시간 초과")), 10000)),
  ]);
  const K = kakao.maps;
  const map = new K.Map(el, { center: new K.LatLng(36.3504, 127.3845), level: 4 });
  // 도메인이 등록되지 않으면 지도 그림이 안 나옴: 타일이 하나라도 그려지는지 잠깐 봄
  await new Promise((res, rej) => {
    let ok = false;
    K.event.addListener(map, "tilesloaded", () => { ok = true; res(); });
    setTimeout(() => (ok ? res() : rej(new Error("지도 그림 없음"))), 6000);
  });
  map.addControl(new K.ZoomControl(), K.ControlPosition.RIGHT);
  K.event.addListener(map, "idle", () => idleCbs.forEach((f) => f()));
  K.event.addListener(map, "click", () => tapCb && tapCb());
  let lineObjs = [], dotObjs = [];
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
    relayout() { map.relayout(); },
  };
}

async function leafletImpl() {
  const css = document.createElement("link");
  css.rel = "stylesheet"; css.href = "https://unpkg.com/leaflet@1.9.4/dist/leaflet.css";
  document.head.appendChild(css);
  await loadScript("https://unpkg.com/leaflet@1.9.4/dist/leaflet.js");
  const L = window.L;
  const map = L.map(el, { zoomControl: true }).setView([36.3504, 127.3845], 16);
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap" }).addTo(map);
  map.on("moveend", () => idleCbs.forEach((f) => f()));
  map.on("click", () => tapCb && tapCb());
  const lineLayer = L.layerGroup().addTo(map), dotLayer = L.layerGroup().addTo(map);
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
    relayout() { map.invalidateSize(); },
  };
}

const ready = (async () => {
  // 크기가 생긴 뒤에 지도를 만듦
  while (!el.isConnected || el.clientWidth === 0) await new Promise((r) => setTimeout(r, 100));
  try { impl = await kakaoImpl(); } catch (e) { el.innerHTML = ""; impl = await leafletImpl(); }
  new ResizeObserver(() => impl.relayout()).observe(el);
  if (pendingView) { const v = pendingView; pendingView = null; v(); }
  return impl;
})();

export const MapView = {
  el,
  ready,
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
