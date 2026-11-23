#!/usr/bin/env node
/**
 * 타볼까 국내 중계 (2026-11-23부터 집 노트북 Ubuntu 에서 systemd 로 돎. Windows PC 에서도 돌 수 있음: install-relay.ps1)
 *
 * Cloudflare(타볼까 서버) → 공공데이터포털 연결이 자주 매달려서(2026-11-22 잰 것: 10번 중 8번), 국내 기계가 대신 물어 줌.
 * - 127.0.0.1:8792 에서 GET /f?u=<공공데이터포털 주소> 만 받음 (X-Relay-Key 가 맞아야, 호스트는 apis.data.go.kr 만)
 * - 공공데이터포털이 1.2초 안에 답이 없거나 HTTP_ERROR 를 내면 같은 요청을 하나 더 보냄 (최대 3개, askUpstream)
 * - cloudflared 빠른 터널(*.trycloudflare.com)을 열고, 그 주소를 타볼까 POST /w/relay/register 로 알림 (켤 때 + 5분마다)
 * - cloudflared 가 죽으면 다시 띄움. 중계가 꺼지면 타볼까는 예전처럼 바로 물음 (느리지만 됨)
 * - 환경 변수 CRON_KEY 가 있으면 타볼까 예약 작업(POST /w/cron)도 1분마다 부름 (cronLoop)
 *
 *   node server/relay/relay.mjs        # local.properties 의 RELAY_KEY 사용 (deploy.ps1 이 만들어 둠)
 *   리눅스 노트북: server/relay/linux/README.md (systemd 서비스, 열쇠는 /etc/tagohub-relay.env)
 *
 * 키·주소는 화면에 쓰지 않음 (기록에는 호스트·상태·걸린 시간만)
 */
import http from "node:http";
import { spawn } from "node:child_process";
import { readFileSync, existsSync, appendFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const PORT = 8792;
// 타볼까 krrelay.js RELAY_HOSTS 와 같게 (브이월드는 Cloudflare 에서 막힘)
export const ALLOWED = new Set(["apis.data.go.kr", "api.vworld.kr"]);
const SERVER = "https://tabolkka.pages.dev";
const UPSTREAM_TIMEOUT_MS = 12000;
// 공공데이터포털이 요청을 절반쯤 7~20초 붙잡다 HTTP_ERROR 로 끝냄 (2026-11-24 잰 것: 국내에서 바로 물어도 도착·위치·정류소·노선 모두,
// 되는 요청은 0.1~0.8초). 그래서 1.2초 안에 답이 없으면 같은 요청을 하나 더(최대 3개) 보내고 먼저 온 좋은 답을 씀
export const HEDGE_MS = 1200, HEDGE_TRIES = 3;
const FLAKY = /HTTP_ERROR|SERVICETIMEOUT_ERROR|SERVICE_TIMEOUT/;
const BEAT_MS = 5 * 60 * 1000;
// 터널 스스로 확인: 1분마다 바깥 주소로 /health 를 물어 2번 잇달아 안 되면 cloudflared 를 다시 띄움 (새 주소로 다시 알림).
// 2026-11-22 12:3x UTC 에 중계는 살아 있는데 터널만 530(연결 끊김)을 내서, 그동안 타볼까가 느린 바로 묻기로 갔음
const CHECK_MS = 60 * 1000, CHECK_FAILS = 2;
// 리눅스 노트북(server/relay/linux)에서는 systemd 가 RELAY_LOG_DIR·RELAY_KEY 를 줌
const LOG_DIR = process.env.RELAY_LOG_DIR || join(ROOT, "server", "relay", "logs");

function prop(name) {
  const f = join(ROOT, "local.properties");
  if (!existsSync(f)) return "";
  const m = readFileSync(f, "utf-8").match(new RegExp("^\\s*" + name + "\\s*=\\s*(.+?)\\s*$", "m"));
  return m ? m[1] : "";
}

// 시험에서 불러 쓸 때는 기록 파일에 쓰지 않음 (main 이 켬)
let toFile = false;
function log(msg) {
  const line = `${new Date().toISOString()} ${msg}`;
  if (!toFile) return;
  console.log(line);
  try { mkdirSync(LOG_DIR, { recursive: true }); appendFileSync(join(LOG_DIR, new Date().toISOString().slice(0, 10) + ".log"), line + "\n"); } catch { /* 기록 실패는 무시 */ }
}

/** 같은 문자열인지 (시간 차로 값을 알아내지 못하게) */
export function same(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || !a || a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

/** 물어도 되는 주소인지: https + 허락한 호스트 + 기본 포트 */
export function allowedTarget(u) {
  try {
    const t = new URL(u);
    return t.protocol === "https:" && ALLOWED.has(t.hostname) && (t.port === "" || t.port === "443");
  } catch {
    return false;
  }
}

/** 기관이 잠깐 탈 난 답인지 (5xx 또는 공공데이터포털 HTTP_ERROR·SERVICETIMEOUT_ERROR) */
export function flaky(status, body) {
  return status >= 500 || FLAKY.test(Buffer.from(body).subarray(0, 3000).toString("latin1"));
}

/**
 * 기관에 묻기: 공공데이터포털이면 [hedgeMs] 안에 좋은 답이 없을 때 같은 요청을 하나 더 보냄 (최대 [tries] 개, 다 합쳐 [totalMs]).
 * 먼저 온 좋은 답을 쓰고 나머지는 끊음. 모두 탈 난 답이면 마지막 답, 답이 하나도 없으면 마지막 오류(시간 넘김은 TimeoutError).
 * 돌려주는 것: {status, type, body, tries}
 */
export function askUpstream(target, headers, fetcher = fetch, { hedgeMs = HEDGE_MS, tries = HEDGE_TRIES, totalMs = UPSTREAM_TIMEOUT_MS } = {}) {
  if (new URL(target).hostname !== "apis.data.go.kr") tries = 1;
  return new Promise((resolve, reject) => {
    const ctrls = [];
    let done = false, started = 0, pending = 0, last = null, lastErr = null, timer = null, cap = null;
    const finish = (fn, v) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      clearTimeout(cap);
      ctrls.forEach((c) => { try { c.abort(); } catch { /* 이미 끝남 */ } });
      if (v && typeof v === "object" && "body" in v) v.tries = started;
      fn(v);
    };
    const next = () => {
      if (started < tries) return launch();
      if (pending === 0) return last ? finish(resolve, last) : finish(reject, lastErr || new Error("upstream failed"));
    };
    const launch = () => {
      if (done || started >= tries) return;
      started++;
      pending++;
      const c = new AbortController();
      ctrls.push(c);
      Promise.resolve()
        .then(() => fetcher(target, { headers, signal: c.signal }))
        .then(async (r) => ({ status: r.status, type: r.headers.get("content-type"), body: Buffer.from(await r.arrayBuffer()) }))
        .then((out) => {
          pending--;
          if (done) return;
          if (!flaky(out.status, out.body)) return finish(resolve, out);
          last = out;
          next();
        }, (e) => {
          pending--;
          if (done) return;
          lastErr = e;
          next();
        });
      clearTimeout(timer);
      if (started < tries) timer = setTimeout(launch, hedgeMs);
    };
    cap = setTimeout(() => {
      if (last) return finish(resolve, last);
      const e = new Error("upstream timeout");
      e.name = "TimeoutError";
      finish(reject, e);
    }, totalMs);
    launch();
  });
}

/** 중계 요청 처리 (시험에서 fetcher·겹쳐 묻기 설정을 바꿔 끼움) */
export async function handle(req, key, fetcher = fetch, hedge = {}) {
  const url = new URL(req.url, "http://relay.local");
  if (url.pathname === "/health") return { status: 200, headers: { "Content-Type": "text/plain" }, body: "ok" };
  if (req.method !== "GET" || url.pathname !== "/f") return { status: 404, headers: {}, body: "not found" };
  if (!same(req.headers["x-relay-key"], key)) return { status: 403, headers: {}, body: "forbidden" };
  const target = url.searchParams.get("u") || "";
  if (!allowedTarget(target)) return { status: 400, headers: {}, body: "bad target" };
  const t0 = Date.now();
  try {
    const headers = { Accept: req.headers.accept || "*/*", "User-Agent": req.headers["user-agent"] || "TagoHub-relay" };
    const r = await askUpstream(target, headers, fetcher, hedge);
    log(`${new URL(target).pathname.split("/").slice(0, 3).join("/")} ${r.status}${flaky(r.status, r.body) ? " 탈남" : ""} ${Date.now() - t0}ms ${r.tries}번`);
    return { status: r.status, headers: { "Content-Type": r.type || "application/octet-stream", "X-Relay-Upstream": String(r.status), "X-Relay-Tries": String(r.tries) }, body: r.body };
  } catch (e) {
    log(`실패 ${e.name} ${Date.now() - t0}ms`);
    // 중계는 살아 있고 기관이 늦거나 끊김: X-Relay-Upstream 0 (타볼까가 중계를 꺼진 것으로 보지 않게)
    return { status: e.name === "TimeoutError" ? 504 : 502, headers: { "X-Relay-Upstream": "0" }, body: "relay upstream failed" };
  }
}

async function register(tunnel, key) {
  try {
    const r = await fetch(SERVER + "/w/relay/register", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Relay-Key": key },
      body: JSON.stringify({ url: tunnel }),
      signal: AbortSignal.timeout(20000),
    });
    log(`타볼까에 알림: ${r.status}`);
    return r.ok;
  } catch (e) {
    log(`타볼까에 알리지 못함: ${e.name}`);
    return false;
  }
}

/**
 * 타볼까 예약 작업을 1분마다 부름 (CRON_KEY 가 있을 때만: 리눅스 노트북의 /etc/tagohub-relay.env).
 * GitHub Actions 의 5분 예약이 실제로는 6시간에 한 번꼴로만 돌아서(2026-11-23 잰 것) 고장 확인·정류장 시간표 모으기가 멈췄음.
 * 24시간 켜져 있는 노트북이 대신 부름. 한 번 끝나야 다음을 부름 (겹치지 않게), 시작 기준 1분 간격.
 */
export const CRON_MS = 60 * 1000;
export async function cronOnce(cronKey, fetcher = fetch) {
  try {
    const r = await fetcher(SERVER + "/w/cron", { method: "POST", headers: { "X-Cron-Key": cronKey }, signal: AbortSignal.timeout(55000) });
    await r.arrayBuffer().catch(() => null);
    return r.status;
  } catch (e) {
    return e && e.name === "TimeoutError" ? "timeout" : "error";
  }
}
function cronLoop(cronKey) {
  let lastOk = null, runs = 0, bad = 0, hour = new Date().getUTCHours();
  const tick = async () => {
    const t0 = Date.now();
    const st = await cronOnce(cronKey);
    const ok = st === 200;
    runs++;
    if (!ok) bad++;
    // 기록은 바뀔 때와 한 시간에 한 번만 (1분마다 쓰면 너무 많음)
    if (ok !== lastOk) log(ok ? "예약 작업 부름: 200" : `예약 작업 실패: ${st}${st === 403 ? " (CRON_KEY 가 타볼까와 달라요)" : ""}`);
    lastOk = ok;
    const h = new Date().getUTCHours();
    if (h !== hour) { log(`예약 작업 지난 한 시간 ${runs}번 (실패 ${bad})`); runs = 0; bad = 0; hour = h; }
    setTimeout(tick, Math.max(1000, CRON_MS - (Date.now() - t0)));
  };
  setTimeout(tick, 15000);
}

function findCloudflared() {
  const tries = [
    process.env.CLOUDFLARED,
    "C:\\Program Files (x86)\\cloudflared\\cloudflared.exe",
    "C:\\Program Files\\cloudflared\\cloudflared.exe",
    join(process.env.LOCALAPPDATA || "", "Microsoft", "WinGet", "Links", "cloudflared.exe"),
  ].filter(Boolean);
  return tries.find((p) => existsSync(p)) || "cloudflared";
}

function startTunnel(key) {
  let tunnel = null, beat = null, check = null, misses = 0;
  const exe = findCloudflared();
  const child = spawn(exe, ["tunnel", "--no-autoupdate", "--url", `http://127.0.0.1:${PORT}`], { windowsHide: true });
  const onData = (buf) => {
    const m = /https:\/\/[a-z0-9-]+\.trycloudflare\.com/.exec(buf.toString());
    if (m && m[0] !== tunnel) {
      tunnel = m[0];
      log("터널 열림 (주소를 타볼까에 알림)");
      // 터널이 바로 쓸 수 있게 될 때까지 잠깐 기다렸다 알림
      setTimeout(() => register(tunnel, key), 5000);
      clearInterval(beat);
      beat = setInterval(() => register(tunnel, key), BEAT_MS);
      clearInterval(check);
      check = setInterval(async () => {
        const ok = await fetch(tunnel + "/health", { signal: AbortSignal.timeout(10000) }).then((r) => r.ok, () => false);
        if (ok) { misses = 0; return; }
        misses++;
        log(`터널 확인 실패 ${misses}번`);
        if (misses >= CHECK_FAILS) { log("터널이 안 닿아 cloudflared 를 다시 띄움"); child.kill(); }
      }, CHECK_MS);
    }
  };
  child.stdout.on("data", onData);
  child.stderr.on("data", onData);
  // 못 띄웠을 때(error)와 끝났을 때(exit) 둘 다 올 수 있어서 한 번만 다시 띄움 (못 띄우면 1분 뒤)
  let again = false;
  const restart = (why, ms) => {
    if (again) return;
    again = true;
    clearInterval(beat);
    clearInterval(check);
    log(`${why}, ${ms / 1000}초 뒤 다시`);
    setTimeout(() => startTunnel(key), ms);
  };
  child.on("error", (e) => restart(`cloudflared 를 띄우지 못함: ${e.code || e.message}`, 60000));
  child.on("exit", (code) => restart(`cloudflared 끝남 (${code})`, 10000));
}

function main() {
  toFile = true;
  // 왜 꺼졌는지 남김 (기록 없이 꺼지면 원인을 알 수 없었음)
  process.on("uncaughtException", (e) => { log(`멈춤: ${e && e.stack ? e.stack.split(String.fromCharCode(10))[0] : e}`); process.exit(1); });
  process.on("exit", (code) => { try { appendFileSync(join(LOG_DIR, new Date().toISOString().slice(0, 10) + ".log"), `${new Date().toISOString()} 끝남 (${code})` + String.fromCharCode(10)); } catch { /* 무시 */ } });
  const key = (process.env.RELAY_KEY || "").trim() || prop("RELAY_KEY");
  if (!key) {
    console.error("local.properties 에 RELAY_KEY 가 없어요. deploy.ps1 을 한 번 실행하면 만들어져요");
    process.exit(1);
  }
  http.createServer(async (req, res) => {
    const out = await handle(req, key).catch(() => ({ status: 500, headers: {}, body: "error" }));
    res.writeHead(out.status, out.headers);
    res.end(out.body);
  }).listen(PORT, "127.0.0.1", () => {
    log(`중계 켜짐 127.0.0.1:${PORT}`);
    startTunnel(key);
    // 노트북은 /etc/tagohub-relay.env 의 CRON_KEY, 이 PC 는 local.properties 의 CRON_KEY (노트북이 꺼져 이 PC 가 대신 맡을 때, 2026-11-23)
    const cronKey = (process.env.CRON_KEY || prop("CRON_KEY") || "").trim();
    if (cronKey) { log("예약 작업도 1분마다 부름"); cronLoop(cronKey); }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
