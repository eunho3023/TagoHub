// 지도 화면(WebView·iframe 안)에 넣는 HTML. 카카오 JavaScript 키가 있으면 카카오맵, 없거나 못 불러오면 OpenStreetMap(Leaflet).
// 앱 → 지도: window.tago.apply(상태) 또는 postMessage(JSON). 지도 → 앱: {type:'ready'|'marker'|'moved'} 메시지.

export type MapMarker = { id: string; lat: number; lng: number; kind: 'stop' | 'here' | 'bus' | 'pin'; label?: string };
export type MapState = {
  center?: { lat: number; lng: number; zoom: number };
  markers: MapMarker[];
  line?: { points: [number, number][]; color: string } | null;
  /** true 면 표시물(선·표시)이 다 보이게 맞춤 */
  fit?: boolean;
};
export type MapEvent =
  | { type: 'ready'; engine: string }
  | { type: 'marker'; id: string }
  | { type: 'moved'; lat: number; lng: number };

export function mapHtml(kakaoJsKey?: string): string {
  const key = JSON.stringify(kakaoJsKey ?? '');
  return `<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>
html,body,#map{margin:0;height:100%;width:100%;background:#e8eaed}
.mk{width:16px;height:16px;border-radius:50%;border:3px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.4);box-sizing:border-box}
.mk.stop{background:#1f6feb}.mk.here{background:#e5484d;width:20px;height:20px}.mk.bus{background:#f59e0b;width:20px;height:20px}.mk.pin{background:#0f9d58;width:22px;height:22px}
</style></head><body><div id="map"></div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
(function(){
  var KAKAO = ${key};
  function post(o){
    var s = JSON.stringify(o);
    if (window.ReactNativeWebView) window.ReactNativeWebView.postMessage(s);
    else if (window.parent !== window) window.parent.postMessage(s, '*');
  }
  var M = null, pending = null;

  function leaflet(){
    var map = L.map('map', { zoomControl: false }).setView([36.35, 127.8], 7);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(map);
    var group = L.layerGroup().addTo(map), line = null;
    map.on('moveend', function(){ var c = map.getCenter(); post({ type: 'moved', lat: c.lat, lng: c.lng }); });
    return {
      name: 'OpenStreetMap',
      center: function(c){ map.setView([c.lat, c.lng], c.zoom); },
      markers: function(list){
        group.clearLayers();
        list.forEach(function(m){
          var mk = L.marker([m.lat, m.lng], { icon: L.divIcon({ className: '', html: '<div class="mk ' + m.kind + '"></div>', iconSize: [22, 22], iconAnchor: [11, 11] }) }).addTo(group);
          if (m.label) mk.bindTooltip(m.label);
          mk.on('click', function(){ post({ type: 'marker', id: m.id }); });
        });
      },
      line: function(l){
        if (line) { map.removeLayer(line); line = null; }
        if (l && l.points.length > 1) line = L.polyline(l.points, { color: l.color, weight: 5, opacity: 0.85 }).addTo(map);
      },
      fit: function(pts){ if (pts.length) map.fitBounds(L.latLngBounds(pts), { padding: [40, 40], maxZoom: 17 }); }
    };
  }

  function kakao(){
    var kk = window.kakao.maps;
    var map = new kk.Map(document.getElementById('map'), { center: new kk.LatLng(36.35, 127.8), level: 13 });
    var items = [], line = null;
    var level = function(z){ return Math.max(1, Math.min(14, 19 - z)); };   // Leaflet 확대 16 ≈ 카카오 레벨 3
    kk.event.addListener(map, 'idle', function(){ var c = map.getCenter(); post({ type: 'moved', lat: c.getLat(), lng: c.getLng() }); });
    return {
      name: '카카오맵',
      center: function(c){ map.setCenter(new kk.LatLng(c.lat, c.lng)); map.setLevel(level(c.zoom)); },
      markers: function(list){
        items.forEach(function(o){ o.setMap(null); });
        items = list.map(function(m){
          var el = document.createElement('div');
          el.className = 'mk ' + m.kind;
          if (m.label) el.title = m.label;
          el.addEventListener('click', function(){ post({ type: 'marker', id: m.id }); });
          var ov = new kk.CustomOverlay({ position: new kk.LatLng(m.lat, m.lng), content: el, xAnchor: 0.5, yAnchor: 0.5, clickable: true, zIndex: 3 });
          ov.setMap(map);
          return ov;
        });
      },
      line: function(l){
        if (line) { line.setMap(null); line = null; }
        if (l && l.points.length > 1) {
          line = new kk.Polyline({ path: l.points.map(function(p){ return new kk.LatLng(p[0], p[1]); }), strokeWeight: 5, strokeColor: l.color, strokeOpacity: 0.85 });
          line.setMap(map);
        }
      },
      fit: function(pts){
        if (!pts.length) return;
        var b = new kk.LatLngBounds();
        pts.forEach(function(p){ b.extend(new kk.LatLng(p[0], p[1])); });
        map.setBounds(b, 40, 40, 40, 40);
      }
    };
  }

  function apply(s){
    if (!M) { pending = s; return; }
    if (s.center) M.center(s.center);
    M.markers(s.markers || []);
    M.line(s.line || null);
    if (s.fit) {
      var pts = (s.markers || []).map(function(m){ return [m.lat, m.lng]; });
      if (s.line) pts = pts.concat(s.line.points);
      M.fit(pts);
    }
  }
  window.tago = { apply: apply };
  function onMessage(e){ try { var d = typeof e.data === 'string' ? JSON.parse(e.data) : e.data; if (d && d.markers) apply(d); } catch (_) {} }
  window.addEventListener('message', onMessage);
  document.addEventListener('message', onMessage);   // 안드로이드 WebView

  function start(engine){ M = engine; post({ type: 'ready', engine: M.name }); if (pending) { var p = pending; pending = null; apply(p); } }
  if (KAKAO) {
    var s = document.createElement('script');
    s.src = 'https://dapi.kakao.com/v2/maps/sdk.js?autoload=false&appkey=' + encodeURIComponent(KAKAO);
    s.onload = function(){ try { window.kakao.maps.load(function(){ try { start(kakao()); } catch (_) { start(leaflet()); } }); } catch (_) { start(leaflet()); } };
    s.onerror = function(){ start(leaflet()); };
    document.head.appendChild(s);
  } else start(leaflet());
})();
</script></body></html>`;
}
