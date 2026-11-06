// 버스 화면: 정류장 찾기, 정류장(실시간 도착), 노선(지도·버스 위치·정류장 목록), 지도 탭.
import { esc, busColor, naturalCompare, isoDay, meters } from "./core.js";
import { act, header, note, sectionHead, bindInput, icon } from "./ui.js";
import { go, isWide } from "./nav.js";
import { MapView } from "./map.js";
import { Favorites, Recent } from "./store.js";
import * as B from "./busdata.js";
import { Alight } from "./alight.js";

const REFRESH_MS = 15000, BUS_POS_MS = 10000;
const stopParams = (s) => ({ lat: s.lat, lng: s.lng });
/** 화면 사이에 정류장 정보를 넘김 (주소에는 id·좌표만) */
const known = new Map();
export const remember = (s) => { known.set(s.id, s); return s; };

function stopLine(s, sub) {
  remember(s);
  return `<button class="row" ${act(() => go("stop", s.id, stopParams(s)))}>
    <span class="stopmark"></span>
    <span class="grow"><b>${esc(s.name)}</b><small>${esc(sub ?? (s.no ? "정류장 번호 " + s.no : ""))}</small></span>
  </button>`;
}

// ── 정류장 찾기 (대중교통 → 버스) ──
const search = { q: "", result: [] };
export function busSearch(root, chipsHtml) {
  root.innerHTML = header("TagoHub", "버스 정류장 실시간 도착", { action: { label: "지도", run: () => go("map") } }) + chipsHtml +
    `<div class="searchbox"><input id="q" type="search" placeholder="정류장 이름" value="${esc(search.q)}" autocomplete="off"></div><div id="out"></div>`;
  const out = root.querySelector("#out");
  let seq = 0;
  const draw = async () => {
    const q = search.q.trim();
    if (!q) {
      const fav = Favorites.stops.items, rec = Recent.stops.items;
      out.innerHTML = note("정류장 이름을 입력해 주세요") +
        (fav.length ? sectionHead("즐겨찾기") + `<div class="list">${fav.map((s) => stopLine(s)).join("")}</div>` : "") +
        (rec.length ? sectionHead("최근 본 정류장", () => { Recent.stops.clear(); draw(); }) + `<div class="list">${rec.map((s) => stopLine(s)).join("")}</div>` : "");
      return;
    }
    const my = ++seq;
    out.innerHTML = note("찾는 중...");
    const r = await B.searchStops(q).catch(() => null);
    if (my !== seq) return;
    if (!r) { out.innerHTML = note("정류장 목록을 받지 못했어요"); return; }
    if (!r.length) { out.innerHTML = note("찾는 정류장이 없어요"); return; }
    out.innerHTML = `<div class="list">${r.map((s) => stopLine(s, "")).join("")}</div>`;
    // 시군 이름은 나중에 채움
    const rows = out.querySelectorAll(".row small");
    r.forEach((s, i) => B.cityName(s.city).then((c) => { if (rows[i]) rows[i].textContent = [c, s.no ? "정류장 번호 " + s.no : null].filter(Boolean).join(" · "); }));
  };
  const inp = bindInput(root, "#q", (v) => { search.q = v; draw(); });
  if (!isWide()) inp?.focus({ preventScroll: true });
  draw();
}

// ── 정류장 화면 ──
/** 노선 요약과 실시간 도착을 합친 줄 (실시간 있는 노선 먼저, 없으면 번호순) */
function mergeRows(sums, arrivals) {
  const byId = new Map();
  for (const a of arrivals) { const k = a.routeId || a.routeNo; if (!byId.has(k)) byId.set(k, []); byId.get(k).push(a); }
  const rows = sums.map((s) => ({ id: s.id, no: s.no, type: s.type, sum: s, arr: (byId.get(s.id) || arrivals.filter((a) => !a.routeId && a.routeNo === s.no)) }));
  const knownIds = new Set(sums.map((s) => s.id));
  for (const [k, l] of byId) if (!knownIds.has(k) && !rows.some((r) => r.arr.some((a) => a.routeId === k))) rows.push({ id: k, no: l[0].routeNo, type: l[0].routeType, sum: null, arr: l });
  rows.forEach((r) => { r.city = r.arr.find((a) => a.city)?.city || r.sum?.city || null; });
  rows.forEach((r) => r.arr.sort((a, b) => (a.arrSeconds ?? 1e9) - (b.arrSeconds ?? 1e9)));
  return rows.sort((x, y) => (x.arr.length ? 0 : 1) - (y.arr.length ? 0 : 1) || (x.arr[0]?.arrSeconds ?? 1e9) - (y.arr[0]?.arrSeconds ?? 1e9) || naturalCompare(x.no, y.no));
}

export function stopScreen(root, p) {
  let stop = known.get(p.id);
  let alive = true, live = null, sums = null, city = null, toward = null;
  const timers = [];
  const ensure = async () => {
    if (!stop && p.lat) stop = await B.stopAt(p.id, +p.lat, +p.lng);
    if (!stop) { root.innerHTML = header("정류장", null, { back: true }) + note("정류장을 찾지 못했어요"); return false; }
    remember(stop);
    Recent.addStop(stop);
    return true;
  };
  let rows = [];
  /** 한 줄 오른쪽 (남은 시간은 매초 줄어듦) */
  const rightOf = (r, elapsed, day) => {
    const f = r.arr.find((a) => !B.passed(a, elapsed));
    if (!f) {
      const s = r.sum && (r.sum.first || r.sum.last || r.sum.interval) ? r.sum : infos.get(r.id) || r.sum;
      const plan = s ? [s.first && "첫차 " + s.first, s.last && "막차 " + s.last, B.intervalOn(s, day) > 0 && "배차 " + B.intervalOn(s, day) + "분"].filter(Boolean).join(" · ") : "";
      return `<span class="muted">${live && live.state === "ok" ? "도착 정보 없음" : ""}</span>${plan ? `<small>${esc(plan)}</small>` : ""}`;
    }
    const parts = B.arrivalParts(f, elapsed);
    const soon = (f.arrSeconds ?? 0) - elapsed <= 180;
    const next = r.arr.filter((a) => !B.passed(a, elapsed))[1];
    return `<b class="eta ${soon ? "soon" : ""}">${esc(parts[0])}</b>${parts.length > 1 ? `<small>${esc(parts.slice(1).join(" · "))}</small>` : ""}` +
      (next ? `<small>다음 ${esc(B.arrivalParts(next, elapsed).join(" · "))}</small>` : "");
  };
  const elapsedNow = () => (live ? Math.max(0, Math.floor((Date.now() - live.at) / 1000)) : 0);
  /** 매초: 남은 시간 글자만 바꿈 (줄을 다시 만들면 누르는 중인 것이 사라짐) */
  const tickTimes = () => {
    if (!alive) return;
    const el = elapsedNow(), day = isoDay();
    rows.forEach((r, i) => {
      const sp = root.querySelector(`[data-r="${i}"]`);
      const html = rightOf(r, el, day);
      if (sp && sp.dataset.h !== html) { sp.innerHTML = html; sp.dataset.h = html; }
    });
  };
  // 노선 줄 아래 글: 기점 → 종점 (자료에 없으면 노선을 받아 채움, 경기는 "○○ 방면")
  const ends = new Map();
  const infos = new Map(), asked = new Set();
  const endsOf = (r) => {
    // 기점 → 종점이나 첫차·막차·배차가 없는 노선(자료 파일에 없는 노선)은 노선 정보를 한 번 받아 채움
    const hasPlan = r.sum && (r.sum.first || r.sum.last || r.sum.interval);
    const own = r.sum && [r.sum.start, r.sum.end].filter(Boolean).join(" → ");
    // 요청을 아끼려고: 기점·종점이 없거나, 지금 오는 버스가 없어 첫차·막차를 보여 줄 줄만, 한 화면에 15개까지 (노선 요약만, 한 번씩)
    if (r.id && (!own || (live && !hasPlan && !r.arr.length)) && !asked.has(r.id) && asked.size < 15) {
      asked.add(r.id);
      B.routeInfo(r.id, r.city || (/^\d+$/.test(stop.city) ? stop.city : null))
        .then((x) => {
          if (!x || !alive) return;
          infos.set(r.id, x);
          const t = [x.start, x.end].filter(Boolean).join(" → ");
          if (t) ends.set(r.id, t);
          draw();
        }).catch(() => {});
    }
    if (own) return own;
    if (ends.get(r.id)) return ends.get(r.id);
    const towards = r.arr.find((a) => a.towards)?.towards;
    return towards ? towards + " 방면" : "";
  };
  let refresh = null;
  const draw = () => {
    if (!alive || !stop) return;
    const elapsed = elapsedNow();
    const arr = (live?.list || []).filter((a) => !B.passed(a, elapsed));
    rows = mergeRows(sums || [], live?.list || []);
    let status = "";
    if (!sums && !live) status = "불러오는 중...";
    else if (!live) status = "실시간 도착을 받는 중...";
    else if (live.state === "unsupported") status = "이 정류장의 실시간 도착은 웹 버전에서 볼 수 없어요. 폰 앱에서 볼 수 있어요.";
    else if (live.state === "failed") status = "실시간 도착을 받지 못했어요. 잠시 뒤 다시 시도해 주세요.";
    else if (!rows.length) status = "이 정류장에 서는 버스 정보가 없어요";
    else if (!arr.length) status = "지금 도착 예정인 버스가 없어요";
    const sub = [city, stop.no ? "정류장 번호 " + stop.no : null, toward ? toward + " 방면" : null].filter(Boolean).join(" · ");
    root.innerHTML = header(stop.name, sub || null, {
      back: true,
      star: { on: Favorites.hasStop(stop), toggle: () => { Favorites.toggleStop(stop); draw(); } },
      action: isWide() ? null : { label: "위치", run: () => go("map", stop.id, { lat: stop.lat, lng: stop.lng }) },
    }) + (status ? note(status) : "") + `<button class="link pad" ${act(() => refresh && refresh())}>↻ 새로고침</button><div class="list">${rows.map((r, i) => {
      const ends = endsOf(r);
      return `<button class="row route" ${act(() => go("route", r.id, { from: stop.id, city: r.city || (/^\d+$/.test(stop.city) ? stop.city : null), no: r.no, type: r.type }))}>
        <span class="grow"><span class="badge big" style="background:${busColor(r.type)}">${esc(r.no)}</span><small>${esc(ends)}</small></span>
        <span class="right" data-r="${i}"></span></button>`;
    }).join("")}</div>`;
    tickTimes();
  };
  (async () => {
    root.innerHTML = header("정류장", null, { back: true }) + note("불러오는 중...");
    if (!(await ensure())) return;
    draw();
    B.cityName(stop.city).then((c) => { city = c; draw(); });
    B.stopRoutes(stop).then((s) => {
      sums = s; draw();
      // 어느 방면인지: 지나는 노선의 다음 정류장
      B.towardOf(stop, s).then((t) => { if (t && alive) { toward = t; draw(); } }).catch(() => {});
    }).catch(() => { sums = []; draw(); });
    const tick = async () => {
      let got = await B.arrivals(stop);
      if (!alive) return;
      // 직전엔 도착이 있었는데 갑자기 하나도 없으면(공공데이터가 잠깐 빈 응답을 줄 때) 3초 뒤 한 번 더 물음
      if (got.state === "ok" && !got.list.length && live?.list?.length) {
        await new Promise((r) => setTimeout(r, 3000));
        if (!alive) return;
        const again = await B.arrivals(stop);
        if (!alive) return;
        if (again.state === "ok" && again.list.length) got = again;
      }
      // 처음: 받은 도착 정보를 바로 보여 주고, 버스 위치로 고친 것이 오면 바꿈 (위치 확인에 1~3초 걸림)
      // 새로고침: 고친 것을 1.5초까지 기다렸다가 보여 줌 (지나간 버스가 잠깐 다시 보이는 깜빡임 없게)
      const refine = got.state === "ok" && got.list.length ? B.refineArrivals(stop, got.list) : null;
      if (refine && live) {
        const fixed = await Promise.race([refine, new Promise((r) => setTimeout(() => r(null), 1500))]);
        if (!alive) return;
        if (fixed) { live = { ...got, list: fixed }; draw(); return; }
      }
      live = got; draw();
      if (!refine) return;
      const fixed = await refine;
      if (alive && live === got && fixed !== got.list) { live = { ...got, list: fixed }; draw(); }
    };
    // 새로고침 단추: 도착 정보만 다시 (3초 안에 여러 번 누르면 한 번만)
    let lastRefresh = 0;
    refresh = () => { if (Date.now() - lastRefresh < 3000) return; lastRefresh = Date.now(); tick(); };
    tick();
    timers.push(setInterval(tick, REFRESH_MS), setInterval(tickTimes, 1000));
  })();
  return () => { alive = false; timers.forEach(clearInterval); };
}

// ── 노선 화면 ──
/** 점 p 를 선분 a-b 에 내린 자리와 거리(m). 좁은 범위라 평면으로 계산 */
function project(p, a, b) {
  const kx = 111320 * Math.cos((p[0] * Math.PI) / 180), ky = 110540;
  const ax = (a[1] - p[1]) * kx, ay = (a[0] - p[0]) * ky, bx = (b[1] - p[1]) * kx, by = (b[0] - p[0]) * ky;
  const dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
  const t = len ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len)) : 0;
  const x = ax + t * dx, y = ay + t * dy;
  return { d: Math.hypot(x, y), t, pt: [a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])] };
}
/** 도로 선의 처음부터 각 꼭짓점까지 거리(m) */
function roadCum(road) {
  const cum = [0];
  for (let i = 0; i < road.length - 1; i++) {
    const kx = 111320 * Math.cos((road[i][0] * Math.PI) / 180);
    cum.push(cum[i] + Math.hypot((road[i + 1][1] - road[i][1]) * kx, (road[i + 1][0] - road[i][0]) * 110540));
  }
  return cum;
}
/** 점을 도로 선의 [from, to] 구간 안에 내린 자리: {d, ch(처음부터 거리)} */
function chainOf(road, cum, p, from, to) {
  let best = null;
  for (let i = Math.max(0, from); i <= Math.min(to, road.length - 2); i++) {
    const h = project(p, road[i], road[i + 1]);
    if (!best || h.d < best.d) best = { d: h.d, ch: cum[i] + h.t * (cum[i + 1] - cum[i]) };
  }
  return best;
}
/** 처음부터 ch 미터 간 자리 */
function pointAt(road, cum, ch) {
  let i = 0;
  while (i < cum.length - 2 && cum[i + 1] < ch) i++;
  const len = cum[i + 1] - cum[i], t = len ? Math.max(0, Math.min(1, (ch - cum[i]) / len)) : 0;
  return [road[i][0] + t * (road[i + 1][0] - road[i][0]), road[i][1] + t * (road[i + 1][1] - road[i][1])];
}
// 정류장 점을 노선 선 위로 옮기는 최대 거리 (좌표가 골목 쪽으로 잘못 찍힌 정류장도 큰길 선 위로, 서버 shape.js OFF_COORD_M 과 같게)
const SNAP_M = 250;
/** 정류장을 노선 순서대로 도로 선 위로 (뒤로 돌아가지 않게, 가장 가까운 곳과 15m 안이면 먼저 나오는 쪽). 멀면 원래 자리 */
export function snapStops(stops, road) { return snapStopsFull(stops, road).map((x) => x && x.pt); }
/** [{pt, seg, t}] (선 위로 못 옮긴 정류장은 seg 없음) */
function snapStopsFull(stops, road) {
  if (!road || road.length < 2) return stops.map((s) => (s.lat == null ? null : { pt: [s.lat, s.lng] }));
  let cur = 0;
  return stops.map((s) => {
    if (s.lat == null) return null;
    const p = [s.lat, s.lng];
    const hits = [];
    for (let i = cur; i < road.length - 1; i++) hits.push([i, project(p, road[i], road[i + 1])]);
    if (!hits.length) return { pt: p };
    const best = Math.min(...hits.map(([, h]) => h.d));
    if (best > SNAP_M) return { pt: p };
    const [i, h] = hits.find(([, h2]) => h2.d <= best + 15);
    cur = i;
    return { pt: h.pt, seg: i, t: h.t };
  });
}
/** 버스 GPS 를 도로 선 위로 (가까우면) */
function snapPoint(p, road) {
  if (!road || road.length < 2) return p;
  let best = null;
  for (let i = 0; i < road.length - 1; i++) { const h = project(p, road[i], road[i + 1]); if (!best || h.d < best.d) best = h; }
  return best && best.d <= SNAP_M ? best.pt : p;
}
const plateTail =(no) => (no || "").replace(/\D/g, "").slice(-4) || null;

export function routeScreen(root, p, ctx) {
  let r = null, pos = null, road = null, snapped = null, alive = true, fitted = false, focusSeq = null, linesOf = null;
  const timers = [];
  // 버스마다 도로 위 자리(처음부터 m)·그 자리를 받은 시각·속도(m/초): 다음 위치가 올 때까지 도로를 따라 앞으로 움직여 보임
  // (공공데이터 GPS 는 몇십 초 늦게 바뀌어서, 가만히 두면 실제 버스보다 늦어 보임)
  const track = new Map();
  const MAX_AHEAD_S = 45, MAX_SPEED = 16.7;
  const stopCh = (i) => { const x = snapped?.full[i]; return x && x.seg != null ? snapped.cum[x.seg] + x.t * (snapped.cum[x.seg + 1] - snapped.cum[x.seg]) : null; };
  const stopIdx = (b) => { let i = b.nodeId ? r.stops.findIndex((s) => s.id === b.nodeId) : -1; if (i < 0 && b.ord != null) i = r.stops.findIndex((s) => s.seq === b.ord); return i; };
  /** 새 버스 위치를 받으면: 지금 정류장~다음 정류장 사이 도로 위로 내리고 속도를 셈 */
  const learn = () => {
    if (!snapped || !pos) return;
    const now = Date.now(), seen = new Set();
    for (const b of pos.buses || []) {
      if (b.lat == null || !b.vehicleNo) continue;
      seen.add(b.vehicleNo);
      const i = stopIdx(b);
      const from = i >= 0 ? snapped.full[i]?.seg ?? 0 : 0, to = i >= 0 ? snapped.full[i + 1]?.seg ?? road.length - 2 : road.length - 2;
      let h = chainOf(road, snapped.cum, [b.lat, b.lng], from, to);
      if (!h || h.d > 150) h = i >= 0 && stopCh(i) != null ? { ch: stopCh(i) } : null;
      if (!h) { track.delete(b.vehicleNo); continue; }
      const lim = i >= 0 ? stopCh(i + 1) : null;
      const prev = track.get(b.vehicleNo);
      if (prev && Math.abs(h.ch - prev.ch) < 3) { prev.lim = lim ?? prev.lim; continue; } // 위치가 그대로면(자료가 아직 안 바뀜) 계속 앞으로
      let v = 0;
      if (prev && h.ch > prev.ch) v = Math.min(MAX_SPEED, (h.ch - prev.ch) / Math.max(1, (now - prev.at) / 1000));
      if (prev && prev.v && v) v = (v + prev.v) / 2;
      track.set(b.vehicleNo, { ch: h.ch, at: now, v, lim });
    }
    for (const k of [...track.keys()]) if (!seen.has(k)) track.delete(k);
  };
  /** 지금 보일 자리 */
  const busXY = (b) => {
    const t = track.get(b.vehicleNo);
    if (!t || !snapped) return snapPoint([b.lat, b.lng], road);
    let ch = t.ch + t.v * Math.min(MAX_AHEAD_S, (Date.now() - t.at) / 1000);
    if (t.lim != null && t.lim > t.ch) ch = Math.min(ch, t.lim - 3);
    return pointAt(road, snapped.cum, Math.max(t.ch, ch));
  };
  root.innerHTML = header("노선", null, { back: true }) + note("불러오는 중...");
  const drawMap = () => {
    if (!r) return;
    const color = busColor(r.type);
    const pts = r.stops.filter((s) => s.lat != null).map((s) => [s.lat, s.lng]);
    if (linesOf !== (road || pts)) { linesOf = road || pts; MapView.lines([{ pts: linesOf, color, width: 5 }]); }
    const z = MapView.zoom();
    const dots = [];
    // 정류장·버스 점은 도로 선 위에 (선을 받은 뒤)
    if (road && snapped?.road !== road) { snapped = { road, full: snapStopsFull(r.stops, road), cum: roadCum(road) }; track.clear(); learn(); }
    const at = (i) => (road && snapped ? snapped.full[i]?.pt : r.stops[i].lat == null ? null : [r.stops[i].lat, r.stops[i].lng]);
    r.stops.forEach((s, i) => {
      const xy = at(i);
      if (!xy) return;
      const here = p.from && s.id && B.legacyId(s.id) === B.legacyId(p.from), focused = focusSeq === s.seq;
      if (z >= 14 || here || focused) dots.push({ id: "s:" + s.seq, lat: xy[0], lng: xy[1], color: here || focused ? "#D7262E" : color, ring: "#fff", r: here || focused ? 7 : 5, label: focused ? s.name : null });
    });
    for (const b of pos?.buses || []) {
      if (b.lat == null) continue;
      const xy = busXY(b);
      dots.push({ id: "b:" + b.vehicleNo, lat: xy[0], lng: xy[1], color, ring: "#fff", r: 8, label: plateTail(b.vehicleNo) });
    }
    // 좌표가 없는 지역(경기 등): 버스가 있는 정류장 자리에 (폰 앱과 같음)
    for (const [i, list] of B.busAtStops(r.stops, (pos?.buses || []).filter((b) => b.lat == null))) {
      const xy = at(i);
      if (!xy) continue;
      list.forEach((b, k) => dots.push({ id: "b:" + b.vehicleNo, lat: xy[0] + k * 0.00008, lng: xy[1], color, ring: "#fff", r: 8, label: plateTail(b.vehicleNo) }));
    }
    MapView.dots(dots, (d) => {
      const seq = d.id.startsWith("s:") ? +d.id.slice(2) : null;
      const idx = seq != null ? r.stops.findIndex((s) => s.seq === seq) : [...B.busAtStops(r.stops, pos?.buses || []).entries()].find(([, l]) => l.some((b) => "b:" + b.vehicleNo === d.id))?.[0];
      root.querySelector(`[data-i="${idx}"]`)?.scrollIntoView({ behavior: "smooth", block: "center" });
    });
    if (!fitted && pts.length) { MapView.fit(pts); fitted = true; }
  };
  const drawList = () => {
    if (!r || !alive) return;
    const color = busColor(r.type);
    const at = B.busAtStops(r.stops, pos?.buses || []);
    const status = !pos ? "버스 위치를 받는 중..." : pos.state === "unsupported" ? "이 노선의 실시간 버스 위치는 웹 버전에서 볼 수 없어요" :
      pos.state === "failed" ? "버스 위치를 받지 못했어요" : pos.buses.length ? `운행 중 ${pos.buses.length}대 · 15초마다 새로` : "지금 운행 중인 버스가 없어요";
    const s = r.sum;
    const plan = s ? [s.first && "첫차 " + s.first, s.last && "막차 " + s.last, B.intervalOn(s, isoDay()) > 0 && "배차 " + B.intervalOn(s, isoDay()) + "분"].filter(Boolean).join(" · ") : "";
    const list = root.querySelector("#stops");
    root.querySelector("#status").innerHTML = `<b>${esc(status)}</b>${plan ? `<small>${esc(plan)}</small>` : ""}`;
    list.innerHTML = r.stops.map((st, i) => {
      const turn = i > 0 && st.upDown && r.stops[i - 1].upDown && st.upDown !== r.stops[i - 1].upDown ? `<div class="turn">회차</div>` : "";
      const here = p.from && st.id && B.legacyId(st.id) === B.legacyId(p.from);
      const buses = at.get(i) || [];
      const alightOn = st.lat != null && Alight.isTarget(st.name, st.lat, st.lng);
      return turn + `<div class="rstop" data-i="${i}">
        <button class="dotbtn" ${act(() => { if (st.lat != null) { const xy = snapped?.full[i]?.pt || [st.lat, st.lng]; focusSeq = st.seq; MapView.view(xy[0], xy[1], 17); drawMap(); } })}>
          <span class="rdot ${buses.length ? "bus" : ""}" style="--c:${color}"></span></button>
        <button class="grow rname ${here ? "here" : ""}" ${act(async () => {
          if (!st.id || st.lat == null) return;
          // 자료 조각에서 찾고, 없으면 노선 자료로 정류장을 만들어 엶 (전주 등)
          const s2 = (await B.stopAt(st.id, st.lat, st.lng).catch(() => null)) || { id: st.id, name: st.name, lat: st.lat, lng: st.lng, no: st.no || null, city: r.city, routes: [] };
          remember(s2); go("stop", s2.id, stopParams(s2));
        })}>${esc(st.name)}</button>
        ${buses.length ? `<span class="plate" style="background:${color}">${esc(buses.map((b) => plateTail(b.vehicleNo) || "버스").join(" "))}</span>` : ""}
        ${Alight.supported && st.lat != null ? `<button class="mini ${alightOn ? "on" : ""}" ${act(() => { alightOn ? Alight.stop() : Alight.start(st.name, st.lat, st.lng); drawList(); })}>${alightOn ? "알림 끄기" : "내릴 곳"}</button>` : ""}
      </div>`;
    }).join("");
  };
  (async () => {
    try { r = await B.route(p.id, p.city); } catch (e) { r = null; }
    // 자료 조각에 없는 노선은 번호·종류를 정류장 화면에서 넘겨받음
    if (r && r.no === "노선" && p.no) { r.no = p.no; if (!r.type && p.type) r.type = p.type; }
    if (!alive) return;
    if (!r) { root.innerHTML = header("노선", null, { back: true }) + note("노선 정보를 받지 못했어요"); return; }
    root.innerHTML = header(r.no, [[r.start, r.end].filter(Boolean).join(" → "), r.region].filter(Boolean).join(" · "), { back: true }) +
      `<div class="map-slot"></div><div id="status" class="status"></div><button class="link pad" id="rrefresh">↻ 새로고침</button><div id="stops" class="list"></div>`;
    root.querySelector("#rrefresh")?.addEventListener("click", () => tick());
    ctx.useMap(root.querySelector(".map-slot"));
    drawList(); drawMap();
    B.shape(r.id, r.city, r.stops, r.no !== "노선" ? r.no : p.no).then((s) => { road = s; drawMap(); });
    const offIdle = MapView.onIdle(drawMap);
    timers.push(() => offIdle());
    async function tick() { pos = await B.positions(r); if (alive) { learn(); drawList(); drawMap(); } }
    tick();
    // 버스 위치는 10초마다 받고, 그 사이에는 2초마다 도로를 따라 앞으로 움직여 그림
    const t = setInterval(tick, BUS_POS_MS);
    const anim = setInterval(() => { if (alive && [...track.values()].some((x) => x.v > 0)) drawMap(); }, 2000);
    timers.push(() => clearInterval(t), () => clearInterval(anim));
  })();
  return () => { alive = false; timers.forEach((f) => f()); };
}

// ── 지도 탭 (좁은 화면: 지도 + 아래 카드 / 넓은 화면: 오른쪽 지도) ──
import { stopsMap } from "./app.js";
export function mapTab(root, p, ctx) {
  const focus = p.lat ? { id: p.id, lat: +p.lat, lng: +p.lng } : null;
  if (ctx.wide) {
    root.innerHTML = header("지도", "오른쪽 지도에서 정류장을 누르세요", { back: !!focus }) + note("지도를 확대하면 정류장이 보여요. 정류장을 누르면 아래에 카드가 나오고, 카드의 \"도착 정보\"를 누르면 실시간 도착이 보여요.");
  } else {
    root.innerHTML = header("지도", "확대하면 정류장이 보여요", { back: !!focus }) + `<div class="map-slot full"></div>`;
  }
  const slot = root.querySelector(".map-slot");
  ctx.useMap(slot);
  if (focus) MapView.view(focus.lat, focus.lng, 17);
  return stopsMap(ctx.wide ? document.getElementById("side") : slot, focus);
}
