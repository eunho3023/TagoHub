// 아이디 로그인·회원가입·아이디 찾기·비밀번호 찾기 화면 (따로 된 화면: #/login, #/login/signup, #/login/findid, #/login/reset).
// 브라우저 뒤로 가기로 앞 화면에 돌아감. 계정 처리는 account.js
import { esc, USER_SITE } from "./core.js";
import { act, header } from "./ui.js";
import { go } from "./nav.js";
import * as Edits from "./edits.js";
import { MIN_PASSWORD, NET, post, phoneOk, emailOk, current, logout, login, signUp, onChange } from "./account.js";

// ── 화면 ──

const TITLES = { login: "로그인", signup: "회원가입", findid: "아이디 찾기", reset: "비밀번호 찾기", admin: "관리자로 로그인" };
// 입력값은 화면을 오가도 남김 (비밀번호는 화면을 바꾸면 지움)
const form = { adminkey: "", username: "", password: "", confirm: "", email: "", phone: "", byPhone: true };

export function loginScreen(root, p) {
  // 사용자 주소에서는 관리자 화면 없음 (관리자는 서버 주소에서)
  const page = TITLES[p.id] && !(USER_SITE && p.id === "admin") ? p.id : "login";
  const adminLink = () => (USER_SITE ? "" : `<button class="link authlink" ${act(() => go("login", "admin"))}>${Edits.hasAdminKey() ? "관리자 모드 켜짐" : "관리자로 로그인"}</button>`);
  let busy = false, error = "", info = "", found = null, mailSent = false;
  form.password = ""; form.confirm = ""; form.adminkey = "";

  const input = (name, label, type = "text", extra = "") =>
    `<label class="authfield"><span>${label}</span><input name="${name}" type="${type}" value="${esc(form[name])}" ${extra}></label>`;
  const byChips = (reset) => `<div class="segs authsegs">${[[true, "전화번호"], [false, "이메일"]].map(([v, l]) =>
    `<button class="${form.byPhone === v ? "on" : ""}" ${act(() => { form.byPhone = v; found = null; error = ""; draw(); })}>${l}</button>`).join("")}</div>` +
    (form.byPhone ? input("phone", "가입한 전화번호", "tel", 'autocomplete="tel"') : input("email", "가입한 이메일", "email", 'autocomplete="email"')) +
    (reset && form.byPhone ? `<p class="authhint">전화번호로 바꾸려면 문자 인증이 필요해서 폰 앱에서 할 수 있어요. 웹에서는 이메일로 바꿔요.</p>` : "");

  const submit = (label, run, enabled = true) =>
    `<button class="btn authbtn" ${busy || !enabled ? "disabled" : ""} ${act(run)}>${busy ? "잠시만요..." : label}</button>`;

  async function doing(block) {
    if (busy) return;
    busy = true; error = ""; info = ""; draw();
    try { await block(); } catch (e) { error = NET; }
    busy = false; draw();
  }
  const done = (msg) => { info = msg; history.length > 1 ? history.back() : go("settings"); };

  function body() {
    if (page === "admin") {
      if (Edits.hasAdminKey()) return `<p class="authhint">관리자 모드가 켜져 있어요. 노선·정류장 화면에서 지도 고치기를 할 수 있어요.</p>` +
        `<button class="btn authbtn" ${act(() => go("fixstops"))}>지도 고치기 열기</button>` +
        `<button class="link authlink" ${act(async () => { await Edits.setAdminKey(""); info = "관리자 모드를 껐어요"; draw(); })}>관리자 모드 끄기</button>`;
      return input("adminkey", "관리자 키", "password", 'autocomplete="off"') +
        `<p class="authhint">관리자 키를 넣으면 이 브라우저에서 지도 고치기(노선 모양·정류장·역 위치)를 할 수 있어요.</p>` +
        submit("관리자로 로그인", () => doing(async () => {
          if (await Edits.setAdminKey(form.adminkey)) { form.adminkey = ""; done("관리자 모드를 켰어요"); } else error = "키가 맞지 않아요";
        }), !!form.adminkey.trim());
    }
    if (current() && page === "login") {
      return `<p class="authhint">${esc(current().username)} 아이디로 로그인했어요${current().dev ? " · 개발자" : ""}.</p>` +
        `<button class="btn authbtn" ${act(() => go("settings"))}>설정으로</button>` +
        `<button class="link authlink" ${act(() => { logout(); draw(); })}>로그아웃</button>` +
        adminLink();
    }
    if (page === "login") {
      return input("username", "아이디", "text", 'autocomplete="username" autocapitalize="none"') +
        input("password", "비밀번호", "password", 'autocomplete="current-password"') +
        submit("로그인", () => doing(async () => { const m = await login(form.username, form.password); if (m) error = m; else done("로그인했어요"); })) +
        `<div class="authlinks"><button class="link" ${act(() => go("login", "findid"))}>아이디 찾기</button><button class="link" ${act(() => go("login", "reset"))}>비밀번호 찾기</button><button class="link" ${act(() => go("login", "signup"))}>회원가입</button></div>` +
        adminLink();
    }
    if (page === "signup") {
      return input("username", "아이디 (영어 소문자·숫자 4~20자)", "text", 'autocomplete="username" autocapitalize="none"') +
        input("password", `비밀번호 (영어+숫자 ${MIN_PASSWORD}자 이상)`, "password", 'autocomplete="new-password"') +
        input("confirm", "비밀번호 확인", "password", 'autocomplete="new-password"') +
        input("email", "이메일", "email", 'autocomplete="email"') +
        input("phone", "전화번호", "tel", 'autocomplete="tel"') +
        `<p class="authhint">전화번호와 이메일로 아이디를 찾을 수 있어요. 비밀번호는 웹에서는 이메일로, 폰 앱에서 문자 인증을 하면 전화번호로도 바꿀 수 있어요.</p>` +
        submit("가입", () => doing(async () => { const m = await signUp(form); if (m) error = m; else done("가입했어요. 환영해요!"); }));
    }
    if (page === "findid") {
      return byChips(false) +
        (found ? `<p class="authresult">${found.length ? "가입한 아이디: <b>" + found.map(esc).join(", ") + "</b>" : `이 ${form.byPhone ? "전화번호" : "이메일"}로 가입한 아이디가 없어요`}</p>` +
          (found.length ? `<button class="link authlink" ${act(() => go("login"))}>로그인하러 가기</button>` : "") : "") +
        submit("찾기", () => doing(async () => {
          if (form.byPhone ? !phoneOk(form.phone) : !emailOk(form.email)) { error = form.byPhone ? "전화번호를 확인해 주세요" : "이메일을 확인해 주세요"; return; }
          const r = await post("find", form.byPhone ? { phone: form.phone } : { email: form.email });
          if (r.ok) found = r.j.ids || []; else error = r.status === 429 ? "잠시 뒤에 다시 해 주세요" : "지금은 찾을 수 없어요";
        }));
    }
    // 비밀번호 찾기: 웹은 이메일로 링크를 보냄
    if (mailSent) return `<p class="authresult">${esc(form.email.trim())} 로 비밀번호를 바꾸는 링크를 보냈어요. 메일(스팸함도)의 링크에서 새 비밀번호를 정한 뒤 로그인해 주세요.</p>` +
      `<button class="btn authbtn" ${act(() => go("login"))}>로그인하러 가기</button>`;
    return input("username", "아이디", "text", 'autocomplete="username" autocapitalize="none"') + byChips(true) +
      (form.byPhone ? "" : `<p class="authhint">아이디와 이메일이 맞으면 비밀번호를 바꾸는 링크를 메일로 보내요.</p>`) +
      submit("확인", () => doing(async () => {
        if (form.byPhone) {
          if (!phoneOk(form.phone)) { error = "전화번호를 확인해 주세요"; return; }
          const r = await post("reset", { username: form.username, phone: form.phone });
          if (!r.ok) error = r.status === 429 ? "잠시 뒤에 다시 해 주세요" : "지금은 확인할 수 없어요";
          else if (!r.j.ok) error = "아이디와 전화번호가 맞지 않아요";
          else error = r.j.phoneOk ? "맞아요. 폰 앱의 비밀번호 찾기에서 문자로 인증하고 바꿔 주세요. 웹에서는 이메일을 골라 메일로 바꿀 수 있어요."
            : "이 아이디는 전화번호를 문자로 인증하지 않아서 이메일로만 바꿀 수 있어요";
          return;
        }
        if (!emailOk(form.email)) { error = "이메일을 확인해 주세요"; return; }
        const r = await post("resetmail", { username: form.username, email: form.email });
        if (!r.ok) error = r.status === 429 ? "잠시 뒤에 다시 해 주세요" : "지금은 메일을 보낼 수 없어요";
        else if (!r.j.ok) error = "아이디와 이메일이 맞지 않아요";
        else mailSent = true;
      }), !!form.username.trim());
  }

  function draw() {
    root.innerHTML = header(TITLES[page], null, { back: true }) +
      `<form class="authform" autocomplete="on" onsubmit="return false"><h2>${TITLES[page]}</h2>` +
      (page === "login" && !current() ? `<p class="authsub">타볼까 아이디로 로그인해요. 폰 앱과 같은 아이디예요.</p>` : "") +
      body() +
      (info ? `<p class="authinfo">${esc(info)}</p>` : "") + (error ? `<p class="autherr">${esc(error)}</p>` : "") + `</form>`;
    const f = root.querySelector(".authform");
    f.addEventListener("input", (e) => {
      const n = e.target.name;
      if (!(n in form)) return;
      form[n] = n === "username" && page !== "login" ? e.target.value.toLowerCase() : e.target.value;
      if (e.target.value !== form[n]) e.target.value = form[n];
      // 찾기·확인 단추가 켜질지 바뀌는 경우만 다시 그림
      if (page === "admin" && n === "adminkey") { const b = f.querySelector(".authbtn"); if (b) b.disabled = busy || !form.adminkey.trim(); }
      if (page === "reset" && n === "username") { const b = f.querySelector(".authbtn"); if (b) b.disabled = busy || !form.username.trim(); }
    });
    // 엔터 = 맨 아래 큰 단추
    f.addEventListener("keydown", (e) => { if (e.key === "Enter" && e.target.tagName === "INPUT") { e.preventDefault(); f.querySelector(".authbtn")?.click(); } });
  }
  draw();
  const off = onChange(() => { if (!busy) draw(); });
  return () => off();
}

