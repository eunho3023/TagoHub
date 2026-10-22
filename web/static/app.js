'use strict';
/* TagoHub 웹 — 작은 화면 전환기. 서버(server.py)가 키를 붙여 공공 API 를 대신 부른다. */

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

async function api(path, params) {
  const q = new URLSearchParams(params || {});
  const r = await fetch(path + (q.toString() ? '?' + q : ''));
  const j = await r.json().catch(() => ({ error: '서버 응답을 읽지 못했습니다.' }));
  if (!r.ok || j.error) throw new Error(j.error || r.statusText);
  return j;
}

/* ───────── 아이콘 (시안과 같은 선 아이콘) ───────── */
const ICON = {
  map: 'M14.1 5.6a2 2 0 0 0 1.8 0l3.7-1.8A1 1 0 0 1 21 4.6v12.8a1 1 0 0 1-.6.9l-4.5 2.3a2 2 0 0 1-1.8 0l-4.2-2.1a2 2 0 0 0-1.8 0l-3.7 1.8A1 1 0 0 1 3 19.4V6.6a1 1 0 0 1 .6-.9l4.5-2.3a2 2 0 0 1 1.8 0zM15 5.8v15M9 3.2v15',
  bus: 'M8 6v6M16 6v6M2 12h19.6M18 18h3s.5-1.7.8-2.8c.1-.4.2-.8.2-1.2 0-.4-.1-.8-.2-1.2l-1.4-5C20.1 6.8 19.1 6 18 6H4a2 2 0 0 0-2 2v10h3M9 18h5',
  subway: 'M4 11V5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v6M4 11h16v6a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2zM8 15h.01M16 15h.01M8 19l-2 3M16 19l2 3',
  train: 'M8 3.1V7a4 4 0 0 0 8 0V3.1M9 15l-1-1M15 15l1-1M9 19c-2.8 0-5-2.2-5-5v-4a8 8 0 0 1 16 0v4c0 2.8-2.2 5-5 5zM8 19l-2 3M16 19l2 3',
  coach: 'M3 17h18M5 17V8a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v9M5 11h14M8 20v-3M16 20v-3',
  plane: 'M17.8 19.2 16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2l-1.6 1.6 6.4 3.4-3.1 3.1-2.9-.4L2.5 15.5l3.8 1.2 1.2 3.8 1.5-1.1-.4-2.9 3.1-3.1 3.4 6.4z',
  more: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z',
  search: 'M3 11a8 8 0 1 0 16 0a8 8 0 1 0-16 0M21 21l-4.3-4.3',
  route: 'M6 19a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM9 16h6a3 3 0 0 0 0-6H9a3 3 0 0 1 0-6h3',
};
const svg = (name) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${ICON[name]}"/></svg>`;

/* ───────── 색 ───────── */
function routeColor(type) {
  const t = type || '';
  if (/간선/.test(t)) return '#3D5BAB';
  if (/지선/.test(t)) return '#3E9A2E';
  if (/광역|급행|직행|좌석/.test(t)) return '#D7262E';
  if (/마을/.test(t)) return '#7FA92A';
  if (/순환/.test(t)) return '#C9940A';
  if (/심야|올빼미/.test(t)) return '#2B2B5E';
  if (/농어촌/.test(t)) return '#E5622C';
  if (/공항/.test(t)) return '#0090D2';
  if (/일반/.test(t)) return '#14A0A8';
  return '#607080';
}
const LINE_COLORS = { '1호선': '#0052A4', '2호선': '#00A84D', '3호선': '#EF7C1C', '4호선': '#00A5DE', '5호선': '#996CAC', '6호선': '#CD7C2F', '7호선': '#747F00', '8호선': '#E6186C', '9호선': '#BDB092',
  '공항철도': '#0090D2', '경의중앙선': '#5FB59A', '수인분당선': '#E0A800', '신분당선': '#D4003B', '경춘선': '#0C8E72', '우이신설선': '#9DB516' };
const lineColor = (l) => LINE_COLORS[l] || (Object.entries(LINE_COLORS).find(([k]) => (l || '').includes(k.replace('선', '')))?.[1]) || '#4F5B6B';
const lineLabel = (l) => (/^(\d+)호선/.exec(l || '') || [])[1] || (l || '').replace('선', '').slice(0, 2);
const lineBadge = (l, big) => `<span class="dot" style="background:${lineColor(l)};${big ? '' : 'width:28px;height:28px;font-size:12px'}">${esc(lineLabel(l))}</span>`;

/* ───────── 시간·글 ───────── */
function etaText(sec) {
  if (sec == null) return '정보없음';
  if (sec <= 30) return '곧 도착';
  const m = Math.floor(sec / 60), s = sec % 60;
  return m ? `${m}분 ${String(s).padStart(2, '0')}초` : `${s}초`;
}
const hhmm = (s) => (s && s.length >= 12 ? `${s.slice(8, 10)}:${s.slice(10, 12)}` : '--:--');
function durText(a, b) {
  if (!a || !b || a.length < 12 || b.length < 12) return '';
  const t = (s) => new Date(+s.slice(0, 4), +s.slice(4, 6) - 1, +s.slice(6, 8), +s.slice(8, 10), +s.slice(10, 12)).getTime();
  const m = Math.round((t(b) - t(a)) / 60000);
  return m >= 60 ? `${Math.floor(m / 60)}시간${m % 60 ? ' ' + (m % 60) + '분' : ''}` : `${m}분`;
}
const won = (n) => (n ? n.toLocaleString('ko-KR') + '원' : '요금 정보 없음');
const km = (m) => (m >= 1000 ? (m / 1000).toFixed(1) + 'km' : Math.round(m) + 'm');
const mins = (s) => { const m = Math.round(s / 60); return m >= 60 ? `${Math.floor(m / 60)}시간${m % 60 ? ' ' + (m % 60) + '분' : ''}` : `${m}분`; };
const today = () => new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10);
function toast(msg) { const t = $('#toast'); t.textContent = msg; t.classList.add('show'); clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('show'), 2400); }

/* ───────── 화면 모드 ───────── */
function applyTheme() {
  const pref = localStorage.getItem('theme') || 'system';
  const dark = pref === 'dark' || (pref === 'system' && matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
}
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);
applyTheme();

/* ───────── 지도 ───────── */
const map = L.map('map', { zoomControl: true }).setView([36.35, 127.8], 7);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(map);
const layers = { stops: L.layerGroup().addTo(map), route: L.layerGroup().addTo(map), buses: L.layerGroup().addTo(map), pin: L.layerGroup().addTo(map), me: L.layerGroup().addTo(map) };
const mkIcon = (cls, html = '') => L.divIcon({ className: '', html: `<div class="mk ${cls}">${html}</div>`, iconSize: [22, 22], iconAnchor: [11, 11] });
const clearLayer = (n) => layers[n].clearLayers();

/* ───────── 상태 ───────── */
const S = { nav: [], timers: [], here: null, favs: JSON.parse(localStorage.getItem('favs') || '[]'), plan: { o: null, d: null, mode: 'transit', option: 'recommend' } };
const saveFavs = () => localStorage.setItem('favs', JSON.stringify(S.favs));
const isFav = (id) => S.favs.some((f) => f.id === id);
function toggleFav(stop) {
  S.favs = isFav(stop.id) ? S.favs.filter((f) => f.id !== stop.id) : [{ id: stop.id, name: stop.name, city: stop.city, no: stop.no, lat: stop.lat, lng: stop.lng }, ...S.favs];
  saveFavs();
}
function every(ms, fn) { S.timers.push(setInterval(fn, ms)); }
function clearTimers() { S.timers.forEach(clearInterval); S.timers = []; }
const screen = $('#screen');

/* ───────── 이동 ───────── */
const TABS = [['home', '지도', 'map'], ['bus', '버스', 'bus'], ['subway', '지하철', 'subway'], ['train', '기차', 'train'], ['more', '더보기', 'more']];
function renderTabs(active) {
  $('#tabs').innerHTML = TABS.map(([k, l, i]) => `<button data-tab="${k}" class="${k === active ? 'on' : ''}">${svg(i)}${l}</button>`).join('');
  $$('#tabs button').forEach((b) => b.addEventListener('click', () => { S.nav = []; go(b.dataset.tab, null, false); }));
}
function go(view, params, push = true) {
  clearTimers();
  if (push) S.nav.push({ view, params });
  S.cur = { view, params };
  const tab = { home: 'home', search: 'home', place: 'home', nearby: 'home', plan: 'home', bus: 'bus', stop: 'bus', route: 'bus', subway: 'subway', station: 'subway', train: 'train', air: 'more', trips: S.curKind === 'air' ? 'more' : 'train', more: 'more' }[view] || 'home';
  renderTabs(view === 'trips' ? (params?.kind === 'air' ? 'more' : 'train') : tab);
  screen.scrollTop = 0;
  (VIEWS[view] || VIEWS.home)(params);
}
function back() {
  S.nav.pop();
  const prev = S.nav[S.nav.length - 1];
  if (prev) go(prev.view, prev.params, false); else go('home', null, false);
}
const backBtn = () => (S.nav.length > 1 ? '<button class="back" id="back">‹ 뒤로</button>' : '');
const bindBack = () => $('#back')?.addEventListener('click', back);
const loading = (msg = '불러오는 중…') => `<div class="spin">${esc(msg)}</div>`;
const errBox = (e) => `<div class="note err">${esc(e.message || e)}</div>`;

function locate() {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) return reject(new Error('이 브라우저는 위치를 지원하지 않습니다.'));
    navigator.geolocation.getCurrentPosition(
      (p) => {
        S.here = { lat: p.coords.latitude, lng: p.coords.longitude };
        clearLayer('me');
        L.marker([S.here.lat, S.here.lng], { icon: mkIcon('me') }).addTo(layers.me);
        resolve(S.here);
      },
      () => reject(new Error('위치를 알 수 없습니다. 브라우저의 위치 권한을 허용하세요.')),
      { enableHighAccuracy: true, timeout: 10000 },
    );
  });
}

/* ───────── 한 줄 목록 만들기 ───────── */
const stopItem = (s) => `<button class="item" data-stop='${esc(JSON.stringify(s))}'><span class="badge" style="background:var(--c-bus);min-width:40px">${svg('bus').replace('<svg', '<svg width="18" height="18"')}</span>
  <span class="grow"><div class="name">${esc(s.name)}</div><div class="sm">${esc([s.no, s.region, s.distance != null ? s.distance + 'm' : ''].filter(Boolean).join(' · '))}</div></span></button>`;
function bindStops(root = screen) {
  $$('[data-stop]', root).forEach((b) => b.addEventListener('click', () => go('stop', JSON.parse(b.dataset.stop))));
}
function showStopsOnMap(stops) {
  clearLayer('stops');
  stops.forEach((s) => L.marker([s.lat, s.lng], { icon: mkIcon('stop') }).on('click', () => go('stop', s)).bindTooltip(s.name).addTo(layers.stops));
  if (stops.length) map.fitBounds(L.latLngBounds(stops.map((s) => [s.lat, s.lng])), { padding: [40, 40], maxZoom: 17 });
}

/* ───────── 화면들 ───────── */
const VIEWS = {};

VIEWS.home = async function () {
  const tile = (label, icon, color, view, off) => `<button class="tile ${off ? 'off' : ''}" data-view="${view}" style="background:${color}">${svg(icon)}<b>${label}</b></button>`;
  screen.innerHTML = `<div class="pad" style="padding-top:28px">
    <h1 class="h1">어디로<br>가시나요?</h1>
    <form id="q" class="search" style="margin-top:14px">${svg('search')}<input id="qi" placeholder="장소 · 정류소 · 역 검색" aria-label="검색" autocomplete="off"></form>
    <div class="tiles">
      ${tile('지도', 'map', 'var(--c-map)', 'nearby')}${tile('버스', 'bus', 'var(--c-bus)', 'bus')}
      ${tile('지하철', 'subway', 'var(--c-subway)', 'subway')}${tile('기차', 'train', 'var(--c-train)', 'train')}
      ${tile('고속·시외', 'coach', 'var(--c-coach)', 'coach', true)}${tile('항공', 'plane', 'var(--c-air)', 'air')}
    </div>
    <button class="btn line" id="plan" style="width:100%;margin-top:10px">${svg('route').replace('<svg', '<svg width="18" height="18" style="vertical-align:-3px;margin-right:6px"')}길찾기</button>
    <div class="label">자주 가는 곳</div><div id="favs" class="list" style="margin:0 -16px"></div></div>`;
  $('#q').addEventListener('submit', (e) => { e.preventDefault(); const q = $('#qi').value.trim(); if (q) go('search', q); });
  $$('.tile').forEach((b) => b.addEventListener('click', () => (b.dataset.view === 'coach' ? toast('고속·시외버스는 웹 버전에서 아직 준비 중이에요') : go(b.dataset.view))));
  $('#plan').addEventListener('click', () => go('plan'));
  const box = $('#favs');
  if (!S.favs.length) { box.innerHTML = '<div class="sub" style="padding:8px 16px">정류장의 ★ 를 누르면 여기에 모여요.</div>'; return; }
  box.innerHTML = S.favs.slice(0, 5).map((f) => `<button class="item" data-fav="${esc(f.id)}"><span class="badge" data-b style="background:var(--c-bus)">${svg('bus').replace('<svg', '<svg width="18" height="18"')}</span>
    <span class="grow"><div class="name">${esc(f.name)}</div><div class="sm">${esc(f.no || '')}</div></span><span class="eta num" data-e></span></button>`).join('');
  $$('[data-fav]', box).forEach((b) => {
    const f = S.favs.find((x) => x.id === b.dataset.fav);
    b.addEventListener('click', () => go('stop', f));
    api('/api/bus/arrivals', { city: f.city, node: f.id }).then((r) => {
      const a = (r.arrivals || [])[0];
      if (!a) return;
      const badge = $('[data-b]', b); badge.style.background = routeColor(a.routeType); badge.textContent = a.routeNo;
      $('[data-e]', b).textContent = a.sec == null ? '' : a.sec <= 30 ? '곧' : Math.ceil(a.sec / 60) + '분';
    }).catch(() => {});
  });
};

VIEWS.search = async function (q) {
  screen.innerHTML = backBtn() + `<div class="pad"><h1 class="h1" style="font-size:20px;margin-top:8px">‘${esc(q)}’ 검색</h1></div>${loading()}`;
  bindBack();
  const [stops, stations, places] = await Promise.allSettled([api('/api/stops/search', { q }), api('/api/subway/search', { q }), api('/api/place', { q, ...(S.here || {}) })]);
  let html = backBtn() + `<div class="pad"><h1 class="h1" style="font-size:20px;margin-top:8px">‘${esc(q)}’ 검색</h1></div>`;
  const grp = (title, list, row) => (list.length ? `<div class="pad"><div class="label">${title}</div></div><div class="list">${list.map(row).join('')}</div>` : '');
  const pl = places.status === 'fulfilled' ? places.value.places : [];
  const st = stations.status === 'fulfilled' ? stations.value.stations : [];
  html += grp('지하철역', st.slice(0, 5), (s) => `<button class="item" data-st='${esc(JSON.stringify(s))}'><span class="grow"><div class="name">${esc(s.name)}</div><div class="legs">${s.lines.map((l) => lineBadge(l)).join('')}</div></span></button>`);
  const sl = stops.status === 'fulfilled' ? stops.value.stops : [];
  html += grp('정류장', sl.slice(0, 6), stopItem);
  html += grp('장소', pl.slice(0, 10), (p) => `<button class="item" data-place='${esc(JSON.stringify(p))}'><span class="grow"><div class="name">${esc(p.name)}</div><div class="sm">${esc([p.category, p.address].filter(Boolean).join(' · '))}</div></span></button>`);
  if (!pl.length && !sl.length && !st.length) html += '<div class="note">검색 결과가 없습니다.</div>';
  if (places.status === 'rejected') html += `<div class="note err">장소 검색: ${esc(places.reason.message)}</div>`;
  screen.innerHTML = html;
  bindBack(); bindStops();
  $$('[data-place]').forEach((b) => b.addEventListener('click', () => go('place', JSON.parse(b.dataset.place))));
  $$('[data-st]').forEach((b) => b.addEventListener('click', () => go('station', JSON.parse(b.dataset.st))));
};

VIEWS.place = async function (p) {
  clearLayer('pin');
  L.marker([p.lat, p.lng], { icon: mkIcon('pin') }).addTo(layers.pin);
  map.setView([p.lat, p.lng], 16);
  screen.innerHTML = backBtn() + `<div class="pad" style="padding-top:8px"><h1 class="h1" style="font-size:22px">${esc(p.name)}</h1><div class="sub">${esc(p.address || '')}${p.phone ? ' · ' + esc(p.phone) : ''}</div>
    <div style="display:flex;gap:8px;margin-top:14px"><button class="btn line" id="dep">여기서 출발</button><button class="btn" id="arr">여기로 도착</button></div>
    <div class="label">가까운 정류장</div></div><div id="near">${loading()}</div>`;
  bindBack();
  $('#dep').addEventListener('click', () => { S.plan.o = { name: p.name, lat: p.lat, lng: p.lng }; go('plan'); });
  $('#arr').addEventListener('click', () => { S.plan.d = { name: p.name, lat: p.lat, lng: p.lng }; go('plan'); });
  try {
    const r = await api('/api/stops/near', { lat: p.lat, lng: p.lng, r: 500 });
    $('#near').innerHTML = r.stops.length ? `<div class="list">${r.stops.slice(0, 12).map(stopItem).join('')}</div>` : '<div class="note">500m 안에 정류장이 없습니다.</div>';
    bindStops($('#near'));
    showStopsOnMap(r.stops.slice(0, 30));
    L.marker([p.lat, p.lng], { icon: mkIcon('pin') }).addTo(layers.pin);
  } catch (e) { $('#near').innerHTML = errBox(e); }
};

VIEWS.nearby = async function () {
  screen.innerHTML = backBtn() + `<div class="pad" style="padding-top:8px"><h1 class="h1" style="font-size:22px">내 주변 정류장</h1></div><div id="near">${loading('위치를 확인하는 중…')}</div>`;
  bindBack();
  try {
    const here = await locate();
    map.setView([here.lat, here.lng], 16);
    const r = await api('/api/stops/near', { lat: here.lat, lng: here.lng, r: 500 });
    $('#near').innerHTML = r.stops.length ? `<div class="list">${r.stops.map(stopItem).join('')}</div>` : '<div class="note">500m 안에 정류장이 없습니다.</div>';
    bindStops($('#near'));
    showStopsOnMap(r.stops);
  } catch (e) { $('#near').innerHTML = errBox(e) + '<div class="note">장소를 검색해서 시작할 수도 있어요. 첫 화면의 검색창을 쓰세요.</div>'; }
};

VIEWS.bus = function () {
  screen.innerHTML = `<div class="band" style="background:var(--c-bus)"><div class="row"><div class="title">버스</div></div><div class="meta">정류소 이름·번호로 검색하세요</div></div>
    <div class="pad" style="padding-top:14px"><form id="f" class="search">${svg('search')}<input id="qi" placeholder="정류소명 · 정류소번호" aria-label="정류소 검색" autocomplete="off"></form>
    <div style="margin-top:10px"><button class="btn line" id="here">내 주변 정류장</button></div></div><div id="res"></div>
    <div class="pad"><div class="label">즐겨찾기</div></div><div class="list" id="favs"></div>`;
  $('#here').addEventListener('click', () => go('nearby'));
  $('#f').addEventListener('submit', async (e) => {
    e.preventDefault();
    const q = $('#qi').value.trim(); if (!q) return;
    $('#res').innerHTML = loading();
    try { const r = await api('/api/stops/search', { q }); $('#res').innerHTML = r.stops.length ? `<div class="list">${r.stops.map(stopItem).join('')}</div>` : '<div class="note">검색 결과가 없습니다.</div>'; bindStops($('#res')); showStopsOnMap(r.stops.slice(0, 40)); }
    catch (er) { $('#res').innerHTML = errBox(er); }
  });
  $('#favs').innerHTML = S.favs.length ? S.favs.map((f) => stopItem(f)).join('') : '<div class="sub" style="padding:8px 16px">★ 를 누른 정류장이 없습니다.</div>';
  bindStops($('#favs'));
};

VIEWS.stop = function (s) {
  const color = 'var(--c-bus)';
  screen.innerHTML = backBtn() + `<div class="band" style="background:${color}"><div class="row"><div class="title" style="font-size:24px">${esc(s.name)}</div>
    <button id="fav" aria-label="즐겨찾기">${isFav(s.id) ? '★' : '☆'}</button><button id="rf" aria-label="새로고침">↻</button></div><div class="meta">${esc([s.no, s.region].filter(Boolean).join(' · '))} <span id="upd"></span></div></div>
    <div id="note"></div><div id="rows" class="list">${loading()}</div>`;
  bindBack();
  clearLayer('pin');
  if (s.lat) { L.marker([s.lat, s.lng], { icon: mkIcon('stop') }).addTo(layers.pin); map.setView([s.lat, s.lng], 17); }
  $('#fav').addEventListener('click', () => { toggleFav(s); $('#fav').textContent = isFav(s.id) ? '★' : '☆'; });
  let data = null, got = 0, routes = [];
  const draw = () => {
    if (!data) return;
    const el = (Date.now() - got) / 1000;
    const known = new Map(routes.map((r) => [r.routeId, r]));
    const rows = data.arrivals.map((a) => ({ ...a, info: known.get(a.routeId) }));
    const withArr = new Set(rows.map((r) => r.routeId));
    routes.filter((r) => !withArr.has(r.routeId)).forEach((r) => rows.push({ routeId: r.routeId, routeNo: r.routeNo, routeType: r.routeType, sec: null, info: r }));
    $('#rows').innerHTML = rows.length ? rows.map((a) => {
      const sec = a.sec == null ? null : Math.max(0, Math.round(a.sec - el));
      const ends = a.info ? [a.info.start, a.info.end].filter(Boolean).join(' → ') : '';
      return `<button class="item" data-route="${esc(a.routeId)}"><span class="badge" style="background:${routeColor(a.routeType)}">${esc(a.routeNo)}</span>
        <span class="grow"><div class="name">${esc(ends || a.routeNo)}</div><div class="sm">${esc([a.routeType, a.vehicleType].filter(Boolean).join(' · '))}</div></span>
        <span class="eta num ${sec != null && sec <= 30 ? 'soon' : ''}">${esc(etaText(sec))}<small>${a.prevStops != null ? a.prevStops + '정류장 전' : ''}</small></span></button>`;
    }).join('') : '<div class="note">이 정류장의 버스 정보가 없습니다.</div>';
    $$('[data-route]').forEach((b) => b.addEventListener('click', () => go('route', { city: s.city, route: b.dataset.route, focus: s.id })));
  };
  const load = async () => {
    $('#rf').disabled = true;
    try {
      const [a, r] = await Promise.all([api('/api/bus/arrivals', { city: s.city, node: s.id }), api('/api/bus/stoproutes', { city: s.city, node: s.id }).catch(() => ({ routes: [] }))]);
      if (a.supported === false) { $('#note').innerHTML = `<div class="note">${esc(a.message)}</div>`; $('#rows').innerHTML = ''; return; }
      data = a; got = Date.now(); routes = r.routes; $('#note').innerHTML = ''; $('#upd').textContent = '· ' + new Date().toTimeString().slice(0, 8) + ' 갱신';
      draw();
    } catch (e) { $('#note').innerHTML = errBox(e); if (!data) $('#rows').innerHTML = ''; }
    $('#rf').disabled = false;
  };
  $('#rf').addEventListener('click', load);
  load(); every(15000, load); every(1000, draw);
};

VIEWS.route = async function (p) {
  screen.innerHTML = backBtn() + loading('노선 정보를 불러오는 중…');
  bindBack();
  const load = async (first) => {
    let r;
    try { r = await api('/api/bus/route', { city: p.city, route: p.route }); }
    catch (e) { if (first) screen.innerHTML = backBtn() + errBox(e); bindBack(); return; }
    const i = r.info, color = routeColor(i.routeType);
    const atNode = new Map();
    r.buses.forEach((b) => atNode.set(b.ord ?? b.nodeId, b));
    const ends = [i.start, i.end].filter(Boolean).join(' → ');
    screen.innerHTML = backBtn() + `<div class="band" style="background:${color}"><div class="row"><div class="title num" style="font-size:34px">${esc(i.routeNo)}</div>${i.routeType ? `<span class="pill">${esc(String(i.routeType).replace('버스', ''))}</span>` : ''}<span style="flex:1"></span><button id="rf" aria-label="새로고침">↻</button></div>
      <div class="meta">${esc(ends)}</div><div class="meta">${esc([i.interval ? '배차 ' + i.interval + '분' : '', '운행 중 ' + r.buses.length + '대'].filter(Boolean).join(' · '))}</div></div>
      <div class="tl" style="--rl:${color}">${r.stops.map((s) => {
        const bus = r.buses.find((b) => (b.ord != null && b.ord === s.ord) || b.nodeId === s.id);
        return `<div class="st ${bus ? 'bus' : ''} ${s.id === p.focus ? 'focus' : ''}"><span class="rail"></span><span class="pt"><i>${bus ? '🚌' : ''}</i></span>
          <span><div class="nm">${esc(s.name)}</div><div class="s2">${esc(bus ? '버스 위치 · ' + (bus.vehicleNo || '운행 중') : s.no)}</div></span></div>`;
      }).join('')}</div>`;
    bindBack();
    $('#rf').addEventListener('click', () => load(false));
    clearLayer('route'); clearLayer('buses');
    const pts = r.stops.filter((s) => s.lat).map((s) => [s.lat, s.lng]);
    if (pts.length > 1) { L.polyline(pts, { color, weight: 5, opacity: .85 }).addTo(layers.route); if (first) map.fitBounds(pts, { padding: [40, 40] }); }
    r.buses.filter((b) => b.lat).forEach((b) => L.marker([b.lat, b.lng], { icon: mkIcon('bus') }).bindTooltip(b.vehicleNo || '버스').addTo(layers.buses));
    const f = $('.st.focus'); if (f && first) f.scrollIntoView({ block: 'center' });
  };
  await load(true);
  every(10000, () => load(false));
};

VIEWS.subway = function () {
  screen.innerHTML = `<div class="band" style="background:var(--c-subway)"><div class="row"><div class="title">지하철</div></div><div class="meta">역 이름으로 검색하세요 (실시간 도착은 서울·수도권)</div></div>
    <div class="pad" style="padding-top:14px"><form id="f" class="search">${svg('search')}<input id="qi" placeholder="역 이름" aria-label="역 검색" autocomplete="off"></form></div><div id="res"></div>`;
  $('#f').addEventListener('submit', async (e) => {
    e.preventDefault();
    const q = $('#qi').value.trim(); if (!q) return;
    try {
      const r = await api('/api/subway/search', { q });
      $('#res').innerHTML = r.stations.length ? `<div class="list">${r.stations.map((s) => `<button class="item" data-st='${esc(JSON.stringify(s))}'><span class="grow"><div class="name">${esc(s.name)}</div><div class="legs">${s.lines.map((l) => lineBadge(l)).join('')}</div></span></button>`).join('')}</div>` : '<div class="note">검색 결과가 없습니다.</div>';
      $$('[data-st]').forEach((b) => b.addEventListener('click', () => go('station', JSON.parse(b.dataset.st))));
    } catch (er) { $('#res').innerHTML = errBox(er); }
  });
};

VIEWS.station = function (st) {
  screen.innerHTML = backBtn() + `<div class="band" style="background:var(--c-subway)"><div class="row"><div class="title" style="font-size:34px">${esc(st.name)}</div><span class="legs">${st.lines.map((l) => lineBadge(l, true)).join('')}</span><span style="flex:1"></span><button id="rf" aria-label="새로고침">↻</button></div>
    <div class="meta">실시간 도착 · 20초마다 갱신 <span id="upd"></span></div></div><div id="rows">${loading()}</div>`;
  bindBack();
  if (st.lat) { clearLayer('pin'); L.marker([st.lat, st.lng], { icon: mkIcon('pin') }).addTo(layers.pin); map.setView([st.lat, st.lng], 16); }
  let data = null, got = 0;
  const draw = () => {
    if (!data) return;
    const el = (Date.now() - got) / 1000;
    const key = (a) => (a.sec ? a.sec : /도착|진입|출발/.test(a.msg || '') ? 1 : 99999);
    const sorted = [...data.arrivals].sort((a, b) => key(a) - key(b));
    $('#rows').innerHTML = data.arrivals.length ? `<div class="list">${sorted.map((a) => {
      const sec = a.sec ? Math.max(0, Math.round(a.sec - el)) : null;
      const txt = sec ? (sec < 60 ? '곧 도착' : Math.ceil(sec / 60) + '분') : (a.msg || '').replace(/\[(\d+)\]번째 전역.*/, '$1정거장 전').slice(0, 14);
      return `<div class="item">${lineBadge(a.line, true)}<span class="grow"><div class="name">${esc(a.dest ? a.dest + '행' : a.text)}</div><div class="sm">${esc([a.updown, a.now && '현재 ' + a.now].filter(Boolean).join(' · '))}${a.express ? ' · 급행' : ''}${a.last ? ' · 막차' : ''}</div></span>
        <span class="eta num ${txt === '곧 도착' ? 'soon' : ''}">${esc(txt)}</span></div>`;
    }).join('')}</div>` : '<div class="note">지금 도착 예정인 열차가 없습니다.</div>';
  };
  const load = async () => {
    try {
      const r = await api('/api/subway/arrivals', { station: st.name });
      if (r.supported === false) { $('#rows').innerHTML = `<div class="note">${esc(r.message || '이 역은 실시간 정보를 지원하지 않습니다.')}</div>`; return; }
      data = r; got = Date.now(); $('#upd').textContent = '· ' + new Date().toTimeString().slice(0, 8); draw();
    } catch (e) { $('#rows').innerHTML = errBox(e) + '<div class="note">실시간 도착은 서울·수도권 역만 됩니다.</div>'; }
  };
  $('#rf').addEventListener('click', load);
  load(); every(20000, load); every(1000, draw);
};

/* 기차·항공 시간표 */
const KINDS = {
  train: { title: '기차', color: 'var(--c-train)', list: '/api/train/stations', key: 'stations', trips: '/api/train/trips', tkey: 'trips', place: '출발역·도착역' },
  air: { title: '항공', color: 'var(--c-air)', list: '/api/air/airports', key: 'airports', trips: '/api/air/flights', tkey: 'flights', place: '출발 공항·도착 공항' },
};
function timetable(kind) {
  const K = KINDS[kind];
  screen.innerHTML = `<div class="band" style="background:${K.color}"><div class="row"><div class="title">${K.title}</div></div><div class="meta">${K.place}과 날짜를 고르세요 (국토교통부 시간표)</div></div>
    <div class="form-row pad" style="padding-top:6px"><div class="fld"><label for="dep">출발</label><input id="dep" list="dl" placeholder="이름 입력" autocomplete="off"></div>
      <button class="btn line" id="sw" style="margin-bottom:10px" aria-label="바꾸기">⇄</button>
      <div class="fld"><label for="arr">도착</label><input id="arr" list="dl" placeholder="이름 입력" autocomplete="off"></div></div>
    <div class="form-row pad"><div class="fld"><label for="date">날짜</label><input id="date" type="date" value="${today()}"></div><button class="btn" id="go" style="margin-bottom:10px">조회</button></div>
    <datalist id="dl"></datalist><div id="res"></div>`;
  let places = [];
  api(K.list).then((r) => { places = r[K.key]; $('#dl').innerHTML = places.map((p) => `<option value="${esc(p.name)}"></option>`).join(''); }).catch((e) => { $('#res').innerHTML = errBox(e); });
  const idOf = (n) => places.find((p) => p.name === n.trim())?.id;
  $('#sw').addEventListener('click', () => { const a = $('#dep').value; $('#dep').value = $('#arr').value; $('#arr').value = a; });
  $('#go').addEventListener('click', async () => {
    const dep = idOf($('#dep').value), arr = idOf($('#arr').value);
    if (!dep || !arr) { $('#res').innerHTML = '<div class="note err">목록에 있는 이름을 골라 주세요.</div>'; return; }
    $('#res').innerHTML = loading();
    try {
      const r = await api(K.trips, { dep, arr, date: $('#date').value });
      const rows = r[K.tkey];
      const now = new Date(); const nowKey = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}${String(now.getHours()).padStart(2, '0')}${String(now.getMinutes()).padStart(2, '0')}`;
      const next = rows.findIndex((t) => t.dep >= nowKey);
      $('#res').innerHTML = rows.length ? rows.map((t, i) => `<div class="trip ${t.dep < nowKey ? 'past' : ''} ${i === next ? 'next' : ''}" style="--tc:${K.color}">
        <div class="top"><span class="t num">${hhmm(t.dep)}</span><span class="mid"><span>${esc(durText(t.dep, t.arr))}</span><span class="ln"><i></i><b></b><i></i></span></span><span class="t num">${hhmm(t.arr)}</span></div>
        <div class="bot"><span><span class="kind">${esc(t.kind || '')}</span><span class="sub">${esc(t.no)}</span>${i === next ? ' <b style="color:var(--accent);font-size:11px">다음 출발</b>' : ''}</span><span class="fare">${esc(won(t.fare))}</span></div></div>`).join('') : '<div class="note">해당 날짜에 운행편이 없습니다.</div>';
    } catch (e) { $('#res').innerHTML = errBox(e); }
  });
}
VIEWS.train = () => timetable('train');
VIEWS.air = () => timetable('air');

/* 길찾기 */
VIEWS.plan = function () {
  const P = S.plan;
  const picker = (id, label, val) => `<div class="fld"><label for="${id}">${label}</label><div style="display:flex;gap:6px"><input id="${id}" style="flex:1" placeholder="장소 검색" value="${esc(val?.name || '')}" autocomplete="off"><button class="btn line" data-here="${id}" title="내 위치" style="padding:0 12px">⌖</button></div><div id="${id}r" class="list"></div></div>`;
  screen.innerHTML = backBtn() + `<div class="pad" style="padding-top:8px"><h1 class="h1" style="font-size:22px">길찾기</h1></div>${picker('po', '출발', P.o)}${picker('pd', '도착', P.d)}
    <div class="chips"><button class="chip ${P.mode === 'transit' ? 'on' : ''}" data-mode="transit">대중교통</button><button class="chip ${P.mode === 'car' ? 'on' : ''}" data-mode="car">자동차</button></div>
    <div class="chips" id="opts" style="padding-top:0;display:${P.mode === 'car' ? 'flex' : 'none'}">${[['recommend', '추천'], ['shortest', '최단거리'], ['notoll', '무료도로'], ['nomotorway', '고속도로 제외']].map(([k, l]) => `<button class="chip ${P.option === k ? 'on' : ''}" data-opt="${k}">${l}</button>`).join('')}</div>
    <div class="pad"><button class="btn" id="run" style="width:100%">경로 찾기</button></div><div id="res"></div>`;
  bindBack();
  const bindPick = (id, key) => {
    const inp = $('#' + id); let t;
    inp.addEventListener('input', () => {
      clearTimeout(t); P[key] = null;
      const q = inp.value.trim();
      if (q.length < 2) { $('#' + id + 'r').innerHTML = ''; return; }
      t = setTimeout(async () => {
        try {
          const r = await api('/api/place', { q, ...(S.here || {}) });
          $('#' + id + 'r').innerHTML = r.places.slice(0, 6).map((p, i) => `<button class="item" data-p="${i}"><span class="grow"><div class="name">${esc(p.name)}</div><div class="sm">${esc(p.address || '')}</div></span></button>`).join('');
          $$('[data-p]', $('#' + id + 'r')).forEach((b) => b.addEventListener('click', () => { const p = r.places[+b.dataset.p]; P[key] = { name: p.name, lat: p.lat, lng: p.lng }; inp.value = p.name; $('#' + id + 'r').innerHTML = ''; }));
        } catch (e) { $('#' + id + 'r').innerHTML = errBox(e); }
      }, 350);
    });
  };
  bindPick('po', 'o'); bindPick('pd', 'd');
  $$('[data-here]').forEach((b) => b.addEventListener('click', async () => {
    try { const h = await locate(); const key = b.dataset.here === 'po' ? 'o' : 'd'; P[key] = { name: '내 위치', ...h }; $('#' + b.dataset.here).value = '내 위치'; } catch (e) { toast(e.message); }
  }));
  $$('[data-mode]').forEach((b) => b.addEventListener('click', () => { P.mode = b.dataset.mode; go('plan', null, false); }));
  $$('[data-opt]').forEach((b) => b.addEventListener('click', () => { P.option = b.dataset.opt; $$('[data-opt]').forEach((x) => x.classList.toggle('on', x === b)); if (P.o && P.d) run(); }));
  const run = async () => {
    if (!P.o || !P.d) { $('#res').innerHTML = '<div class="note err">출발과 도착을 검색 결과에서 골라 주세요.</div>'; return; }
    $('#res').innerHTML = loading('경로를 찾는 중…');
    clearLayer('route'); clearLayer('pin');
    [P.o, P.d].forEach((p, i) => L.marker([p.lat, p.lng], { icon: mkIcon(i ? 'pin' : 'me') }).addTo(layers.pin));
    map.fitBounds([[P.o.lat, P.o.lng], [P.d.lat, P.d.lng]], { padding: [60, 60] });
    try {
      const q = { olat: P.o.lat, olng: P.o.lng, dlat: P.d.lat, dlng: P.d.lng };
      if (P.mode === 'car') renderCar(await api('/api/route/car', { ...q, option: P.option })); else renderTransit(await api('/api/route/transit', q));
    } catch (e) { $('#res').innerHTML = errBox(e); }
  };
  $('#run').addEventListener('click', run);
};

const TRAFFIC = { 1: '#E53935', 2: '#F57C00', 3: '#FBC02D', 4: '#2E9B57', 6: '#8E0000' };
function renderCar(r) {
  if (!r.found) { $('#res').innerHTML = `<div class="note err">${esc(r.message || '경로를 찾지 못했습니다.')}</div>`; return; }
  const s = r.summary;
  r.roads.forEach((road) => { if (road.points.length > 1) L.polyline(road.points, { color: TRAFFIC[road.state] || '#3D5BAB', weight: 6, opacity: .9 }).addTo(layers.route); });
  const fare = [s.toll ? '통행료 ' + won(s.toll) : '통행료 무료', s.taxi ? '택시 ' + won(s.taxi) : ''].filter(Boolean).join(' · ');
  const heavy = r.roads.filter((x) => [1, 2, 6].includes(x.state)).reduce((a, x) => a + (x.distance || 0), 0);
  $('#res').innerHTML = `<div class="sum"><div class="big num">${esc(mins(s.duration))}</div><div class="sub">${esc(km(s.distance))} · ${esc(fare)}</div><div class="sub">${heavy ? '정체 ' + esc(km(heavy)) : '정체 없음'}</div></div>
    <div class="list">${r.guides.filter((g) => g.guidance && !/12시 방향/.test(g.guidance)).map((g, i) => `<div class="step"><span class="ic">${i + 1}</span><span class="grow" style="flex:1"><div class="name" style="font-weight:700">${esc(g.guidance)}</div><div class="sm sub">${esc(km(g.distance || 0))}</div></span></div>`).join('')}</div>`;
}
function renderTransit(r) {
  if (!r.routes.length) { $('#res').innerHTML = `<div class="note err">${esc({ STARTNODES_NULL: '출발지 근처에 대중교통이 없습니다.', ENDNODES_NULL: '도착지 근처에 대중교통이 없습니다.', NO_RESULTS: '대중교통 경로를 찾지 못했습니다.', EQUAL_POINTS: '출발지와 도착지가 같습니다.' }[r.status] || '경로를 찾지 못했습니다. (' + esc(r.status || '') + ')')}</div>`; return; }
  const draw = (rt) => {
    clearLayer('route');
    rt.steps.forEach((st, i) => { if (st.points.length > 1) L.polyline(st.points, st.type === 'WALKING' ? { color: '#7A8494', weight: 4, dashArray: '2 8' } : { color: st.type === 'SUBWAY' ? '#4B5563' : '#1F7A4A', weight: 6, opacity: .9 }).addTo(layers.route); });
  };
  $('#res').innerHTML = `<div class="list">${r.routes.map((rt, i) => `<button class="item" data-r="${i}" style="align-items:flex-start"><span class="grow"><div class="name num" style="font-size:20px">${esc(mins(rt.totalTime))} <span class="sub" style="font-size:12px;font-weight:500">${esc(rt.fare ? won(rt.fare) : '')} · 환승 ${rt.transfers ?? 0}회</span></div>
    <div class="legs">${rt.steps.map((st) => st.type === 'WALKING' ? `<span class="w">걷기 ${esc(Math.max(1, Math.round((st.time || 0) / 60)))}분</span>` : `<span class="badge" style="background:${st.type === 'SUBWAY' ? 'var(--c-subway)' : routeColor((st.vehicles[0] || {}).type)};height:26px;min-width:44px">${esc((st.vehicles[0] || {}).name || (st.type === 'SUBWAY' ? '지하철' : '버스'))}</span>`).join('<span class="w">›</span>')}</div>
    <div class="sm" style="margin-top:6px">${esc(rt.steps.filter((s) => s.type !== 'WALKING').map((s) => (s.stops[0] || '') + ' → ' + (s.stops[s.stops.length - 1] || '')).join('  |  '))}</div></span></button>`).join('')}</div>`;
  $$('[data-r]').forEach((b) => b.addEventListener('click', () => draw(r.routes[+b.dataset.r])));
  draw(r.routes[0]);
}

VIEWS.more = async function () {
  const pref = localStorage.getItem('theme') || 'system';
  screen.innerHTML = `<div class="pad" style="padding-top:28px"><h1 class="h1">더보기</h1>
    <button class="item" id="goair" style="margin:14px -16px 0;width:calc(100% + 32px)"><span style="color:var(--accent)">${svg('plane').replace('<svg', '<svg width="22" height="22"')}</span><span class="grow"><div class="name">항공</div><div class="sm">국내선 운항 시간표</div></span></button>
    <div class="label">화면 모드</div><div class="chips" style="padding:0">${[['system', '시스템'], ['light', '라이트'], ['dark', '다크']].map(([k, l]) => `<button class="chip ${pref === k ? 'on' : ''}" data-theme="${k}">${l}</button>`).join('')}</div>
    <div class="sub" style="margin-top:6px">시스템을 고르면 기기의 라이트·다크 설정을 따라 바뀝니다.</div>
    <div class="label">서버 상태</div><div id="cfg" class="sub">확인 중…</div>
    <div class="label">안내</div><div class="sub">이 웹 버전은 조회용입니다. GPS 경로 안내·하차 알림·위젯은 안드로이드 앱에서만 됩니다. 버스 도착은 국토교통부 실시간 서비스 지역만, 지하철 실시간은 서울·수도권만 지원합니다.</div></div>`;
  $('#goair').addEventListener('click', () => go('air'));
  $$('[data-theme]').forEach((b) => b.addEventListener('click', () => { localStorage.setItem('theme', b.dataset.theme); applyTheme(); $$('[data-theme]').forEach((x) => x.classList.toggle('on', x === b)); }));
  try {
    const c = await api('/api/config');
    const ok = (v) => (v ? '✓' : '✗ 키 없음');
    $('#cfg').innerHTML = `TAGO ${ok(c.tago)} · 카카오 ${ok(c.kakao)} · 서울 지하철 ${ok(c.seoul)} · 정류장 데이터 ${esc(c.db.state)}`;
  } catch (e) { $('#cfg').textContent = e.message; }
};

go('home');
