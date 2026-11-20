// 아이디 회원 (웹): 폰 앱과 같은 아이디로 로그인·회원가입·아이디 찾기·비밀번호 찾기.
// 브라우저는 Firebase 를 바로 못 불러서(키가 안드로이드 전용) 서버 /w/auth/* (src/auth.js) 가 대신 부름.
// 로그인하면 Firebase 토큰을 이 브라우저에만 저장. 개발자 아이디(서버 비밀 값 DEV_IDS)면 지도 고치기가 열림 (edits.js).
// 화면은 login.js (이 파일은 화면 없이: edits.js 가 쓰고, 테스트도 불러옴).
import { BASE } from "./core.js";

const KEY = "tagohub.account";
export const MIN_PASSWORD = 8;

function load() {
  try { const a = JSON.parse(localStorage.getItem(KEY) || "null"); return a && a.username && a.refreshToken ? a : null; } catch (e) { return null; }
}
let acc = load();
const listeners = new Set();
/** 로그인·로그아웃·개발자 여부가 바뀌면 부름 (돌려준 함수로 그만 받기) */
export function onChange(f) { listeners.add(f); return () => listeners.delete(f); }
function save(a) {
  acc = a;
  try { if (a) localStorage.setItem(KEY, JSON.stringify(a)); else localStorage.removeItem(KEY); } catch (e) { /* 저장 못 함 */ }
  listeners.forEach((f) => { try { f(); } catch (e) { console.error(e); } });
}

/** 로그인한 계정 {username, dev, email} 또는 null */
export const current = () => acc;
/** 개발자 아이디로 로그인했는지 */
export const isDev = () => !!(acc && acc.dev);
export function logout() { save(null); }

export async function post(op, body) {
  const r = await fetch(BASE + "w/auth/" + op, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  let j = {};
  try { j = await r.json(); } catch (e) { /* 빈 응답 */ }
  return { status: r.status, ok: r.ok, j };
}
function expOf(tok) {
  try { return JSON.parse(atob(String(tok).split(".")[1].replace(/-/g, "+").replace(/_/g, "/"))).exp * 1000; } catch (e) { return 0; }
}
function keep(j) {
  save({ username: j.username, dev: !!j.dev, email: j.email || null, idToken: j.idToken, refreshToken: j.refreshToken, exp: expOf(j.idToken) });
}

/** 지금 쓸 수 있는 Firebase 토큰 (2분 안에 끝나면 새로 받음). 로그인 안 했거나 못 받으면 null */
export async function token() {
  if (!acc) return null;
  if (acc.exp - Date.now() > 120000) return acc.idToken;
  const r = await post("refresh", { refreshToken: acc.refreshToken }).catch(() => null);
  if (!r || !r.ok) { if (r && r.status === 401) save(null); return null; }
  save({ ...acc, idToken: r.j.idToken, refreshToken: r.j.refreshToken || acc.refreshToken, exp: expOf(r.j.idToken) });
  return acc.idToken;
}

/** 서버에 아이디·개발자 여부를 다시 물어봄 (DEV_IDS 가 바뀌었을 수 있음) */
export async function refreshMe() {
  const t = await token();
  if (!t) return;
  const r = await post("me", { idToken: t }).catch(() => null);
  if (!r || !r.ok || !acc) return;
  if (!r.j.username) { save(null); return; }
  if (r.j.dev !== acc.dev || r.j.username !== acc.username) save({ ...acc, username: r.j.username, dev: !!r.j.dev });
}

export const NET = "인터넷 연결을 확인해 주세요";
export const normUsername = (s) => { const u = String(s || "").trim().toLowerCase(); return /^[a-z0-9_]{4,20}$/.test(u) ? u : null; };
export const phoneOk = (s) => { const d = String(s || "").replace(/[\s\-()]/g, ""); return /^\+82\d{8,11}$/.test(d) || /^0\d{8,10}$/.test(d); };
export const emailOk = (s) => /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(String(s || "").trim());

/** 아이디 로그인 → 틀리면 알림 글, 되면 null */
export async function login(username, password) {
  const u = normUsername(username);
  if (!u || !password) return "아이디나 비밀번호가 맞지 않아요";
  const r = await post("login", { username: u, password, web: true }).catch(() => null);
  if (!r) return NET;
  if (r.status === 429) return "로그인을 여러 번 틀려서 15분 동안 막혔어요. 잠시 뒤에 해 주세요";
  if (r.status === 401) return "아이디나 비밀번호가 맞지 않아요";
  if (!r.ok || !r.j.idToken) return "지금은 로그인할 수 없어요. 잠시 뒤에 해 주세요";
  keep(r.j);
  return null;
}

/** 가입 전에 입력 확인 (앱 IdAccount.validateSignUp 과 같은 규칙) → 알림 글 또는 null */
export function validateSignUp(f) {
  if (!normUsername(f.username)) return "아이디는 영어 소문자·숫자·밑줄(_)로 4~20자예요";
  const pw = f.password || "";
  if (pw.length < MIN_PASSWORD || !/[A-Za-z]/.test(pw) || !/\d/.test(pw)) return `비밀번호는 영어와 숫자를 섞어 ${MIN_PASSWORD}자 이상이에요`;
  if (pw !== f.confirm) return "비밀번호 확인이 달라요";
  if (!emailOk(f.email)) return "이메일을 확인해 주세요";
  if (!phoneOk(f.phone)) return "전화번호를 확인해 주세요 (예: 010-1234-5678)";
  return null;
}

export async function signUp(f) {
  const bad = validateSignUp(f);
  if (bad) return bad;
  const r = await post("signup", { username: f.username, password: f.password, email: f.email, phone: f.phone }).catch(() => null);
  if (!r) return NET;
  if (r.ok && r.j.idToken) { keep(r.j); return null; }
  return ({ taken: "이미 쓰는 아이디예요", email: "이미 가입한 이메일이에요. 아이디 찾기로 아이디를 찾아 로그인해 주세요", weak: `비밀번호는 영어와 숫자를 섞어 ${MIN_PASSWORD}자 이상이에요`,
    "bad email": "이메일을 확인해 주세요", "bad phone": "전화번호를 확인해 주세요", "bad username": "아이디는 영어 소문자·숫자·밑줄(_)로 4~20자예요",
    "too many": "잠시 뒤에 다시 해 주세요" })[r.j.error] || "지금은 가입할 수 없어요. 잠시 뒤에 해 주세요";
}

// 열 때 한 번: 개발자 여부를 서버에 다시 물어봄
if (acc) refreshMe();
