// 대중교통 탭(수단 칩), 길찾기 탭, 설정, 공지사항.
import { getText, esc } from "./core.js";
import { act, header, chips, note, sectionHead } from "./ui.js";
import { go } from "./nav.js";
import { Theme } from "./store.js";
import { busSearch } from "./bus.js";
import { directionsScreen } from "./places.js";

// ── 대중교통 탭: 버스·지하철·기차·고속시외·항공·도로 ──
export const MODES = [
  ["bus", "버스"], ["subway", "지하철"], ["train", "기차"], ["coach", "고속·시외"], ["air", "항공"], ["road", "도로"],
];
/** 다른 모듈이 수단 화면을 넣음 (subway 등) */
export const modeScreens = {};
let mode = "bus";

export function transit(root, p, ctx) {
  if (p.id && MODES.some(([k]) => k === p.id)) mode = p.id;
  const chipsHtml = chips(MODES.map(([k, l]) => ({ label: l, on: k === mode, run: () => { location.hash = "#/transit/" + k; } })));
  if (mode === "bus") return busSearch(root, chipsHtml);
  const fn = modeScreens[mode];
  if (fn) return fn(root, chipsHtml, ctx);
  root.innerHTML = header("TagoHub", MODES.find(([k]) => k === mode)[1]) + chipsHtml + note("준비 중이에요");
  return null;
}

export function navi(root, p, ctx) { return directionsScreen(root, p, ctx); }

// ── 설정 ──
const ISSUES = "https://github.com/eunho3023/TagoHub/issues/new?title=" + encodeURIComponent("[웹 제보] ");
export function settings(root) {
  const draw = () => {
    root.innerHTML = header("설정") + sectionHead("화면 모드") +
      `<div class="seg">${[["auto", "기기 설정"], ["light", "밝게"], ["dark", "어둡게"]].map(([m, l]) => `<button class="${Theme.mode === m ? "on" : ""}" ${act(() => { Theme.set(m); draw(); })}>${l}</button>`).join("")}</div>` +
      sectionHead("정보") +
      `<div class="list"><button class="row" ${act(() => go("notices"))}><b class="grow">공지사항</b></button>
       <a class="row" href="${ISSUES}" target="_blank" rel="noopener"><span class="grow"><b>앱 제보</b><small>불편한 점이나 틀린 정보를 알려 주세요</small></span></a></div>` +
      note("웹 버전 · 폰 앱과 같은 공공 자료를 씁니다. 즐겨찾기·집·회사·최근 기록은 이 기기(브라우저)에만 저장돼요.");
  };
  draw();
}

// ── 공지사항 (저장소 notices.json, 폰 앱과 같은 파일) ──
let notices = null;
const noticesP = getText("https://raw.githubusercontent.com/eunho3023/TagoHub/main/notices.json")
  .then((t) => { notices = (JSON.parse(t) || []).filter((n) => n && n.title).sort((a, b) => (b.date || "").localeCompare(a.date || "")); return notices; })
  .catch(() => (notices = []));
export const latestNotice = () => (notices && notices[0]) || null;

export function noticesScreen(root) {
  const draw = () => {
    root.innerHTML = header("공지사항", null, { back: true, action: { label: "제보하기", run: () => window.open(ISSUES, "_blank", "noopener") } }) +
      (notices == null ? note("공지사항을 불러오는 중...") : !notices.length ? note("공지사항이 없어요") :
        `<div class="list">${notices.map((n) => `<div class="row col"><b>${esc(n.title)}</b>${n.date ? `<small>${esc(n.date)}</small>` : ""}${n.body ? `<p>${esc(n.body)}</p>` : ""}</div>`).join("")}</div>`);
  };
  draw();
  noticesP.then(draw);
}
export { noticesScreen as notices };
