// 관리자 편집기 (D1 tagohub-log): 쿠키 로그인, 배치도·노선 선형 저장, 앱이 읽는 /w/layouts.tsv·/w/shapes.tsv.
// 비밀 값 ADMIN_TOKEN 이 없으면 모든 관리자 요청은 401 이다.
const COOKIE = "th_admin";
const MAX_BODY = 64 * 1024;
const MAX_SHAPE_BODY = 256 * 1024;
// 배치 표: 노선 하나에 한 줄. updated_at 은 앱에 "바뀐 것이 있나"를 알려 주는 버전이 된다
const SCHEMA_LAYOUTS =
  "CREATE TABLE IF NOT EXISTS layouts (route_id TEXT PRIMARY KEY, tsv TEXT NOT NULL, updated_at INTEGER NOT NULL) WITHOUT ROWID";
// 노선 선형 표: 앱에 들어 있는 bus_route_shapes.tsv 와 같은 형식 (노선ID \t 첫점 \t 변화량)
const SCHEMA_SHAPES =
  "CREATE TABLE IF NOT EXISTS route_shapes (route_id TEXT PRIMARY KEY, tsv TEXT NOT NULL, updated_at INTEGER NOT NULL) WITHOUT ROWID";
const WEB_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, If-None-Match",
  // 웹·앱이 ETag·버전을 읽을 수 있게 (없으면 브라우저가 숨긴다)
  "Access-Control-Expose-Headers": "ETag, X-Layout-Version, X-Shape-Version",
  "Access-Control-Max-Age": "86400",
};

const json = (o, status = 200, headers = {}) =>
  new Response(JSON.stringify(o), {
    status,
    headers: { ...WEB_HEADERS, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...headers },
  });

/** 쿠키 값: ADMIN_TOKEN 을 키로 한 HMAC (토큰 자체를 쿠키에 담지 않는다) */
async function signature(token) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(token), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode("admin-v1"));
  return [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function cookieOf(request, name) {
  for (const part of (request.headers.get("Cookie") || "").split(";")) {
    const i = part.indexOf("=");
    if (i > 0 && part.slice(0, i).trim() === name) return part.slice(i + 1).trim();
  }
  return "";
}

const same = (a, b) => {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
};

async function isAdmin(request, env) {
  if (!env.ADMIN_TOKEN) return false;
  return same(cookieOf(request, COOKIE), await signature(env.ADMIN_TOKEN));
}

async function login(request, env) {
  if (!env.ADMIN_TOKEN) return json({ error: "서버에 ADMIN_TOKEN 이 없습니다." }, 503);
  let token = "";
  try {
    const body = await request.json();
    token = String(body && body.token ? body.token : "");
  } catch {
    return json({ error: "요청을 읽지 못했습니다." }, 400);
  }
  if (!same(token, env.ADMIN_TOKEN)) return json({ error: "암호가 맞지 않습니다." }, 401);
  const value = await signature(env.ADMIN_TOKEN);
  return json({ ok: true }, 200, {
    "Set-Cookie": `${COOKIE}=${value}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=2592000`,
  });
}

/**
 * D1 연결. 처음 한 번 표를 만들고, 연결이 없으면 null.
 * (배포 직후 처음 저장할 때 "no such table: layouts" 로 실패하지 않게)
 */
let schemaReady = false;
async function database(env) {
  if (!env.DB) return null;
  if (!schemaReady) {
    await env.DB.prepare(SCHEMA_LAYOUTS).run();
    await env.DB.prepare(SCHEMA_SHAPES).run();
    schemaReady = true;
  }
  return env.DB;
}

/** 저장된 배치·선형의 버전. 고치면 updated_at 이, 지우면 줄 수가 바뀐다 */
async function versionOf(db, table) {
  const row = await db.prepare(`SELECT COUNT(*) n, COALESCE(MAX(updated_at), 0) v FROM ${table}`).first();
  const n = Number(row && row.n ? row.n : 0);
  const v = Number(row && row.v ? row.v : 0);
  return `${v}-${n}`;
}

/**
 * 표 하나를 TSV 로 돌려주는 공통 뼈대 (배치·노선 선형).
 * ETag 가 같으면 본문 없이 304 만 돌려준다 → 앱은 1분마다 물어도 데이터를 거의 쓰지 않는다.
 */
async function tsvResponse(request, env, table, headerName) {
  const db = await database(env);
  if (!db) return new Response("", { status: 503, headers: { ...WEB_HEADERS, "Cache-Control": "no-store" } });
  const version = await versionOf(db, table);
  const etag = `"${version}"`;
  const headers = {
    ...WEB_HEADERS,
    "Content-Type": "text/plain; charset=utf-8",
    "Cache-Control": "public, max-age=60",
    ETag: etag,
    [headerName]: version,
  };
  if (request.headers.get("If-None-Match") === etag) return new Response(null, { status: 304, headers });
  const rows = await db.prepare(`SELECT tsv FROM ${table} ORDER BY route_id`).all();
  const text = (rows.results || [])
    .map((r) => String((r && r.tsv) || "").trim())
    .filter(Boolean)
    .join("\n");
  return new Response(text ? text + "\n" : "", { headers });
}

/** 앱이 읽는 전체 배치 TSV (공개) */
const layoutsTsv = (request, env) => tsvResponse(request, env, "layouts", "X-Layout-Version");

/** 앱이 읽는 전체 노선 선형 TSV (공개) */
const shapesTsv = (request, env) => tsvResponse(request, env, "route_shapes", "X-Shape-Version");

function cleanTsv(body) {
  let raw = body;
  if (typeof raw === "object" && raw) raw = String(raw.tsv || "");
  // 한 줄만, 앞뒤 공백 제거
  const line = String(raw || "").trim().split(/\r?\n/)[0].trim();
  const parts = line.split("\t");
  if (parts.length < 2) return "";
  const id = parts[0].trim().replace(/^SEB/, "");
  if (!/^[A-Za-z0-9_-]{1,40}$/.test(id)) return "";
  const cells = parts[1].split(",").map((s) => s.trim()).filter(Boolean);
  if (!cells.length || cells.length > 300) return "";
  const out = [];
  for (const cell of cells) {
    const p = cell.split(":");
    if (p.length !== 3) return "";
    const node = p[0].trim();
    const x = Number(p[1]), y = Number(p[2]);
    if (!/^[A-Za-z0-9_-]{1,40}$/.test(node)) return "";
    if (!Number.isInteger(x) || !Number.isInteger(y) || x < 0 || y < 0 || x > 99 || y > 999) return "";
    out.push(`${node}:${x}:${y}`);
  }
  return `${id}\t${out.join(",")}`;
}

/**
 * 노선 선형 한 줄 검사: `노선ID \t 위도,경도 \t 위도변화,경도변화,…` (변화량은 1e-5도 정수).
 * 앱의 RouteShapes.parse 와 같은 형식이어야 하므로 여기서 형식을 강제한다.
 */
function cleanShapeTsv(body) {
  let raw = body;
  if (typeof raw === "object" && raw) raw = String(raw.tsv || "");
  const line = String(raw || "").trim().split(/\r?\n/)[0].trim();
  const parts = line.split("\t");
  if (parts.length < 3) return "";
  const id = parts[0].trim().replace(/^SEB/, "");
  if (!/^[A-Za-z0-9_-]{1,40}$/.test(id)) return "";
  const first = parts[1].split(",").map(Number);
  if (first.length !== 2 || !first.every(Number.isFinite)) return "";
  // 한반도 근처만 (잘못된 좌표가 지도에 튀는 것을 막음)
  if (!(first[0] > 33 && first[0] < 39 && first[1] > 124 && first[1] < 132)) return "";
  const deltas = parts[2].split(",").map((s) => s.trim()).filter(Boolean).map(Number);
  if (deltas.length < 2 || deltas.length % 2 !== 0 || deltas.length > 4000) return "";
  if (!deltas.every((n) => Number.isInteger(n) && Math.abs(n) < 100000)) return "";
  return `${id}\t${first[0].toFixed(5)},${first[1].toFixed(5)}\t${deltas.join(",")}`;
}

async function layouts(request, url, env) {
  const db = await database(env);
  if (!db) return json({ error: "데이터베이스가 연결되지 않았습니다." }, 503);
  const id = decodeURIComponent(url.pathname.slice("/w/admin/layouts".length).replace(/^\//, ""));
  if (!id) {
    if (request.method !== "GET") return json({ error: "GET 만 됩니다." }, 405);
    const rows = await db.prepare("SELECT route_id id, updated_at at, length(tsv) len FROM layouts ORDER BY updated_at DESC LIMIT 200").all();
    return json({ items: rows.results || [], version: await versionOf(db, "layouts") });
  }
  if (!/^[A-Za-z0-9_-]{1,40}$/.test(id)) return json({ error: "노선 ID 형식이 아닙니다." }, 400);

  if (request.method === "GET") {
    const row = await db.prepare("SELECT tsv, updated_at at FROM layouts WHERE route_id = ?").bind(id).first();
    return row ? json({ id, tsv: row.tsv, updatedAt: row.at }) : json({ error: "저장된 배치가 없습니다." }, 404);
  }
  if (request.method === "PUT" || request.method === "POST") {
    const raw = await request.text();
    if (raw.length > MAX_BODY) return json({ error: "너무 큽니다." }, 413);
    let body = raw;
    try {
      body = JSON.parse(raw);
    } catch {
      body = raw;
    }
    const tsv = cleanTsv(body);
    if (!tsv) return json({ error: "형식이 맞지 않습니다. (노선ID\\t노드:x:y,…)" }, 400);
    const at = Date.now();
    await db
      .prepare(
        "INSERT INTO layouts (route_id, tsv, updated_at) VALUES (?, ?, ?) ON CONFLICT(route_id) DO UPDATE SET tsv = excluded.tsv, updated_at = excluded.updated_at"
      )
      .bind(id, tsv, at)
      .run();
    return json({ ok: true, id, updatedAt: at, version: await versionOf(db, "layouts") });
  }
  if (request.method === "DELETE") {
    await db.prepare("DELETE FROM layouts WHERE route_id = ?").bind(id).run();
    return json({ ok: true, version: await versionOf(db, "layouts") });
  }
  return json({ error: "지원하지 않는 방식입니다." }, 405);
}

async function shapes(request, url, env) {
  const db = await database(env);
  if (!db) return json({ error: "데이터베이스가 연결되지 않았습니다." }, 503);
  const id = decodeURIComponent(url.pathname.slice("/w/admin/shapes".length).replace(/^\//, ""));
  if (!id) {
    if (request.method !== "GET") return json({ error: "GET 만 됩니다." }, 405);
    const rows = await db.prepare("SELECT route_id id, updated_at at, length(tsv) len FROM route_shapes ORDER BY updated_at DESC LIMIT 200").all();
    return json({ items: rows.results || [], version: await versionOf(db, "route_shapes") });
  }
  if (!/^[A-Za-z0-9_-]{1,40}$/.test(id)) return json({ error: "노선 ID 형식이 아닙니다." }, 400);

  if (request.method === "GET") {
    const row = await db.prepare("SELECT tsv, updated_at at FROM route_shapes WHERE route_id = ?").bind(id).first();
    return row ? json({ id, tsv: row.tsv, updatedAt: row.at }) : json({ error: "저장된 선형이 없습니다." }, 404);
  }
  if (request.method === "PUT" || request.method === "POST") {
    const raw = await request.text();
    if (raw.length > MAX_SHAPE_BODY) return json({ error: "너무 큽니다." }, 413);
    let body = raw;
    try {
      body = JSON.parse(raw);
    } catch {
      body = raw;
    }
    const tsv = cleanShapeTsv(body);
    if (!tsv) return json({ error: "형식이 맞지 않습니다. (노선ID\\t위도,경도\\t변화량,…)" }, 400);
    const at = Date.now();
    await db
      .prepare(
        "INSERT INTO route_shapes (route_id, tsv, updated_at) VALUES (?, ?, ?) ON CONFLICT(route_id) DO UPDATE SET tsv = excluded.tsv, updated_at = excluded.updated_at"
      )
      .bind(id, tsv, at)
      .run();
    return json({ ok: true, id, updatedAt: at, version: await versionOf(db, "route_shapes") });
  }
  if (request.method === "DELETE") {
    await db.prepare("DELETE FROM route_shapes WHERE route_id = ?").bind(id).run();
    return json({ ok: true, version: await versionOf(db, "route_shapes") });
  }
  return json({ error: "지원하지 않는 방식입니다." }, 405);
}

export async function admin(request, url, env) {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: WEB_HEADERS });
  if (url.pathname === "/w/layouts.tsv") return layoutsTsv(request, env);
  if (url.pathname === "/w/shapes.tsv") return shapesTsv(request, env);
  if (!url.pathname.startsWith("/w/admin")) return json({ error: "없는 주소입니다." }, 404);
  if (url.pathname === "/w/admin/login" && request.method === "POST") return login(request, env);
  if (url.pathname === "/w/admin/logout") {
    return json({ ok: true }, 200, { "Set-Cookie": `${COOKIE}=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0` });
  }
  if (!(await isAdmin(request, env))) return json({ error: "로그인이 필요합니다." }, 401);
  if (url.pathname === "/w/admin/session") return json({ ok: true });
  if (url.pathname === "/w/admin/layouts" || url.pathname.startsWith("/w/admin/layouts/")) return layouts(request, url, env);
  if (url.pathname === "/w/admin/shapes" || url.pathname.startsWith("/w/admin/shapes/")) return shapes(request, url, env);
  return json({ error: "없는 주소입니다." }, 404);
}
