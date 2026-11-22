#!/usr/bin/env node
/**
 * 타볼까 국내 중계 (이 PC에서 돎)
 *
 * Cloudflare(타볼까 서버) → 공공데이터포털 연결이 자주 매달려서(2026-11-22 잰 것: 10번 중 8번), 국내 PC 가 대신 물어 줌.
 * - 127.0.0.1:8792 에서 GET /f?u=<공공데이터포털 주소> 만 받음 (X-Relay-Key 가 맞아야, 호스트는 apis.data.go.kr 만)
 * - cloudflared 빠른 터널(*.trycloudflare.com)을 열고, 그 주소를 타볼까 POST /w/relay/register 로 알림 (켤 때 + 5분마다)
 * - cloudflared 가 죽으면 다시 띄움. 이 PC 가 꺼지면 타볼까는 예전처럼 바로 물음 (느리지만 됨)
 *
 *   node server/relay/relay.mjs        # local.properties 의 RELAY_KEY 사용 (deploy.ps1 이 만들어 둠)
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
export const ALLOWED = new Set(["apis.data.go.kr"]);
const SERVER = "https://tabolkka.pages.dev";
const UPSTREAM_TIMEOUT_MS = 12000;
const BEAT_MS = 5 * 60 * 1000;
const LOG_DIR = join(ROOT, "server", "relay", "logs");

function prop(name) {
  const f = join(ROOT, "local.properties");
  if (!existsSync(f)) return "";
  const m = readFileSync(f, "utf-8").match(new RegExp("^\\s*" + name + "\\s*=\\s*(.+?)\\s*$", "m"));
  return m ? m[1] : "";
}

function log(msg) {
  const line = `${new Date().toISOString()} ${msg}`;
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

/** 중계 요청 처리 (시험에서 fetcher 를 바꿔 끼움) */
export async function handle(req, key, fetcher = fetch) {
  const url = new URL(req.url, "http://relay.local");
  if (url.pathname === "/health") return { status: 200, headers: { "Content-Type": "text/plain" }, body: "ok" };
  if (req.method !== "GET" || url.pathname !== "/f") return { status: 404, headers: {}, body: "not found" };
  if (!same(req.headers["x-relay-key"], key)) return { status: 403, headers: {}, body: "forbidden" };
  const target = url.searchParams.get("u") || "";
  if (!allowedTarget(target)) return { status: 400, headers: {}, body: "bad target" };
  const t0 = Date.now();
  try {
    const r = await fetcher(target, {
      headers: { Accept: req.headers.accept || "*/*", "User-Agent": req.headers["user-agent"] || "TagoHub-relay" },
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
    const body = Buffer.from(await r.arrayBuffer());
    log(`${new URL(target).pathname.split("/").slice(0, 3).join("/")} ${r.status} ${Date.now() - t0}ms`);
    return { status: r.status, headers: { "Content-Type": r.headers.get("content-type") || "application/octet-stream", "X-Relay-Upstream": String(r.status) }, body };
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
  let tunnel = null, beat = null;
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
    log(`${why}, ${ms / 1000}초 뒤 다시`);
    setTimeout(() => startTunnel(key), ms);
  };
  child.on("error", (e) => restart(`cloudflared 를 띄우지 못함: ${e.code || e.message}`, 60000));
  child.on("exit", (code) => restart(`cloudflared 끝남 (${code})`, 10000));
}

function main() {
  const key = prop("RELAY_KEY");
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
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
