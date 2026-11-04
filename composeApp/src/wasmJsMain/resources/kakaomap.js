// 웹 버전 카카오 지도 (폰 앱과 같은 지도). Compose 화면(캔버스) 위에 지도 칸만큼 div 를 띄워 카카오 지도를 그린다.
// 키는 서버 배포 때 config.js 가 window.TAGOHUB_KAKAO_JS_KEY 로 넣음 (카카오 JavaScript 키, 등록한 도메인에서만 동작).
// Kotlin(KakaoMap.kt)이 부르는 함수만: load / show / place / hide / view / fit / lines / dots / state
window.TagoKakao = (function () {
  let map = null, div = null, loaded = null;
  let lineObjs = [], dotObjs = [];
  let onDot = null, onIdle = null, onTap = null;

  function key() { return window.TAGOHUB_KAKAO_JS_KEY || ""; }

  // SDK 받기 (한 번). 결과: 1 성공, 0 실패
  function load(cb) {
    if (loaded !== null) { loaded.then(cb); return; }
    loaded = new Promise(function (res) {
      if (!key()) { res(0); return; }
      const s = document.createElement("script");
      s.src = "https://dapi.kakao.com/v2/maps/sdk.js?autoload=false&appkey=" + encodeURIComponent(key());
      s.onload = function () {
        try { kakao.maps.load(function () { res(1); }); } catch (e) { res(0); }
      };
      s.onerror = function () { res(0); };
      document.head.appendChild(s);
      setTimeout(function () { res(0); }, 15000);
    });
    loaded.then(cb);
  }

  function report() {
    if (!map || !onIdle) return;
    const c = map.getCenter(), b = map.getBounds(), sw = b.getSouthWest(), ne = b.getNorthEast();
    onIdle([c.getLat(), c.getLng(), map.getLevel(), sw.getLat(), sw.getLng(), ne.getLat(), ne.getLng()].join(","));
  }

  function show(x, y, w, h, lat, lng, level, dotCb, idleCb, tapCb) {
    onDot = dotCb; onIdle = idleCb; onTap = tapCb;
    if (!div) {
      div = document.createElement("div");
      div.style.cssText = "position:fixed;z-index:10;overflow:hidden;";
      document.body.appendChild(div);
    }
    place(x, y, w, h);
    div.style.display = "block";
    if (!map) {
      map = new kakao.maps.Map(div, { center: new kakao.maps.LatLng(lat, lng), level: level });
      map.addControl(new kakao.maps.ZoomControl(), kakao.maps.ControlPosition.RIGHT);
      kakao.maps.event.addListener(map, "idle", report);
      kakao.maps.event.addListener(map, "click", function () { if (onTap) onTap(); });
    } else {
      map.relayout();
      map.setCenter(new kakao.maps.LatLng(lat, lng));
      map.setLevel(level);
    }
    setTimeout(report, 0);
  }

  function place(x, y, w, h) {
    if (!div) return;
    div.style.left = x + "px"; div.style.top = y + "px";
    div.style.width = w + "px"; div.style.height = h + "px";
    if (map) map.relayout();
  }

  function hide() {
    if (div) div.style.display = "none";
    clear();
  }

  function clear() {
    lineObjs.forEach(function (o) { o.setMap(null); }); lineObjs = [];
    dotObjs.forEach(function (o) { o.setMap(null); }); dotObjs = [];
  }

  function view(lat, lng, level) {
    if (!map) return;
    map.setLevel(level);
    map.setCenter(new kakao.maps.LatLng(lat, lng));
  }

  // pts: [[위도, 경도], ...] JSON
  function fit(json) {
    if (!map) return;
    const pts = JSON.parse(json);
    if (!pts.length) return;
    const b = new kakao.maps.LatLngBounds();
    pts.forEach(function (p) { b.extend(new kakao.maps.LatLng(p[0], p[1])); });
    map.setBounds(b, 32, 32, 32, 32);
  }

  // [{pts:[[위도,경도]...], color:"#rrggbb", width:5}] JSON
  function lines(json) {
    if (!map) return;
    lineObjs.forEach(function (o) { o.setMap(null); }); lineObjs = [];
    JSON.parse(json).forEach(function (l) {
      const path = l.pts.map(function (p) { return new kakao.maps.LatLng(p[0], p[1]); });
      lineObjs.push(new kakao.maps.Polyline({ map: map, path: path, strokeWeight: l.width + 4, strokeColor: "#ffffff", strokeOpacity: 0.9 }));
      lineObjs.push(new kakao.maps.Polyline({ map: map, path: path, strokeWeight: l.width, strokeColor: l.color, strokeOpacity: 1 }));
    });
  }

  // [{id, lat, lng, color, ring, r, label}] JSON → 동그라미(누르면 onDot(id)) + 이름표
  function dots(json) {
    if (!map) return;
    dotObjs.forEach(function (o) { o.setMap(null); }); dotObjs = [];
    JSON.parse(json).forEach(function (d) {
      const el = document.createElement("div");
      const size = d.r * 2;
      el.style.cssText = "width:" + size + "px;height:" + size + "px;border-radius:50%;cursor:pointer;box-sizing:content-box;" +
        "background:" + d.color + ";border:2.5px solid " + d.ring + ";box-shadow:0 1px 2px rgba(0,0,0,.35);";
      el.onclick = function (e) { e.stopPropagation(); if (onDot) onDot(d.id); };
      let content = el;
      if (d.label) {
        const wrap = document.createElement("div");
        wrap.style.cssText = "position:relative;";
        const t = document.createElement("div");
        t.textContent = d.label;
        t.style.cssText = "position:absolute;left:" + (size + 8) + "px;top:-14px;white-space:nowrap;font:600 11px sans-serif;color:#fff;" +
          "background:" + d.color + ";border-radius:6px;padding:1px 5px;";
        wrap.appendChild(el); wrap.appendChild(t);
        content = wrap;
      }
      dotObjs.push(new kakao.maps.CustomOverlay({ map: map, position: new kakao.maps.LatLng(d.lat, d.lng), content: content, xAnchor: 0.5, yAnchor: 0.5, zIndex: d.label ? 3 : 2 }));
    });
  }

  return { load: load, show: show, place: place, hide: hide, view: view, fit: fit, lines: lines, dots: dots, hasKey: function () { return key() ? 1 : 0; } };
})();
