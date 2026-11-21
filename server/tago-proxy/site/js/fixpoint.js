// 지도 고치기: 점 하나 위치 옮기기 (관리자). 지하철역(station)·공영자전거 대여소·주차장·편의시설(place) 점.
// 정류장은 fixstops.js (여러 곳을 한 번에). 지도에서 새 자리를 누르고 저장하면 웹·폰 앱 모두에 보임 (서버 /w/edits).
// #/fixpoint/station?keys=열쇠|열쇠&lat=&lng=&name=   (환승역은 노선마다 열쇠가 따로라 모두 같은 자리로)
// #/fixpoint/place?pid=bike:…&lat=&lng=&name=
import { esc } from "./core.js";
import { act, header, note } from "./ui.js";
import { MapView } from "./map.js";
import * as E from "./edits.js";

export function fixPointScreen(root, p, ctx) {
  const title = "위치 고치기";
  if (!E.isAdmin()) { root.innerHTML = header(title, null, { back: true }) + note("지도 고치기는 개발자만 할 수 있어요. 설정에서 개발자 아이디로 로그인하거나 관리자 키를 넣어 주세요."); return () => {}; }
  const kind = p.id === "station" ? "station" : "place";
  const ids = String((kind === "station" ? p.keys : p.pid) || "").split("|").filter(Boolean);
  const name = String(p.name || "");
  const from = [+p.lat, +p.lng];
  let to = null, busy = false, msg = "";
  const fixedNow = () => ids.some((k) => (kind === "station" ? E.stationFix(k) : E.placeFix(k)));

  root.innerHTML = header(title, name || null, { back: true }) + `<div class="map-slot full"></div><div id="fixbar"></div>`;
  ctx.useMap(root.querySelector(".map-slot"));
  if (Number.isFinite(from[0])) MapView.view(from[0], from[1], 18);

  const drawMap = () => {
    const dots = [{ id: "from", lat: from[0], lng: from[1], color: to ? "#9aa3b2" : "#D7262E", ring: "#fff", r: 9, label: to ? null : name, labelUp: !to }];
    if (to) dots.push({ id: "to", lat: to[0], lng: to[1], color: "#E08A00", ring: "#fff", r: 9, label: name, labelUp: true });
    MapView.lines(to ? [{ pts: [from, to], color: "#9aa3b2", width: 2, dash: true }] : []);
    MapView.dots(dots, () => {});
  };
  const drawBar = () => {
    const box = root.querySelector("#fixbar");
    if (!box) return;
    let h = note(to ? `새 자리를 골랐어요. 저장을 눌러야 반영돼요.` : `지도에서 「${esc(name || "이 점")}」의 새 자리를 누르세요.`);
    if (!ids.length) h = note("고칠 점을 알 수 없어요.");
    if (msg) h += `<p class="note"><b>${esc(msg)}</b></p>`;
    h += `<div class="toolrow">${fixedNow() ? `<button class="link" ${act(() => reset())} ${busy ? "disabled" : ""}>원래 위치로</button>` : ""}<span class="grow"></span>` +
      (to ? `<button class="link" ${act(() => { to = null; msg = ""; drawAll(); })}>취소</button>` : "") +
      `<button class="bar-act" ${act(() => saveIt())} ${busy || !to || !ids.length ? "disabled" : ""}>${busy ? "저장 중..." : "저장"}</button></div>`;
    box.innerHTML = h;
  };
  const drawAll = () => { drawMap(); drawBar(); };

  async function saveIt() {
    if (!to || busy) return;
    busy = true; msg = ""; drawBar();
    try {
      for (const k of ids) await E.save(kind, k, [+to[0].toFixed(6), +to[1].toFixed(6)]);
      from[0] = to[0]; from[1] = to[1]; to = null;
      msg = "저장했어요. 웹·폰 앱 지도에 바로 보여요.";
    } catch (e) { msg = e.message || "저장하지 못했어요"; }
    busy = false; drawAll();
  }
  async function reset() {
    if (busy) return;
    busy = true; msg = ""; drawBar();
    try { for (const k of ids) await E.save(kind, k, null); to = null; msg = "고친 위치를 지웠어요. 원래 자료 위치로 보여요."; }
    catch (e) { msg = e.message || "지우지 못했어요"; }
    busy = false; drawAll();
  }

  MapView.onTap((ll) => { if (!ll || busy || !ids.length) return; to = ll; msg = ""; drawAll(); });
  MapView.ready.then(drawAll);
  return () => { MapView.onTap(null); MapView.lines([]); };
}
