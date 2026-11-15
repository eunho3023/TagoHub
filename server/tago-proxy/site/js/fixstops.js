// 지도 고치기 창 (관리자): 화면 안 정류장을 모두 띄우고, 점을 누른 뒤 새 자리를 누르면 옮김. 옮긴 것은 모아서 한 번에 저장.
// 고른 정류장을 지나는 노선의 "노선 모양 고치기"로도 바로 감. 저장하면 웹·폰 앱 모두에 보임 (서버 /w/edits).
// #/fixstops?lat=&lng=   (좌표를 주면 그 자리로)
import { esc } from "./core.js";
import { act, header, note } from "./ui.js";
import { MapView } from "./map.js";
import { go } from "./nav.js";
import * as B from "./busdata.js";
import * as E from "./edits.js";

const MIN_ZOOM = 15;

export function fixStopsScreen(root, p, ctx) {
  if (!E.isAdmin()) { root.innerHTML = header("지도 고치기", null, { back: true }) + note("지도 고치기는 관리자만 할 수 있어요. 설정에서 관리자 키를 넣어 주세요."); return () => {}; }
  let alive = true, stops = [], sel = null, busy = false, msg = "", routes = null, timer = null, preset = false;
  /** 정류장 id → {orig:[위도,경도], to:[위도,경도], name} (아직 저장 안 함) */
  const moves = new Map();
  root.innerHTML = header("지도 고치기", "정류장 위치·노선 모양", { back: true }) + `<div class="map-slot full"></div><div id="fixbar"></div>`;
  ctx.useMap(root.querySelector(".map-slot"));
  if (p.lat) MapView.view(+p.lat, +p.lng, 17);

  const posOf = (s) => moves.get(s.id)?.to || [s.lat, s.lng];
  const drawMap = () => {
    if (!alive) return;
    const lines = [], dots = [];
    for (const [, m] of moves) lines.push({ pts: [m.orig, m.to], color: "#9aa3b2", width: 2, dash: true });
    for (const s of stops) {
      const at = posOf(s), on = sel && sel.id === s.id, moved = moves.has(s.id);
      dots.push({ id: s.id, lat: at[0], lng: at[1], color: on ? "#D7262E" : moved ? "#E08A00" : "#2563eb", ring: "#fff", r: on ? 9 : 6, label: on ? s.name : null, labelUp: on });
    }
    MapView.lines(lines);
    MapView.dots(dots, (d) => {
      const s = stops.find((x) => x.id === d.id);
      if (!s) return;
      sel = sel && sel.id === s.id ? null : s; routes = null; msg = "";
      drawAll();
      if (sel) loadRoutes(sel);
    });
  };
  async function loadRoutes(s) {
    const sums = await B.routeSums(s.routes || []).catch(() => ({}));
    if (!alive || sel !== s) return;
    routes = (s.routes || []).map((id) => ({ id, no: sums[id]?.no || id })).sort((a, b) => String(a.no).localeCompare(String(b.no), "ko", { numeric: true })).slice(0, 30);
    drawBar();
  }
  const drawBar = () => {
    if (!alive) return;
    const box = root.querySelector("#fixbar");
    if (!box) return;
    const zoomOk = MapView.zoom() >= MIN_ZOOM;
    let h = note(!zoomOk ? "지도를 더 확대하면 정류장이 모두 보여요." : sel ? `「${sel.name}」 고름 → 지도에서 새 위치를 누르면 옮겨져요.` : "정류장 점을 누르고 → 지도에서 새 위치를 누르면 옮겨져요. 여러 곳을 옮긴 뒤 한 번에 저장해요.");
    if (msg) h += `<p class="note"><b>${esc(msg)}</b></p>`;
    if (sel) {
      const fixed = E.stopFix(sel.id);
      h += `<div class="toolrow">
        ${moves.has(sel.id) ? `<button class="link" ${act(() => { moves.delete(sel.id); drawAll(); })}>이 정류장 되돌리기</button>` : ""}
        ${fixed ? `<button class="link" ${act(() => resetStop(sel))}>고친 위치 지우기 (원래 자료로)</button>` : ""}
        <button class="link" ${act(() => { sel = null; routes = null; drawAll(); })}>고르기 취소</button></div>`;
      if (routes && routes.length) h += `<p class="note">노선 모양 고치기:</p><div class="toolrow">${routes.map((r) => `<button class="link" ${act(() => go("fix", r.id, { kind: "bus", no: r.no, name: r.no }))}>${esc(r.no)}</button>`).join("")}</div>`;
    }
    h += `<div class="toolrow"><span class="grow muted">${moves.size ? `${moves.size}곳 옮김 (저장 전)` : ""}</span>
      ${moves.size ? `<button class="link" ${act(() => { moves.clear(); sel = null; drawAll(); })}>모두 취소</button>` : ""}
      <button class="bar-act" ${act(() => saveAll())} ${busy || !moves.size ? "disabled" : ""}>${busy ? "저장 중..." : "저장"}</button></div>`;
    box.innerHTML = h;
  };
  const drawAll = () => { drawMap(); drawBar(); };

  async function saveAll() {
    if (busy || !moves.size) return;
    busy = true; msg = ""; drawBar();
    let done = 0;
    try {
      for (const [id, m] of [...moves]) {
        await E.save("stop", id, [+m.to[0].toFixed(6), +m.to[1].toFixed(6)]);
        moves.delete(id); done++;
      }
      msg = `${done}곳 저장했어요. 웹과 폰 앱에서 모두 새 위치로 보여요.`;
    } catch (e) { msg = (done ? `${done}곳 저장, ` : "") + (e.message || "저장하지 못했어요"); }
    busy = false; sel = null; load(); drawAll();
  }
  async function resetStop(s) {
    if (busy) return;
    busy = true; drawBar();
    try { await E.save("stop", s.id, null); moves.delete(s.id); msg = "고친 위치를 지웠어요. 원래 자료 위치로 보여요."; sel = null; }
    catch (e) { msg = e.message || "지우지 못했어요"; }
    busy = false; load(); drawAll();
  }
  /** 화면 안 정류장 다시 읽기 (고친 위치가 들어간 자료 조각) */
  function load() {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      const b = MapView.bounds();
      if (!b || !alive) return;
      await E.ready();
      const got = MapView.zoom() < MIN_ZOOM ? [] : await B.stopsIn(b.s, b.w, b.n, b.e, 1500).catch(() => stops);
      if (!alive) return;
      // 정류장 화면에서 왔으면 그 정류장을 골라 둠 (처음 한 번)
      if (p.id && !preset) { preset = true; sel = got.find((x) => x.id === p.id || B.legacyId(x.id) === B.legacyId(p.id)) || null; if (sel) loadRoutes(sel); }
      // 고른 정류장은 화면 밖으로 나가도 남김
      stops = sel && !got.some((x) => x.id === sel.id) ? [...got, sel] : got;
      drawAll();
    }, 250);
  }
  MapView.onTap((ll) => {
    if (!ll || busy || !sel) return;
    const orig = moves.get(sel.id)?.orig || [sel.lat, sel.lng];
    moves.set(sel.id, { orig, to: ll, name: sel.name });
    msg = `「${sel.name}」을(를) 옮겼어요 (저장을 눌러야 반영돼요)`;
    sel = null; routes = null;
    drawAll();
  });
  const offIdle = MapView.onIdle(load);
  const offEdit = E.onChange(load);
  MapView.ready.then(load);
  drawBar();
  return () => { alive = false; clearTimeout(timer); offIdle(); offEdit(); MapView.onTap(null); };
}
