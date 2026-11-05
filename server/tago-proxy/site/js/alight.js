// 하차 알림: 내릴 정류장에 다가가는 것을 브라우저 위치로 지켜보다가 800m·350m·90m 에서 읽어 주고 진동, 지나치면 알림
// (폰 앱 AlightTracker 와 같은 규칙). 탭을 켜 둔 동안만 동작.
import { meters, distText, esc } from "./core.js";
import { act } from "./ui.js";
import { locate, geoMessage } from "./geo.js";

const HEADS = 800, SOON = 350, NOW = 90, MAX_ACC = 80, PASSED_EXTRA = 250;
const STAGES = ["watching", "heads", "soon", "now", "passed"];

function batchim(w) { const c = w.charCodeAt(w.length - 1); return c >= 0xac00 && c <= 0xd7a3 && (c - 0xac00) % 28 !== 0; }

export class Tracker {
  constructor(name, lat, lng) { Object.assign(this, { name, lat, lng }); this.stage = 0; this.min = Infinity; this.done = false; }
  get label() { return /정류[장소]$/.test(this.name) ? this.name : this.name + " 정류장"; }
  update(lat, lng, acc = 0) {
    const d = meters(lat, lng, this.lat, this.lng);
    if (acc > MAX_ACC) return { d, speak: null, vibrate: false, finished: this.done };
    this.min = Math.min(this.min, d);
    const reached = d <= NOW ? 3 : d <= SOON ? 2 : d <= HEADS ? 1 : 0;
    let speak = null, vibrate = false;
    if (this.stage >= 2 && this.stage !== 4 && d > Math.max(NOW * 2.5, this.min + PASSED_EXTRA)) {
      this.stage = 4; this.done = true; vibrate = true;
      speak = `${this.label}${batchim(this.label) ? "을" : "를"} 지나쳤습니다. 다음 정류장에서 내려 되돌아가세요.`;
    } else if (this.stage !== 4 && reached > this.stage) {
      this.stage = reached;
      if (reached === 1) speak = `${this.label}까지 약 ${distText(d)} 남았습니다.`;
      if (reached === 2) { speak = `곧 ${this.label}입니다. 내릴 준비를 하세요.`; vibrate = true; }
      if (reached === 3) { speak = `${this.label}입니다. 내리세요.`; vibrate = true; }
    }
    return { d, speak, vibrate, finished: this.done, stage: STAGES[this.stage] };
  }
}

function announce(text, vib) {
  try { if (vib && navigator.vibrate) navigator.vibrate([400, 200, 400]); } catch (e) { /* 없음 */ }
  try { const u = new SpeechSynthesisUtterance(text); u.lang = "ko-KR"; speechSynthesis.cancel(); speechSynthesis.speak(u); } catch (e) { /* 없음 */ }
}

let tracker = null, watch = null, dist = null, msg = null;
const banner = () => document.getElementById("alight");

function drawBanner() {
  const b = banner();
  if (!b) return;
  if (!tracker) { b.hidden = true; b.innerHTML = ""; return; }
  b.hidden = false;
  b.innerHTML = `<div class="grow"><b>하차 알림 · ${esc(tracker.label)}${dist != null ? "  " + distText(dist) : ""}</b>${msg ? `<small>${esc(msg)}</small>` : ""}</div>
    <button ${act(() => Alight.stop())}>끄기</button>`;
}

export const Alight = {
  supported: "geolocation" in navigator,
  isTarget(name, lat, lng) { return !!tracker && tracker.name === name && tracker.lat === lat && tracker.lng === lng; },
  start(name, lat, lng) {
    this.stop();
    const t = (tracker = new Tracker(name, lat, lng));
    msg = "위치를 받는 중이에요. 이 화면을 켜 두세요.";
    drawBanner();
    watch = navigator.geolocation.watchPosition(
      (p) => {
        if (tracker !== t) return;
        const u = t.update(p.coords.latitude, p.coords.longitude, p.coords.accuracy || 0);
        dist = u.d;
        if (u.speak) { msg = u.speak; announce(u.speak, u.vibrate); }
        if (u.finished && watch != null) { navigator.geolocation.clearWatch(watch); watch = null; }
        drawBanner();
      },
      (e) => { if (tracker === t) geoMessage(e).then((m) => { if (tracker === t) { msg = m; drawBanner(); } }); },
      { enableHighAccuracy: true, maximumAge: 5000, timeout: 30000 },
    );
  },
  stop() {
    if (watch != null) navigator.geolocation.clearWatch(watch);
    watch = null; tracker = null; dist = null; msg = null;
    drawBanner();
  },
  redraw: drawBanner,
};

/** 지금 위치 한 번 (못 받으면 null) */
export function currentLocation() {
  return locate().then((p) => [p[0], p[1]], () => null);
}
