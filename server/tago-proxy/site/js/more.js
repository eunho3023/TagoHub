// 대중교통 탭(수단 칩), 길찾기 탭, 설정, 공지사항.
import { BASE, getText, getJson, esc } from "./core.js";
import { act, header, chips, note, sectionHead } from "./ui.js";
import { go } from "./nav.js";
import { Theme } from "./store.js";
import { busSearch } from "./bus.js";
import * as Edits from "./edits.js";
import * as Account from "./account.js";
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
  root.innerHTML = header("타볼까", MODES.find(([k]) => k === mode)[1]) + chipsHtml + note("준비 중이에요");
  return null;
}

export function navi(root, p, ctx) { return directionsScreen(root, p, ctx); }

// ── 설정 ──
const ISSUES = "https://github.com/eunho3023/TagoHub/issues/new?title=" + encodeURIComponent("[웹 제보] ");
/** 몇 분 전 / 몇 시간 전 */
function ago(ms) {
  const m = Math.max(0, Math.round((Date.now() - ms) / 60000));
  return m < 1 ? "방금" : m < 60 ? m + "분 전" : m < 1440 ? Math.round(m / 60) + "시간 전" : Math.round(m / 1440) + "일 전";
}
export function settings(root) {
  let adminMsg = "";
  // 자료 연결 상태: 서버가 30분마다 각 제공처(TAGO·서울·경기·전주·제주·카카오)에 작은 요청을 보내 본 결과 (서버 src/health.js)
  let health = null, healthFail = false;
  if (Edits.isAdmin()) getJson("w/health").then((v) => { health = v; draw(); }).catch(() => { healthFail = true; draw(); });
  const healthHtml = () => {
    if (healthFail) return sectionHead("자료 연결 상태") + note("상태를 받지 못했어요");
    if (!health) return sectionHead("자료 연결 상태") + note("확인 중...");
    const list = (health.checks || []).filter((c) => c.ok !== null);
    if (!list.length) return sectionHead("자료 연결 상태") + note("아직 확인한 기록이 없어요 (30분마다 확인해요)");
    const bad = list.filter((c) => !c.ok).length;
    return sectionHead("자료 연결 상태") + `<div class="list">${list.map((c) => `<div class="row"><span class="grow"><b>${c.ok ? "✅" : "⚠️"} ${esc(c.label)}</b><small>${c.ok ? "정상" : esc(c.msg || "고장") + (c.failSince ? " · " + ago(c.failSince) + "부터" : "")}</small></span></div>`).join("")}</div>` +
      note((bad ? `${bad}곳이 지금 잘 안 돼요. 그곳 정보는 늦거나 보이지 않을 수 있어요. ` : "모든 제공처와 잘 연결돼 있어요. ") + (health.at ? ago(health.at) + " 확인 · 30분마다 확인해요" : ""));
  };
  // 관리자 키: 맞는 키를 넣은 브라우저에서만 노선 모양·정류장 위치 고치기 단추가 보임 (키는 배포한 컴퓨터의 local.properties EDIT_ADMIN_KEY)
  // 아이디 계정: 폰 앱과 같은 아이디. 개발자 아이디면 지도 고치기
  const accountHtml = () => {
    const a = Account.current();
    return sectionHead("계정") + (a
      ? `<div class="list"><div class="row"><span class="grow"><b>아이디: ${esc(a.username)}${a.dev ? " · 개발자" : ""}</b><small>${a.dev ? "노선·정류장 화면에서 지도 고치기를 할 수 있어요" : "폰 앱과 같은 아이디로 로그인했어요"}</small></span>${a.dev ? `<button class="link" ${act(() => go("fixstops"))}>지도 고치기</button>` : ""}<button class="link" ${act(() => { Account.logout(); draw(); })}>로그아웃</button></div></div>`
      : `<div class="list"><button class="row" ${act(() => go("login"))}><span class="grow"><b>아이디로 로그인·회원가입</b><small>폰 앱과 같은 아이디를 써요</small></span></button></div>`);
  };
  // 관리자 칸: 관리자로 로그인했을 때만 (관리자 아이디 로그인이면 키 없이 고칠 수 있음. 예전에 넣은 관리자 키가 있으면 끄기)
  const adminHtml = () => (!Edits.isAdmin() ? "" : sectionHead("관리자") +
    `<div class="list"><div class="row"><span class="grow"><b>관리자 모드 켜짐</b><small>노선·정류장·역·자전거·주차장·편의시설 점에서 지도 고치기를 할 수 있어요</small></span><button class="link" ${act(() => go("fixstops"))}>지도 고치기 열기</button>${Edits.hasAdminKey() ? `<button class="link" ${act(async () => { await Edits.setAdminKey(""); adminMsg = "관리자 키를 지웠어요"; draw(); })}>키 지우기</button>` : ""}</div></div>` +
    (adminMsg ? note(adminMsg) : ""));
  const draw = () => {
    root.innerHTML = header("설정") + sectionHead("화면 모드") +
      `<div class="seg">${[["auto", "기기 설정"], ["light", "밝게"], ["dark", "어둡게"]].map(([m, l]) => `<button class="${Theme.mode === m ? "on" : ""}" ${act(() => { Theme.set(m); draw(); })}>${l}</button>`).join("")}</div>` +
      sectionHead("정보") +
      `<div class="list"><button class="row" ${act(() => go("notices"))}><b class="grow">공지사항</b></button>
       <button class="row" ${act(() => go("report"))}><span class="grow"><b>앱 제보</b><small>불편한 점이나 틀린 정보를 알려 주세요</small></span></button></div>` +
      accountHtml() +
      // 자료 연결 상태는 관리자만 (일반 사용자에게는 필요 없는 정보)
      (Edits.isAdmin() ? healthHtml() : "") +
      adminHtml() +
      sectionHead("약관 및 정책") +
      `<div class="list">${[["legal/terms.html", "이용약관"], ["legal/privacy.html", "개인정보 처리방침"], ["legal/sources.html", "정보 제공처", "교통 자료·지도를 제공하는 기관"], ["legal/licenses.html", "오픈소스 라이선스"]]
        .map(([h, t, s]) => `<a class="row" href="${h}"><span class="grow"><b>${t}</b>${s ? `<small>${s}</small>` : ""}</span></a>`).join("")}</div>` +
      note("웹 버전 · 폰 앱과 같은 공공 자료를 씁니다. 즐겨찾기·집·회사·최근 기록은 이 기기(브라우저)에만 저장돼요. 교통 정보는 제공처 사정으로 늦거나 실제와 다를 수 있어요.");
  };
  draw();
  return Account.onChange(draw);
}

// ── 앱 제보: 폰 앱과 같은 곳(Firebase, 서버 /w/report)으로. 못 보내면 깃허브 이슈로 ──
const KINDS = ["오류", "추가 요청", "삭제 요청"];
const report = { kind: "오류", text: "" };
export function reportScreen(root) {
  let sending = false, result = null;
  const draw = () => {
    root.innerHTML = header("앱 제보", "불편한 점이나 틀린 정보를 알려 주세요", { back: true }) +
      `<div class="segs">${KINDS.map((k) => `<button class="${report.kind === k ? "on" : ""}" ${act(() => { report.kind = k; draw(); })}>${k}</button>`).join("")}</div>
      <div class="searchbox"><textarea id="rt" rows="7" maxlength="2000" placeholder="내용 (어디서, 무엇이)">${esc(report.text)}</textarea>
      <div class="flexrow"><small class="grow">${report.text.length} / 2000</small><button class="btn" ${sending ? "disabled" : ""} ${act(send)}>${sending ? "보내는 중..." : "보내기"}</button></div></div>` +
      (result === "ok" ? note("보냈어요. 고맙습니다!") : result === "fail" ? `<p class="note">보내지 못했어요. <a class="blue" href="${ISSUES}${encodeURIComponent(report.kind)}&body=${encodeURIComponent(report.text)}" target="_blank" rel="noopener">깃허브로 보내기</a></p>` : "") +
      note("제보에는 내용과 브라우저 종류만 함께 보내요.");
    const ta = root.querySelector("#rt");
    ta.oninput = () => { report.text = ta.value.slice(0, 2000); const c = root.querySelector(".searchbox small"); if (c) c.textContent = report.text.length + " / 2000"; };
  };
  async function send() {
    if (sending) return;
    if (!report.text.trim()) { result = null; draw(); root.querySelector("#rt")?.focus(); return; }
    sending = true; result = null; draw();
    try {
      const r = await fetch(BASE + "w/report", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ kind: report.kind, text: report.text, device: (navigator.userAgentData?.brands?.map((b) => b.brand).filter((b) => !/Not/.test(b)).join(" ") || navigator.userAgent).slice(0, 120) }) });
      result = r.ok && (await r.json()).ok ? "ok" : "fail";
    } catch (e) { result = "fail"; }
    sending = false;
    if (result === "ok") report.text = "";
    draw();
  }
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
    root.innerHTML = header("공지사항", null, { back: true, action: { label: "제보하기", run: () => go("report") } }) +
      (notices == null ? note("공지사항을 불러오는 중...") : !notices.length ? note("공지사항이 없어요") :
        `<div class="list">${notices.map((n) => `<div class="row col"><b>${esc(n.title)}</b>${n.date ? `<small>${esc(n.date)}</small>` : ""}${n.body ? `<p>${esc(n.body)}</p>` : ""}</div>`).join("")}</div>`);
  };
  draw();
  noticesP.then(draw);
}
export { noticesScreen as notices };
