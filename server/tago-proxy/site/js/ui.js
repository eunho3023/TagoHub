// 화면 만들기 도우미: 글자 끼우기(esc), 누를 곳 묶기(act), 머리띠, 아이콘, 탭 칩.
import { esc } from "./core.js";

/** 이번에 그리는 화면의 누를 곳들 (data-act="n" → 함수) */
let acts = new Map(), actSeq = 0;
export function act(fn) { const k = "a" + ++actSeq; acts.set(k, fn); return `data-act="${k}"`; }
export function resetActs() { acts = new Map(); }
document.addEventListener("click", (e) => {
  const t = e.target.closest("[data-act]");
  if (!t) return;
  const f = acts.get(t.dataset.act);
  if (f) { e.preventDefault(); e.stopPropagation(); f(e, t); }
});

export { esc };

const ICONS = {
  home: '<path d="M3 11 12 3.5 21 11"/><path d="M5.5 9.5V20h13V9.5"/><path d="M10 20v-6h4v6"/>',
  work: '<rect x="3" y="7" width="18" height="13" rx="2.5"/><path d="M9 7V4.5h6V7M3 12.5h18"/>',
  star: '<path d="M12 3l2.6 5.6 6.1.7-4.5 4.2 1.2 6L12 16.6 6.6 19.5l1.2-6L3.3 9.3l6.1-.7z"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/>',
  bus: '<rect x="5" y="3" width="14" height="15" rx="3"/><path d="M5 11h14M7.5 18v3M16.5 18v3"/><circle cx="8.5" cy="14.5" r="1"/><circle cx="15.5" cy="14.5" r="1"/>',
  map: '<path d="M3 6l6-2.5 6 2.5 6-2.5V18l-6 2.5-6-2.5-6 2.5z"/><path d="M9 3.5V18M15 6v14.5"/>',
  route: '<path d="M12 2.5 21.5 12 12 21.5 2.5 12z"/><path d="M9 15v-4h5.5M12.5 9l2 2-2 2"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1"/><circle cx="12" cy="12" r="7"/>',
  pin: '<path d="M12 21s-6-6.2-6-11a6 6 0 0 1 12 0c0 4.8-6 11-6 11z"/><circle cx="12" cy="10" r="2.2"/>',
  back: '<path d="M15 5 8 12l7 7"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
};
export function icon(name, size = 22) {
  return `<svg class="ic" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${ICONS[name] || ""}</svg>`;
}

/** 위쪽 남색 머리띠 */
export function header(title, sub, { back = false, action = null, star = null } = {}) {
  return `<header class="bar">
    ${back ? `<button class="bar-back" ${act(() => history.back())} aria-label="뒤로">${icon("back", 24)}</button>` : ""}
    <div class="bar-title"><b>${esc(title)}</b>${sub ? `<small>${esc(sub)}</small>` : ""}</div>
    ${star ? `<button class="bar-star ${star.on ? "on" : ""}" ${act(star.toggle)} aria-label="즐겨찾기">${star.on ? "★" : "☆"}</button>` : ""}
    ${action ? `<button class="bar-act" ${act(action.run)}>${esc(action.label)}</button>` : ""}
  </header>`;
}

/** 알약 모양 칩 줄 [{label, on, run}] */
export function chips(list) {
  return `<div class="chips">${list.map((c) => `<button class="chip ${c.on ? "on" : ""}" ${act(c.run)}>${esc(c.label)}</button>`).join("")}</div>`;
}

export const note = (t) => `<p class="note">${esc(t)}</p>`;
export const sectionHead = (t, clear) => `<div class="shead"><span>${esc(t)}</span>${clear ? `<button ${act(clear)}>지우기</button>` : ""}</div>`;

/** 입력칸 (글자가 바뀔 때마다 onInput, 잠깐 멈춘 뒤) */
export function bindInput(root, sel, onInput, wait = 250) {
  const inp = root.querySelector(sel);
  if (!inp) return null;
  let t = null;
  inp.addEventListener("input", () => { clearTimeout(t); t = setTimeout(() => onInput(inp.value), wait); });
  return inp;
}
