// 버스 화면: 정류장 찾기, 정류장(실시간 도착), 노선(지도·버스 위치·정류장 목록), 지도 탭.
import { esc, busColor, naturalCompare, isoDay, meters } from "./core.js";
import { act, header, note, sectionHead, bindInput, icon } from "./ui.js";
import { go, isWide } from "./nav.js";
import { MapView } from "./map.js";
import { Favorites, Recent } from "./store.js";
import * as B from "./busdata.js";
import { Alight } from "./alight.js";

const REFRESH_MS = 15000;
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
  rows.forEach((r) => { r.city = r.arr.find((a) => a.city)?.city || null; });
  rows.forEach((r) => r.arr.sort((a, b) => (a.arrSeconds ?? 1e9) - (b.arrSeconds ?? 1e9)));
  return rows.sort((x, y) => (x.arr.length ? 0 : 1) - (y.arr.length ? 0 : 1) || (x.arr[0]?.arrSeconds ?? 1e9) - (y.arr[0]?.arrSeconds ?? 1e9) || naturalCompare(x.no, y.no));
}

export function stopScreen(root, p) {
  let stop = known.get(p.id);
  let alive = true, live = null, sums = null, city = null;
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
      const s = r.sum;
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
    const sub = [city, stop.no ? "정류장 번호 " + stop.no : null].filter(Boolean).join(" · ");
    root.innerHTML = header(stop.name, sub || null, {
      back: true,
      star: { on: Favorites.hasStop(stop), toggle: () => { Favorites.toggleStop(stop); draw(); } },
      action: isWide() ? null : { label: "위치", run: () => go("map", stop.id, { lat: stop.lat, lng: stop.lng }) },
    }) + (status ? note(status) : "") + `<div class="list">${rows.map((r, i) => {
      const ends = r.sum ? [r.sum.start, r.sum.end].filter(Boolean).join(" → ") : "";
      return `<button class="row route" ${act(() => go("route", r.id, { from: stop.id, city: r.city || (/^\d+$/.test(stop.city) ? stop.city : null) }))}>
        <span class="grow"><span class="badge big" style="background:${busColor(r.type)}">${esc(r.no)}</span><small>${esc(ends || r.type || "")}</small></span>
        <span class="right" data-r="${i}"></span></button>`;
    }).join("")}</div>`;
    tickTimes();
  };
  (async () => {
    root.innerHTML = header("정류장", null, { back: true }) + note("불러오는 중...");
    if (!(await ensure())) return;
    draw();
    B.cityName(stop.city).then((c) => { city = c; draw(); });
    B.stopRoutes(stop).then((s) => { sums = s; draw(); }).catch(() => { sums = []; draw(); });
    const tick = async () => { live = await B.arrivals(stop); draw(); };
    tick();
    timers.push(setInterval(tick, REFRESH_MS), setInterval(tickTimes, 1000));
  })();
  return () => { alive = false; timers.forEach(clearInterval); };
}

// ── 노선 화면 ──
const plateTail = (no) => (no || "").replace(/\D/g, "").slice(-4) || null;

export function routeScreen(root, p, ctx) {
  let r = null, pos = null, road = null, alive = true, fitted = false, focusSeq = null;
  const timers = [];
  root.innerHTML = header("노선", null, { back: true }) + note("불러오는 중...");
  const drawMap = () => {
    if (!r) return;
    const color = busColor(r.type);
    const pts = r.stops.filter((s) => s.lat != null).map((s) => [s.lat, s.lng]);
    MapView.lines([{ pts: road || pts, color, width: 5 }]);
    const z = MapView.zoom();
    const dots = [];
    for (const s of r.stops) {
      if (s.lat == null) continue;
      const here = p.from && s.id && B.legacyId(s.id) === B.legacyId(p.from), focused = focusSeq === s.seq;
      if (z >= 14 || here || focused) dots.push({ id: "s:" + s.seq, lat: s.lat, lng: s.lng, color: here || focused ? "#D7262E" : "#fff", ring: color, r: here || focused ? 7 : 4, label: focused ? s.name : null });
    }
    for (const b of pos?.buses || []) if (b.lat != null) dots.push({ id: "b:" + b.vehicleNo, lat: b.lat, lng: b.lng, color, ring: "#fff", r: 8, label: plateTail(b.vehicleNo) });
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
        <button class="dotbtn" ${act(() => { if (st.lat != null) { focusSeq = st.seq; MapView.view(st.lat, st.lng, 17); drawMap(); } })}>
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
    if (!alive) return;
    if (!r) { root.innerHTML = header("노선", null, { back: true }) + note("노선 정보를 받지 못했어요"); return; }
    root.innerHTML = header(r.no, [[r.start, r.end].filter(Boolean).join(" → "), r.region, r.type].filter(Boolean).join(" · "), { back: true }) +
      `<div class="map-slot"></div><div id="status" class="status"></div><div id="stops" class="list"></div>`;
    ctx.useMap(root.querySelector(".map-slot"));
    drawList(); drawMap();
    B.shape(r.id).then((s) => { road = s; drawMap(); });
    const offIdle = MapView.onIdle(drawMap);
    timers.push(() => offIdle());
    const tick = async () => { pos = await B.positions(r); if (alive) { drawList(); drawMap(); } };
    tick();
    const t = setInterval(tick, REFRESH_MS);
    timers.push(() => clearInterval(t));
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
