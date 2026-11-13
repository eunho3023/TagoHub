/* TagoHub 배치도 편집기
   정류장을 4칸 폭 격자에 놓고, 앱의 bus_route_layouts.tsv 와 같은 형식으로 내보낸다.
   고칠 때마다 브라우저(localStorage)에 저장한다. */
(() => {
  const $ = (s) => document.querySelector(s);
  const COLS = 4; // 앱의 PER_ROW 와 같다
  const KEY = (id) => 'tagohub.layout.' + id;

  const S = { route: null, stops: [], cells: {}, sel: null, rows: 4, savedAt: 0 };

  function hint(msg, ms = 4000) {
    const el = $('#hint');
    el.textContent = msg || '';
    clearTimeout(hint.t);
    if (msg && ms) hint.t = setTimeout(() => { el.textContent = ''; }, ms);
  }

  async function api(path, params) {
    const q = new URLSearchParams(params || {});
    const r = await fetch(path + (q.toString() ? '?' + q : ''));
    const j = await r.json().catch(() => ({ error: '서버 응답을 읽지 못했습니다.' }));
    if (!r.ok || j.error) throw new Error(j.error || r.statusText);
    return j;
  }

  function download(name, text, type = 'text/plain') {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([text], { type: type + ';charset=utf-8' }));
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  }

  /* ── 저장 ── */
  function save() {
    if (!S.route) return;
    try { localStorage.setItem(KEY(S.route.id), JSON.stringify({ cells: S.cells, rows: S.rows, at: Date.now() })); } catch { /* 저장 공간 없으면 넘어감 */ }
    S.savedAt = Date.now();
    status();
  }

  function restore(id) {
    try {
      const j = JSON.parse(localStorage.getItem(KEY(id)) || 'null');
      if (j && j.cells) return j;
    } catch { /* 깨진 저장은 무시 */ }
    return null;
  }

  function status() {
    const placed = Object.keys(S.cells).length;
    const t = S.savedAt ? new Date(S.savedAt).toTimeString().slice(0, 8) : '';
    $('#save').textContent = `${placed}/${S.stops.length}칸 배치${t ? ' · 저장됨 ' + t : ' · 저장 안 됨'}`;
  }

  /* ── 그리기 ── */
  const maxY = () => Object.values(S.cells).reduce((m, p) => Math.max(m, p[1]), 0);

  function render() {
    const rows = Math.max(S.rows, maxY() + 2, 2);
    S.rows = rows;
    const grid = $('#grid');
    grid.innerHTML = '';
    for (let y = 0; y < rows; y++) {
      for (let x = 0; x < COLS; x++) {
        const cell = document.createElement('div');
        cell.className = 'cell';
        cell.dataset.x = x; cell.dataset.y = y;
        const axis = document.createElement('i');
        axis.className = 'axis';
        axis.textContent = `${x},${y}`;
        cell.appendChild(axis);
        S.stops.forEach((s, i) => {
          const p = S.cells[s.id];
          if (!p || p[0] !== x || p[1] !== y) return;
          const chip = document.createElement('button');
          chip.className = 'chip' + (S.sel === s.id ? ' sel' : '');
          chip.dataset.node = s.id;
          chip.innerHTML = `<b>${i + 1}</b><span></span>`;
          chip.querySelector('span').textContent = s.name || s.id;
          chip.title = `${i + 1}. ${s.name} (${x},${y})`;
          cell.appendChild(chip);
        });
        grid.appendChild(cell);
      }
    }
    const tray = $('#tray');
    tray.innerHTML = '';
    S.stops.forEach((s, i) => {
      if (S.cells[s.id]) return;
      const chip = document.createElement('button');
      chip.className = 'chip' + (S.sel === s.id ? ' sel' : '');
      chip.dataset.node = s.id;
      chip.innerHTML = `<b>${i + 1}</b><span></span>`;
      chip.querySelector('span').textContent = s.name || s.id;
      tray.appendChild(chip);
    });
    if (!tray.children.length) tray.innerHTML = '<div class="label">모두 배치했습니다.</div>';
    status();
  }

  /* ── 선택·배치 ── */
  function select(id) {
    S.sel = S.sel === id ? null : id;
    render();
  }

  function place(x, y) {
    if (!S.sel) return hint('먼저 정류장을 누르세요.');
    S.cells[S.sel] = [x, y];
    save();
    render();
  }

  function moveSelected(dx, dy) {
    if (!S.sel) return;
    const p = S.cells[S.sel] || [0, 0];
    S.cells[S.sel] = [Math.max(0, Math.min(COLS - 1, p[0] + dx)), Math.max(0, p[1] + dy)];
    save();
    render();
  }

  function autoLayout() {
    if (!S.stops.length) return hint('먼저 노선을 조회하세요.');
    S.cells = {};
    S.stops.forEach((s, i) => {
      const row = Math.floor(i / COLS), col = i % COLS;
      S.cells[s.id] = [row % 2 === 0 ? col : COLS - 1 - col, row];
    });
    S.sel = null;
    save();
    render();
    hint('정류장을 순서대로 4칸씩 놓았습니다. 필요한 칸만 옮기세요.');
  }

  /* ── 노선 ── */
  async function findRoute() {
    const no = $('#q').value.trim();
    if (!no) return hint('노선 번호를 넣으세요.');
    hint('노선을 찾는 중…', 0);
    try {
      const r = await api('/api/bus/routes', { city: 'seoul', no });
      if (!r.routes || !r.routes.length) return hint('그 번호의 노선이 없습니다.');
      await loadRoute(r.routes[0].routeId);
    } catch (e) { hint(e.message); }
  }

  async function loadRoute(id) {
    hint('노선 정보를 불러오는 중…', 0);
    try {
      const d = await api('/api/bus/route', { city: 'seoul', route: id });
      S.route = { id, no: d.info.routeNo, start: d.info.start, end: d.info.end };
      S.stops = (d.stops || []).filter((s) => s.id).sort((a, b) => (a.ord || 0) - (b.ord || 0));
      S.cells = {}; S.sel = null; S.savedAt = 0;
      const saved = restore(id);
      if (saved) { S.cells = saved.cells; S.rows = saved.rows || 4; S.savedAt = saved.at || 0; }
      $('#name').textContent = `${S.route.no} · ${S.route.start || '?'} ↔ ${S.route.end || '?'} · 정류장 ${S.stops.length}`;
      document.title = `TagoHub 배치도 편집기 · ${S.route.no}`;
      render();
      hint(saved ? '저장해 둔 배치를 불러왔습니다.' : '자동 배치로 초안을 만들고 칸을 옮겨 보세요.');
    } catch (e) { hint(e.message); }
  }

  /* ── 가져오기·내보내기 ── */
  async function importFile(file) {
    const cells = LayoutFormat.parseLayoutText(await file.text(), S.route && S.route.id);
    if (!cells) return hint('좌표 파일에서 배치를 찾지 못했습니다.');
    S.cells = cells;
    S.sel = null;
    save();
    render();
    const missing = S.stops.filter((s) => !cells[s.id]).length;
    hint(`좌표 ${Object.keys(cells).length}개를 불러왔습니다.${missing ? ` 이 노선 정류장 ${missing}개는 아직 배치 전입니다.` : ''}`);
  }

  function exportTsv() {
    if (!S.route) return hint('먼저 노선을 조회하세요.');
    if (!Object.keys(S.cells).length) return hint('배치한 칸이 없습니다.');
    const order = S.stops.map((s) => s.id);
    const text = LayoutFormat.toTsv(S.route.id, S.cells, order) + '\n';
    download(`${S.route.id.replace(/^SEB/, '')}.tsv`, text);
    const missing = S.stops.filter((s) => !S.cells[s.id]).length;
    hint(missing ? `TSV 를 내려받았습니다. 배치 안 된 정류장 ${missing}개는 빠졌습니다.` : 'bus_route_layouts.tsv 에 넣을 한 줄을 내려받았습니다.');
  }

  function exportJson() {
    if (!S.route) return hint('먼저 노선을 조회하세요.');
    download(`${S.route.id.replace(/^SEB/, '')}.layout.json`, JSON.stringify({ id: S.route.id, cells: S.cells }, null, 2), 'application/json');
  }

  /* ── 이벤트 ── */
  function bind() {
    $('#find').addEventListener('click', findRoute);
    $('#q').addEventListener('keydown', (e) => { if (e.key === 'Enter') findRoute(); });
    $('#auto').addEventListener('click', autoLayout);
    $('#rowAdd').addEventListener('click', () => { S.rows = maxY() + 3; save(); render(); });
    $('#rowDel').addEventListener('click', () => { S.rows = Math.max(2, maxY() + 1); save(); render(); });
    $('#clear').addEventListener('click', () => {
      if (!Object.keys(S.cells).length) return;
      if (!confirm('배치를 모두 지울까요?')) return;
      S.cells = {}; S.sel = null; save(); render();
    });
    $('#tsvSave').addEventListener('click', exportTsv);
    $('#jsonSave').addEventListener('click', exportJson);
    $('#file').addEventListener('change', (e) => { const f = e.target.files[0]; if (f) importFile(f); e.target.value = ''; });

    $('#grid').addEventListener('click', (e) => {
      const chip = e.target.closest('.chip');
      if (chip) { select(chip.dataset.node); return; }
      const cell = e.target.closest('.cell');
      if (cell) place(Number(cell.dataset.x), Number(cell.dataset.y));
    });
    $('#tray').addEventListener('click', (e) => {
      const chip = e.target.closest('.chip');
      if (chip) select(chip.dataset.node);
    });
    document.addEventListener('keydown', (e) => {
      if (!S.sel) return;
      const tag = (document.activeElement && document.activeElement.tagName) || '';
      if (tag === 'INPUT') return;
      const map = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
      if (map[e.key]) { e.preventDefault(); moveSelected(...map[e.key]); }
    });
  }

  bind();
  render();
})();
