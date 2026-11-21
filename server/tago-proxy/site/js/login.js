// 아이디 로그인·회원가입·아이디 찾기·비밀번호 찾기 화면 (따로 된 화면: #/login, #/login/signup, #/login/findid, #/login/reset).
// 브라우저 뒤로 가기로 앞 화면에 돌아감. 계정 처리는 account.js
import { esc } from "./core.js";
import { act, header } from "./ui.js";
import { go } from "./nav.js";
import * as Edits from "./edits.js";
import { MIN_PASSWORD, NET, post, phoneOk, emailOk, current, logout, login, signUp, onChange, EMAIL_DOMAINS, joinEmail, usernameFree, normUsername, setRemember } from "./account.js";

// ── 화면 ──

const TITLES = { login: "로그인", signup: "회원가입", findid: "아이디 찾기", reset: "비밀번호 찾기", admin: "관리자로 로그인" };
// 입력값은 화면을 오가도 남김 (비밀번호는 화면을 바꾸면 지움)
// 회원가입 이메일은 앞자리(emailId)와 뒷자리(emailDomain, 고르기·직접 넣기 emailCustom)로 나눠 받음
const form = { autoLogin: true, emailId: "", emailDomain: "naver.com", emailCustom: "", username: "", password: "", confirm: "", email: "", phone: "", byPhone: true };

export function loginScreen(root, p) {
  const page = TITLES[p.id] ? p.id : "login";
  const adminOn = () => Edits.isAdmin();
  const adminLink = () => `<button class="link authlink" ${act(() => go("login", "admin"))}>${adminOn() ? "관리자 모드 켜짐" : "관리자로 로그인"}</button>`;
  let busy = false, error = "", info = "", found = null, mailSent = false;
  /** 아이디 중복확인 결과 { u: 확인한 아이디, free: true|false|null } */
  let idCheck = null;
  // 회원가입: 앞 화면에서 넣은 이메일이 있으면 앞자리·뒷자리로 나눠 이어 씀
  if (page === "signup" && form.email && !form.emailId) {
    const [a, d] = form.email.split("@");
    form.emailId = a || "";
    if (d) { if (EMAIL_DOMAINS.includes(d)) form.emailDomain = d; else { form.emailDomain = ""; form.emailCustom = d; } }
  }
  const signupEmail = () => joinEmail(form.emailId, form.emailDomain || form.emailCustom);
  /** 비밀번호 확인 칸 아래 글: 같은지 다른지 (확인 칸이 비었으면 없음) */
  const matchHtml = () => (!form.confirm ? "" : form.confirm === form.password
    ? `<span class="authok">비밀번호가 같아요</span>` : `<span class="autherr">비밀번호가 달라요</span>`);
  const idCheckHtml = () => {
    const u = normUsername(form.username);
    if (!form.username) return "";
    if (!u) return `<span class="autherr">영어 소문자·숫자·밑줄(_)로 4~20자예요</span>`;
    if (!idCheck || idCheck.u !== u) return `<span class="authhint">중복확인을 눌러 주세요</span>`;
    return idCheck.free === true ? `<span class="authok">쓸 수 있는 아이디예요</span>`
      : idCheck.free === false ? `<span class="autherr">이미 쓰는 아이디예요</span>` : `<span class="autherr">지금은 확인할 수 없어요. 잠시 뒤에 다시 눌러 주세요</span>`;
  };
  async function checkId() {
    const u = normUsername(form.username);
    if (!u) { draw(); return null; }
    idCheck = { u, free: await usernameFree(u) };
    draw();
    return idCheck.free;
  }
  form.password = ""; form.confirm = "";

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
    // 관리자 로그인: 관리자 아이디(서버 비밀 값 DEV_IDS 에 든 아이디)와 비밀번호. 관리자 아이디가 아니면 로그인하지 않음.
    // 관리자인지는 서버가 정함 (지도 고치기 저장도 서버가 이 로그인 토큰을 확인, src/edits.js·auth.js)
    if (page === "admin") {
      if (adminOn()) return `<p class="authhint">${current()?.dev ? esc(current().username) + " 관리자 아이디로 로그인했어요" : "관리자 모드가 켜져 있어요"}. 노선·정류장·역 점에서 지도 고치기를 할 수 있어요.</p>` +
        `<button class="btn authbtn" ${act(() => go("settings"))}>설정에서 지도 고치기</button>` +
        `<button class="link authlink" ${act(async () => { if (Edits.hasAdminKey()) await Edits.setAdminKey(""); if (current()?.dev) logout(); Edits.setEditing(false); info = "관리자 모드를 껐어요"; draw(); })}>관리자 로그아웃</button>`;
      return input("username", "관리자 아이디", "text", 'autocomplete="username" autocapitalize="none"') +
        input("password", "비밀번호", "password", 'autocomplete="current-password"') +
        `<p class="authhint">관리자 아이디로 로그인하면 지도 고치기(노선 모양·정류장·역·자전거·주차장·편의시설 위치)를 할 수 있어요.</p>` +
        submit("관리자로 로그인", () => doing(async () => {
          const m = await login(form.username, form.password);
          if (m) { error = m; return; }
          if (!current()?.dev) { logout(); error = "관리자 아이디가 아니에요"; return; }
          done("관리자로 로그인했어요");
        }));
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
        `<label class="authcheck"><input type="checkbox" name="autoLogin" ${form.autoLogin ? "checked" : ""}> 자동 로그인</label>` +
        submit("로그인", () => doing(async () => { setRemember(form.autoLogin); const m = await login(form.username, form.password); if (m) error = m; else done("로그인했어요"); })) +
        `<div class="authlinks"><button class="link" ${act(() => go("login", "findid"))}>아이디 찾기</button><button class="link" ${act(() => go("login", "reset"))}>비밀번호 찾기</button><button class="link" ${act(() => go("login", "signup"))}>회원가입</button></div>` +
        adminLink();
    }
    if (page === "signup") {
      const domains = EMAIL_DOMAINS.map((d) => `<option value="${d}" ${form.emailDomain === d ? "selected" : ""}>${d || "직접 입력"}</option>`).join("");
      return `<label class="authfield"><span>아이디 (영어 소문자·숫자 4~20자)</span><span class="authrow"><input name="username" type="text" value="${esc(form.username)}" autocomplete="username" autocapitalize="none">` +
          `<button type="button" class="btn authside" ${act(() => checkId())}>중복확인</button></span><small id="idcheck">${idCheckHtml()}</small></label>` +
        input("password", `비밀번호 (영어+숫자 ${MIN_PASSWORD}자 이상)`, "password", 'autocomplete="new-password"') +
        `<label class="authfield"><span>비밀번호 확인</span><input name="confirm" type="password" value="${esc(form.confirm)}" autocomplete="new-password"><small id="pwmatch">${matchHtml()}</small></label>` +
        `<label class="authfield"><span>이메일</span><span class="authrow"><input name="emailId" type="text" value="${esc(form.emailId)}" autocomplete="off" autocapitalize="none" placeholder="이메일 앞자리">` +
          `<b class="authat">@</b><select name="emailDomain">${domains}</select></span>` +
          (form.emailDomain ? "" : `<input name="emailCustom" type="text" value="${esc(form.emailCustom)}" autocomplete="off" autocapitalize="none" placeholder="예: company.co.kr">`) + `</label>` +
        input("phone", "전화번호", "tel", 'autocomplete="tel"') +
        `<p class="authhint">전화번호와 이메일로 아이디를 찾을 수 있어요. 비밀번호는 웹에서는 이메일로, 폰 앱에서 문자 인증을 하면 전화번호로도 바꿀 수 있어요.</p>` +
        submit("가입", () => doing(async () => {
          // 중복확인을 안 했거나 아이디를 바꿨으면 지금 확인
          const u = normUsername(form.username);
          if (u && (!idCheck || idCheck.u !== u)) idCheck = { u, free: await usernameFree(u) };
          if (u && idCheck.free === false) { error = "이미 쓰는 아이디예요"; return; }
          const m = await signUp({ ...form, email: signupEmail() });
          if (m) error = m; else done("가입했어요. 환영해요!");
        }));
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
      if (!(n in form) || e.target.type === "checkbox") return;
      form[n] = n === "username" && page !== "login" ? e.target.value.toLowerCase() : e.target.value;
      if (e.target.value !== form[n]) e.target.value = form[n];
      if (page === "signup") {
        // 비밀번호 확인: 넣는 대로 같은지 다른지 (다시 그리지 않고 글만 바꿔 입력 중인 칸이 그대로)
        if (n === "password" || n === "confirm") { const el = f.querySelector("#pwmatch"); if (el) el.innerHTML = matchHtml(); }
        if (n === "username") { const el = f.querySelector("#idcheck"); if (el) el.innerHTML = idCheckHtml(); }
        // 이메일 앞자리에 @ 를 넣으면 뒷자리는 직접 넣기로
        if (n === "emailId" && form.emailId.includes("@")) {
          const [a, d] = form.emailId.split("@");
          form.emailId = a; form.emailCustom = d || ""; form.emailDomain = EMAIL_DOMAINS.includes(d) ? d : "";
          draw(); f.ownerDocument.querySelector(form.emailDomain ? 'select[name="emailDomain"]' : 'input[name="emailCustom"]')?.focus();
        }
      }
      // 찾기·확인 단추가 켜질지 바뀌는 경우만 다시 그림
      if (page === "reset" && n === "username") { const b = f.querySelector(".authbtn"); if (b) b.disabled = busy || !form.username.trim(); }
    });
    // 이메일 뒷자리 고르기 (직접 입력이면 칸이 생김), 자동 로그인 체크
    f.addEventListener("change", (e) => {
      if (e.target.name === "autoLogin") { form.autoLogin = e.target.checked; return; }
      if (e.target.name === "emailDomain") { form.emailDomain = e.target.value; draw(); if (!form.emailDomain) root.querySelector('input[name="emailCustom"]')?.focus(); } });
    // 엔터 = 맨 아래 큰 단추
    f.addEventListener("keydown", (e) => { if (e.key === "Enter" && e.target.tagName === "INPUT") { e.preventDefault(); f.querySelector(".authbtn")?.click(); } });
  }
  draw();
  const off = onChange(() => { if (!busy) draw(); });
  return () => off();
}

