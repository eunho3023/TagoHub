// 지도 고치기 화면: 버스 노선 모양(점을 옮기고 넣고 지움) · 정류장 위치(누른 곳으로). 저장하면 웹·폰 앱 모두에 보임 (서버 /w/edits).
// #/fix/<id>?kind=bus&city=&no=   노선 모양
// #/fix/<id>?kind=stop&lat=&lng=&name=   정류장 위치
import { esc, busColor } from "./core.js";
import { act, header, note } from "./ui.js";
import { MapView } from "./map.js";
import * as B from "./busdata.js";
import * as E from "./edits.js";

const MAX_DOTS = 700;
/** 점 줄이기 기준(m): 작을수록 점이 많고 섬세함 (앱 EDIT_TOL_M 과 같음) */
const TOL_M = 2;
const same = (a, b) => Math.abs(a[0] - b[0]) < 1e-7 && Math.abs(a[1] - b[1]) < 1e-7;

export function fixScreen(root, p, ctx) {
  const kind = p.kind === "stop" ? "stop" : "bus";
  let alive = true, pts = [], orig = [], sel = -1, color = "#2563eb", title = kind === "bus" ? "노선 모양 고치기" : "정류장 위치 고치기", hasEdit = false, busy = false, msg = "";
  let stops = [];
  const help = kind === "bus"
    ? "정차하는 정류장은 처음부터 선 위의 점(노선 색)으로 들어가 있어요. 점을 누르고 → 옮길 곳을 누르면 옮겨지고, 점을 고르지 않고 선 가까이를 누르면 점이 새로 생겨요. 회색 정류장 점을 누르면 선이 정류장에 딱 붙어요. 크게 확대할수록 섬세하게 고칠 수 있어요."
    : "지도에서 정류장이 실제로 있는 곳을 누르세요.";
  const drawMap = () => {
    if (!alive) return;
    if (kind === "bus") {
      MapView.lines([{ pts, color, width: 5 }]);
      const b = MapView.bounds();
      const inView = b ? pts.map((q, i) => [q, i]).filter(([q]) => q[0] >= b.s && q[0] <= b.n && q[1] >= b.w && q[1] <= b.e) : [];
      const dots = [];
      // 정류장 자리(회색)를 같이 보여 줘서 선이 정류장을 지나게 맞추기 쉽게
      // 정류장 점을 누르면 그 자리에 선을 연결 (화면 안 정류장이 많지 않을 때)
      // 선에 아직 안 붙은 정류장만 회색 (누르면 연결)
      const onLine = (s) => pts.some((q) => same(q, [s.lat, s.lng]));
      const stopsIn = b ? stops.filter((s) => s.lat != null && s.lat >= b.s && s.lat <= b.n && s.lng >= b.w && s.lng <= b.e && !onLine(s)) : [];
      if (MapView.zoom() >= 14 || stopsIn.length <= 120) stopsIn.forEach((s) => dots.push({ id: "s:" + stops.indexOf(s), lat: s.lat, lng: s.lng, color: "#9aa3b2", ring: "#fff", r: 6 }));
      // 선 위 점: 정류장 자리 점은 노선 색으로 크게, 고른 점은 빨강
      const isStop = (q) => stops.some((s) => s.lat != null && same(q, [s.lat, s.lng]));
      if (inView.length <= MAX_DOTS) for (const [q, i] of inView) {
        const st = isStop(q);
        dots.push({ id: "v:" + i, lat: q[0], lng: q[1], color: i === sel ? "#D7262E" : st ? color : "#fff", ring: i === sel ? "#fff" : st ? "#fff" : color, r: i === sel ? 8 : st ? 7 : 5 });
      }
      MapView.dots(dots, (d) => {
        if (d.id.startsWith("s:")) { const s = stops[+d.id.slice(2)]; if (s) tapAt([s.lat, s.lng], true); return; }
        if (!d.id.startsWith("v:")) return;
        const i = +d.id.slice(2); sel = sel === i ? -1 : i; drawAll();
      });
    } else {
      const lines = orig.length && pts.length && E.dist(orig[0], pts[0]) > 1 ? [{ pts: [orig[0], pts[0]], color: "#9aa3b2", width: 2, dash: true }] : [];
      MapView.lines(lines);
      const dots = [];
      if (lines.length) dots.push({ id: "o", lat: orig[0][0], lng: orig[0][1], color: "#9aa3b2", ring: "#fff", r: 6, label: "원래" });
      if (pts.length) dots.push({ id: "p", lat: pts[0][0], lng: pts[0][1], color: "#D7262E", ring: "#fff", r: 9, label: p.name || "정류장" });
      MapView.dots(dots, () => {});
    }
  };
  const drawBar = () => {
    if (!alive) return;
    const box = root.querySelector("#fixbar");
    if (!box) return;
    box.innerHTML = `${note(help)}${msg ? `<p class="note"><b>${esc(msg)}</b></p>` : ""}
      <div class="toolrow">
        ${kind === "bus" && sel >= 0 ? `<button class="link" ${act(() => { if (pts.length > 2) { pts.splice(sel, 1); sel = -1; drawAll(); } })}>고른 점 지우기</button>` : ""}
        ${kind === "bus" ? `<button class="link" ${act(() => { pts = E.passStops(E.simplify(orig, TOL_M), stops); sel = -1; msg = "자동으로 만든 모양으로 돌렸어요 (저장해야 반영돼요)"; drawAll(); })}>처음 모양으로</button>` : ""}
        ${hasEdit ? `<button class="link" ${act(() => reset())}>고친 것 지우기</button>` : ""}
        <button class="link" ${act(() => history.back())}>취소</button>
        <button class="bar-act" ${act(() => save())} ${busy ? "disabled" : ""}>${busy ? "저장 중..." : "저장"}</button>
      </div>`;
  };
  const drawAll = () => { drawMap(); drawBar(); };
  async function save() {
    if (busy || pts.length < (kind === "bus" ? 2 : 1)) return;
    busy = true; msg = ""; drawBar();
    try {
      await E.save(kind, p.id, kind === "bus" ? pts.map((q) => [+q[0].toFixed(5), +q[1].toFixed(5)]) : [+pts[0][0].toFixed(6), +pts[0][1].toFixed(6)]);
      hasEdit = true; msg = "저장했어요. 웹과 폰 앱에서 모두 이 모양으로 보여요.";
    } catch (e) { msg = e.message || "저장하지 못했어요"; }
    busy = false; drawBar();
  }
  async function reset() {
    if (busy) return;
    busy = true; drawBar();
    try { await E.save(kind, p.id, null); hasEdit = false; pts = kind === "bus" ? E.passStops(E.simplify(orig, TOL_M), stops) : orig.slice(); sel = -1; msg = "고친 것을 지웠어요. 원래 자료로 보여요."; }
    catch (e) { msg = e.message || "지우지 못했어요"; }
    busy = false; drawAll();
  }
  if (!E.isAdmin()) { root.innerHTML = header(title, null, { back: true }) + note("지도 고치기는 관리자만 할 수 있어요. 설정에서 관리자 키를 넣어 주세요."); return () => {}; }
  root.innerHTML = header(title, p.name || p.no || null, { back: true }) + `<div class="map-slot full"></div><div id="fixbar"></div>`;
  ctx.useMap(root.querySelector(".map-slot"));
  root.querySelector("#fixbar").innerHTML = note("불러오는 중...");
  /** 누른 곳에 점 옮기기·넣기. 정류장 가까이(손가락 크기)면 정류장 자리에 딱 붙임 */
  function tapAt(ll, onStop = false) {
    if (!ll || busy) return;
    if (kind === "stop") { pts = [ll]; msg = ""; drawAll(); return; }
    const b = MapView.bounds();
    const finger = b ? Math.max(2, Math.min(60, E.dist([b.s, b.w], [b.s, b.e]) * 0.02)) : 10;
    const snap = onStop ? ll : E.nearestStop(stops, ll, finger);
    const q = snap || ll;
    if (sel >= 0) { pts[sel] = q; sel = -1; }
    else E.insertNear(pts, q, snap ? 600 : 80);   // 정류장은 선에서 좀 떨어져 있어도 가까운 구간 사이에 넣음
    msg = snap ? "정류장에 연결했어요" : ""; drawAll();
  }
  MapView.onTap((ll) => tapAt(ll));
  const offIdle = MapView.onIdle(drawMap);
  (async () => {
    const all = await E.ready(true);
    if (!alive) return;
    if (kind === "stop") {
      const fix = all.stop[p.id];
      hasEdit = !!fix;
      // 원래 자리: 자료 조각(고친 것 적용 전) — 모르면 화면이 넘긴 좌표
      const raw = p.lat ? await B.rawStopPos(p.id, +p.lat, +p.lng).catch(() => null) : null;
      if (!alive) return;
      orig = raw ? [raw] : p.lat ? [[+p.lat, +p.lng]] : [];
      pts = fix ? [[fix[0], fix[1]]] : orig.slice();
      if (pts.length) MapView.view(pts[0][0], pts[0][1], 18);
      drawAll();
      return;
    }
    const r = await B.route(p.id, p.city).catch(() => null);
    if (!alive) return;
    if (!r) { root.querySelector("#fixbar").innerHTML = note("노선 정보를 받지 못했어요"); return; }
    stops = r.stops; color = busColor(r.type);
    root.querySelector(".bar-title b").textContent = (r.no !== "노선" ? r.no : p.no || "노선") + " 노선 모양 고치기";
    const auto = await B.shape(r.id, p.city || r.city, r.stops, r.no !== "노선" ? r.no : p.no, { noEdit: true });
    if (!alive) return;
    orig = auto && auto.length >= 2 ? auto : r.stops.filter((s) => s.lat != null).map((s) => [s.lat, s.lng]);
    const fix = all.bus[r.id] || all.bus[B.legacyId(r.id)];
    hasEdit = !!fix;
    // 정차하는 정류장은 처음부터 선 위의 점으로
    pts = E.passStops(fix ? B.decodePolyline(String(fix), 5) : E.simplify(orig, TOL_M), stops);
    if (pts.length) MapView.fit(pts);
    drawAll();
  })();
  return () => { alive = false; offIdle(); MapView.onTap(null); };
}
