/* TagoHub 노선 모양 편집기
   지도에서 점을 찍거나, GPX·기존 노선 파일·배경 이미지·GPS 기록으로 노선 선형을 만들고
   고칠 때마다 브라우저(localStorage)에 저장한다. 내보내기는 앱의 bus_route_shapes.tsv 와 같은 형식. */
(() => {
  const $ = (s) => document.querySelector(s);
  const KEY = (id) => 'tagohub.shape.' + id;

  const S = {
    route: null, stops: [], pts: [], mode: 'draw', prevMode: 'draw',
    undo: [], sel: -1, savedAt: 0, corners: [], img: null, watch: null, imgLayer: null,
  };

  let map, line, stopLayer, ptLayer;

  /* ── 공통 ── */
  async function api(path, params) {
    const q = new URLSearchParams(params || {});
    const r = await fetch(path + (q.toString() ? '?' + q : ''));
    const j = await r.json().catch(() => ({ error: '서버 응답을 읽지 못했습니다.' }));
    if (!r.ok || j.error) throw new Error(j.error || r.statusText);
    return j;
  }

  function hint(msg, ms = 4000) {
    const el = $('#hint');
    el.textContent = msg || '';
    clearTimeout(hint.t);
    if (msg && ms) hint.t = setTimeout(() => { el.textContent = ''; }, ms);
  }

  function download(name, text, type = 'text/plain') {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([text], { type: type + ';charset=utf-8' }));
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  }

  const dist = (a, b) => {
    const R = 6371000, t = Math.PI / 180;
    const dLat = (b[0] - a[0]) * t, dLng = (b[1] - a[1]) * t;
    const x = Math.sin(dLat / 2) ** 2 + Math.cos(a[0] * t) * Math.cos(b[0] * t) * Math.sin(dLng / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(x));
  };

  const dotIcon = (sel) => L.divIcon({ className: '', html: `<i class="dot${sel ? ' sel' : ''}"></i>`, iconSize: [0, 0] });
  const stopIcon = (kind) => L.divIcon({ className: '', html: `<i class="stop ${kind}"></i>`, iconSize: [0, 0] });

  /* ── 지도 ── */
  function initMap() {
    map = L.map('map', { zoomControl: true, preferCanvas: true }).setView([37.5665, 126.978], 12);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19, attribution: '&copy; OpenStreetMap',
    }).addTo(map);
    stopLayer = L.layerGroup().addTo(map);
    ptLayer = L.layerGroup().addTo(map);
    line = L.polyline([], { color: '#2f81f7', weight: 5, opacity: 0.9 }).addTo(map);
    map.on('click', onMapClick);
  }

  function fit() {
    const pts = S.pts.concat(S.stops.map((s) => [s.lat, s.lng]));
    if (pts.length) map.fitBounds(L.latLngBounds(pts), { padding: [40, 40] });
  }

  /* ── 그리기 ── */
  function drawStops() {
    stopLayer.clearLayers();
    S.stops.forEach((s, i) => {
      const kind = i === 0 ? 'first' : i === S.stops.length - 1 ? 'last' : '';
      L.marker([s.lat, s.lng], { icon: stopIcon(kind), keyboard: false, interactive: true })
        .bindTooltip(`${i + 1}. ${s.name}`, { direction: 'top', offset: [0, -6] })
        .addTo(stopLayer);
    });
  }

  function draw() {
    line.setLatLngs(S.pts);
    ptLayer.clearLayers();
    S.pts.forEach((p, i) => {
      const m = L.marker(p, { icon: dotIcon(i === S.sel), draggable: true, keyboard: false });
      m.on('click', (e) => { if (e.originalEvent) L.DomEvent.stop(e.originalEvent); S.sel = i; draw(); });
      m.on('dragstart', () => { pushUndo(); S.sel = i; });
      m.on('drag', (e) => {
        const ll = e.target.getLatLng();
        S.pts[i] = [ll.lat, ll.lng];
        line.setLatLngs(S.pts);
      });
      m.on('dragend', () => { save(); draw(); });
      m.addTo(ptLayer);
    });
    updateStatus();
  }

  function updateStatus() {
    const t = S.savedAt ? new Date(S.savedAt).toTimeString().slice(0, 8) : '';
    $('#save').textContent = t ? `${S.pts.length}개 점 · 저장됨 ${t}` : `${S.pts.length}개 점 · 저장 안 됨`;
  }

  /* ── 저장·되돌리기 ── */
  function save() {
    if (!S.route) return;
    try { localStorage.setItem(KEY(S.route.id), JSON.stringify({ pts: S.pts, at: Date.now() })); } catch { /* 저장 공간이 없으면 넘어간다 */ }
    S.savedAt = Date.now();
    updateStatus();
  }

  function pushUndo() {
    S.undo.push(S.pts.map((p) => [p[0], p[1]]));
    if (S.undo.length > 80) S.undo.shift();
  }

  function undo() {
    if (!S.undo.length) return hint('되돌릴 작업이 없습니다.');
    S.pts = S.undo.pop();
    S.sel = -1;
    save();
    draw();
  }

  function deleteSelected() {
    if (S.sel < 0) return hint('지도에서 점을 먼저 누르세요.');
    pushUndo();
    S.pts.splice(S.sel, 1);
    S.sel = -1;
    save();
    draw();
  }

  /* ── 입력 ── */
  function onMapClick(e) {
    if (S.mode === 'image') { addCorner(e.latlng); return; }
    if (S.mode !== 'draw') return;
    pushUndo();
    S.pts.push([e.latlng.lat, e.latlng.lng]);
    S.sel = S.pts.length - 1;
    save();
    draw();
  }

  /* ── 노선 조회 ── */
  async function findRoute() {
    const no = $('#q').value.trim();
    if (!no) return hint('노선 번호를 넣으세요. (예: 721)');
    hint('노선을 찾는 중…', 0);
    try {
      const r = await api('/api/bus/routes', { city: 'seoul', no });
      if (!r.routes || !r.routes.length) return hint('그 번호의 노선이 없습니다.');
      if (r.routes.length > 1) hint(`같은 번호 노선 ${r.routes.length}개 중 첫 번째를 엽니다.`);
      await loadRoute(r.routes[0].routeId);
    } catch (e) { hint(e.message); }
  }

  async function loadRoute(id) {
    hint('노선 정보를 불러오는 중…', 0);
    try {
      const d = await api('/api/bus/route', { city: 'seoul', route: id });
      S.route = { id, no: d.info.routeNo, start: d.info.start, end: d.info.end };
      S.stops = (d.stops || []).filter((s) => s.lat && s.lng);
      S.pts = []; S.undo = []; S.sel = -1;
      let saved = null;
      try { saved = JSON.parse(localStorage.getItem(KEY(id)) || 'null'); } catch { saved = null; }
      if (saved && Array.isArray(saved.pts)) { S.pts = saved.pts; S.savedAt = saved.at || 0; }
      $('#name').textContent = `${S.route.no} · ${S.route.start || '?'} ↔ ${S.route.end || '?'} · 정류장 ${S.stops.length}`;
      document.title = `TagoHub 노선 편집기 · ${S.route.no}`;
      drawStops();
      draw();
      fit();
      hint(saved ? '저장해 둔 노선을 불러왔습니다. 점을 눌러 옮기거나 새로 찍으세요.' : '점 추가 상태입니다. 지도를 누르면 노선 점이 붙습니다.');
    } catch (e) { hint(e.message); }
  }

  /* ── 파일 가져오기 ── */
  async function importGpx(file) {
    const text = await file.text();
    const doc = new DOMParser().parseFromString(text, 'application/xml');
    if (doc.querySelector('parsererror')) return hint('GPX 를 읽지 못했습니다.');
    const nodes = [...doc.querySelectorAll('trkpt, rtept, wpt')];
    const pts = nodes
      .map((n) => [parseFloat(n.getAttribute('lat')), parseFloat(n.getAttribute('lon'))])
      .filter((p) => Number.isFinite(p[0]) && Number.isFinite(p[1]));
    if (!pts.length) return hint('GPX 안에 좌표가 없습니다.');
    pushUndo();
    S.pts = pts;
    S.sel = -1;
    save();
    draw();
    fit();
    hint(`GPX 점 ${pts.length}개를 불러왔습니다.`);
  }

  async function importShape(file) {
    const pts = ShapeFormat.parseShapeText(await file.text(), S.route && S.route.id);
    if (!pts || !pts.length) return hint('노선 파일에서 좌표를 찾지 못했습니다.');
    pushUndo();
    S.pts = pts;
    S.sel = -1;
    save();
    draw();
    fit();
    hint(`노선 점 ${pts.length}개를 불러왔습니다.`);
  }

  /* ── 배경 이미지 ── */
  function loadImage(file) {
    if (S.imgLayer) { map.removeLayer(S.imgLayer); S.imgLayer = null; }
    S.img = URL.createObjectURL(file);
    S.corners = [];
    S.prevMode = S.mode === 'image' ? 'draw' : S.mode;
    setMode('image');
    hint('지도에서 이미지의 왼쪽 위와 오른쪽 아래를 차례로 누르세요.');
  }

  function addCorner(latlng) {
    S.corners.push(latlng);
    if (S.corners.length < 2) return hint('오른쪽 아래 모서리를 누르세요.');
    const b = L.latLngBounds(S.corners[0], S.corners[1]);
    S.imgLayer = L.imageOverlay(S.img, b, { opacity: $('#alpha').value / 100 }).addTo(map);
    S.corners = [];
    $('#alphaWrap').classList.add('show');
    setMode(S.prevMode);
    hint('배경 이미지를 놓았습니다. 투명도를 조절하며 위에 점을 찍으세요.');
  }

  /* ── GPS ── */
  function toggleGps() {
    const btn = $('#gps');
    if (S.watch != null) {
      navigator.geolocation.clearWatch(S.watch);
      S.watch = null;
      btn.classList.remove('on');
      btn.textContent = 'GPS 기록';
      hint('GPS 기록을 멈췄습니다.');
      return;
    }
    if (!navigator.geolocation) return hint('이 브라우저는 위치를 지원하지 않습니다.');
    S.watch = navigator.geolocation.watchPosition(onPos, (e) => hint('위치 오류: ' + e.message), {
      enableHighAccuracy: true, maximumAge: 2000, timeout: 20000,
    });
    btn.classList.add('on');
    btn.textContent = 'GPS 기록 중';
    hint('지금부터 지나가는 길이 노선 점으로 붙습니다. 8m 이상 움직일 때만 추가합니다.');
  }

  function onPos(pos) {
    const p = [pos.coords.latitude, pos.coords.longitude];
    const last = S.pts[S.pts.length - 1];
    if (last && dist(last, p) < 8) return;
    S.pts.push(p);
    save();
    draw();
  }

  /* ── 내보내기 ── */
  function exportTsv() {
    if (!S.route || !S.pts.length) return hint('노선을 조회하고 점을 찍은 뒤에 저장할 수 있습니다.');
    const id = S.route.id.replace(/^SEB/, '');
    download(`${id}.tsv`, ShapeFormat.toTsv(S.route.id, S.pts) + '\n');
    hint('bus_route_shapes.tsv 에 넣을 한 줄을 내려받았습니다.');
  }

  function exportGpx() {
    if (!S.pts.length) return hint('먼저 점을 찍으세요.');
    const name = S.route ? `${S.route.no} ${S.route.start || ''}↔${S.route.end || ''}` : 'route';
    const pts = S.pts.map((p) => `      <trkpt lat="${p[0].toFixed(6)}" lon="${p[1].toFixed(6)}"/>`).join('\n');
    const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="TagoHub editor" xmlns="http://www.topografix.com/GPX/1/1">\n  <trk><name>${name}</name><trkseg>\n${pts}\n  </trkseg></trk>\n</gpx>\n`;
    download(`${S.route ? S.route.id : 'route'}.gpx`, xml, 'application/gpx+xml');
  }

  /* ── 모드·버튼 ── */
  function setMode(m) {
    S.mode = m;
    document.querySelectorAll('[data-mode]').forEach((b) => b.classList.toggle('on', b.dataset.mode === m));
    map.getContainer().style.cursor = m === 'draw' || m === 'image' ? 'crosshair' : 'grab';
  }

  function draftFromStops() {
    if (!S.stops.length) return hint('먼저 노선을 조회하세요.');
    pushUndo();
    const pts = [];
    for (const s of S.stops) {
      const p = [s.lat, s.lng];
      if (!pts.length || dist(pts[pts.length - 1], p) > 5) pts.push(p);
    }
    S.pts = pts;
    save();
    draw();
    fit();
    hint('정류장을 순서대로 이은 초안입니다. 도로를 따라 점을 옮기거나 새로 찍으세요.');
  }

  function bind() {
    $('#find').addEventListener('click', findRoute);
    $('#q').addEventListener('keydown', (e) => { if (e.key === 'Enter') findRoute(); });
    document.querySelectorAll('[data-mode]').forEach((b) => b.addEventListener('click', () => setMode(b.dataset.mode)));
    $('#undo').addEventListener('click', undo);
    $('#del').addEventListener('click', deleteSelected);
    $('#clear').addEventListener('click', () => {
      if (!S.pts.length) return;
      if (!confirm('이 노선의 점을 모두 지울까요? 되돌리기로 복구할 수 있습니다.')) return;
      pushUndo(); S.pts = []; S.sel = -1; save(); draw();
    });
    $('#draft').addEventListener('click', draftFromStops);
    $('#gps').addEventListener('click', toggleGps);
    $('#tsvSave').addEventListener('click', exportTsv);
    $('#gpxSave').addEventListener('click', exportGpx);
    $('#gpx').addEventListener('change', (e) => { const f = e.target.files[0]; if (f) importGpx(f); e.target.value = ''; });
    $('#tsv').addEventListener('change', (e) => { const f = e.target.files[0]; if (f) importShape(f); e.target.value = ''; });
    $('#img').addEventListener('change', (e) => { const f = e.target.files[0]; if (f) loadImage(f); e.target.value = ''; });
    $('#alpha').addEventListener('input', (e) => { if (S.imgLayer) S.imgLayer.setOpacity(e.target.value / 100); });
    document.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); undo(); }
      else if (e.key === 'Delete' || e.key === 'Backspace') {
        const tag = (document.activeElement && document.activeElement.tagName) || '';
        if (tag !== 'INPUT' && tag !== 'TEXTAREA') { e.preventDefault(); deleteSelected(); }
      }
    });
  }

  initMap();
  bind();
  setMode('draw');
})();
