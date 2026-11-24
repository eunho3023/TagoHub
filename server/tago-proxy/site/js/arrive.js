// 도착 알림: 정류장에서 고른 노선 버스가 5분(3정류장) 안으로 오면 한 번, 90초(1정류장) 안이면 한 번 더 알림.
// 소리로 읽고 진동, 브라우저 알림을 허락했으면 알림도. 탭을 켜 둔 동안만 15초마다 도착 정보를 봄. 곧 도착을 알린 뒤 3분이면 저절로 끔
import { esc } from "./core.js";
import { act } from "./ui.js";
import * as B from "./busdata.js";

const POLL_MS = 15000, DONE_AFTER_MS = 180000;
let job = null;
const banner = () => document.getElementById("arrive");

function say(text) {
  try { if (navigator.vibrate) navigator.vibrate([400, 200, 400]); } catch (e) { /* 없음 */ }
  try { const u = new SpeechSynthesisUtterance(text); u.lang = "ko-KR"; speechSynthesis.cancel(); speechSynthesis.speak(u); } catch (e) { /* 없음 */ }
  try { if ("Notification" in window && Notification.permission === "granted") new Notification("타볼까 도착 알림", { body: text, tag: "tagohub-arrive" }); } catch (e) { /* 없음 */ }
}

function draw() {
  const b = banner();
  if (!b) return;
  if (!job) { b.hidden = true; b.innerHTML = ""; return; }
  b.hidden = false;
  b.innerHTML = `<div class="grow"><b>도착 알림 · ${esc(job.route.no)}번 · ${esc(job.stop.name)}</b>${job.msg ? `<small>${esc(job.msg)}</small>` : ""}</div>
    <button ${act(() => Arrive.stop())}>끄기</button>`;
}

const sameRoute = (a, r) => (a.routeId && r.id ? a.routeId === r.id : a.routeNo === r.no);

async function tick() {
  const j = job;
  if (!j) return;
  if (j.doneAt && Date.now() - j.doneAt > DONE_AFTER_MS) { Arrive.stop(); return; }
  const got = await B.arrivals(j.stop).catch(() => null);
  if (job !== j) return;
  if (!got || got.state !== "ok") { j.msg = "도착 정보를 다시 받는 중..."; draw(); return; }
  const a = got.list.filter((x) => sameRoute(x, j.route) && !x.waiting).sort((x, y) => (x.arrSeconds ?? 1e9) - (y.arrSeconds ?? 1e9))[0];
  const stage = B.arriveStage(a);
  if (stage > j.stage) {
    j.stage = stage;
    const when = B.arrivalParts(a, 0).slice(0, 2).join(", ");
    say(stage === 2 ? `${j.route.no}번 버스가 곧 도착합니다.` : `${j.route.no}번 버스가 ${when} 남았습니다. 나갈 준비를 하세요.`);
    if (stage === 2) j.doneAt = Date.now();
  }
  j.msg = a ? B.arrivalParts(a, 0).join(" · ") + (j.stage ? "" : " · 5분 전에 알려 드려요") : "지금 오는 버스가 없어요. 오면 알려 드려요";
  draw();
}

export const Arrive = {
  supported: typeof window !== "undefined",
  isTarget(stopId, route) { return !!job && job.stop.id === stopId && job.route.id === route.id; },
  start(stop, route) {
    this.stop();
    try { if ("Notification" in window && Notification.permission === "default") Notification.requestPermission().catch(() => {}); } catch (e) { /* 없음 */ }
    job = { stop, route, stage: 0, msg: "도착 정보를 받는 중...", doneAt: 0, timer: setInterval(tick, POLL_MS) };
    draw();
    tick();
  },
  stop() {
    if (job) clearInterval(job.timer);
    job = null;
    draw();
  },
};
