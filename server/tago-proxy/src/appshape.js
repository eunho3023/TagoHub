// 앱이 만든 노선 모양을 모두가 같이 씀: 앱(폰)이 노선 선을 만들면(카카오 경로·지역 공식 모양) 서버에 올리고,
// 다른 폰과 웹 버전은 그 모양을 그대로 받아 그림 → 앱과 웹의 노선 선이 같음.
// GET  /appshape/<노선 id>          (앱 토큰) → {poly} | 404
// PUT  /appshape/<노선 id> {poly}   (앱 토큰) → 처음 올린 것만 저장 (이미 있으면 그대로, kept:false)
// 웹: /w/shape/<노선 id> 가 이 모양을 먼저 돌려줌 (src/shape.js handleShape)

import { decode, distToPath, bucket } from "./shape.js";

const HEAD = { "Content-Type": "application/json; charset=utf-8" };
const MAX_POINTS = 8000;
let made = false;

async function ensure(db) {
  if (made) return;
  await db.prepare("CREATE TABLE IF NOT EXISTS app_shapes (route_id TEXT PRIMARY KEY, poly TEXT NOT NULL, made INTEGER NOT NULL) WITHOUT ROWID").run();
  made = true;
}

/** 저장된 앱 모양 (폴리라인 글자) 또는 null */
export async function appShape(db, id) {
  if (!db) return null;
  await ensure(db);
  const r = await db.prepare("SELECT poly FROM app_shapes WHERE route_id = ?").bind(id).first();
  return r?.poly || null;
}

/**
 * 올라온 모양 검사: 점 2~8000개, 나라 안, 정류장 좌표를 알면 80% 이상이 선에서 120m 안 (엉뚱한 선이 모두에게 퍼지지 않게).
 * 맞으면 null, 아니면 이유
 */
export function checkShape(poly, stops) {
  if (typeof poly !== "string" || poly.length < 4 || poly.length > 200000) return "bad poly";
  let pts;
  try { pts = decode(poly, 5); } catch { return "bad poly"; }
  if (pts.length < 2 || pts.length > MAX_POINTS) return "bad size";
  if (!pts.every(([a, b]) => a >= 32.5 && a <= 39 && b >= 124 && b <= 132)) return "out of range";
  if (stops && stops.length >= 2) {
    const ok = stops.filter((s) => distToPath(s, pts) <= 120).length;
    if (ok < stops.length * 0.8) return "not on stops";
  }
  return null;
}

/** 자료 조각의 정류장 좌표 [[위도, 경도]] (조각에 없는 노선은 null → 정류장 검사 없이) */
async function fileStops(env, id) {
  if (!env.ASSETS) return null;
  try {
    const r = await env.ASSETS.fetch(new Request(`https://assets.local/data/routes/${bucket(id)}.json`));
    if (!r.ok) return null;
    const route = (await r.json())[id];
    return route ? route.stops.filter((s) => s[3] != null && s[4] != null).map((s) => [s[3], s[4]]) : null;
  } catch { return null; }
}

export async function handleAppShape(request, url, env) {
  const id = decodeURIComponent(url.pathname.slice("/appshape/".length));
  if (!/^[A-Za-z0-9_-]{1,32}$/.test(id)) return new Response("not allowed", { status: 404, headers: HEAD });
  if (!env.DB) return new Response("no db", { status: 503, headers: HEAD });
  if (request.method === "GET") {
    const poly = await appShape(env.DB, id);
    return poly ? new Response(JSON.stringify({ poly }), { headers: HEAD }) : new Response("none", { status: 404, headers: HEAD });
  }
  if (request.method !== "PUT" && request.method !== "POST") return new Response("GET, PUT only", { status: 405, headers: HEAD });
  let body;
  try { body = JSON.parse(await request.text()); } catch { return new Response("bad json", { status: 400, headers: HEAD }); }
  const why = checkShape(body?.poly, await fileStops(env, id));
  if (why) return new Response(why, { status: 400, headers: HEAD });
  await ensure(env.DB);
  const res = await env.DB.prepare("INSERT INTO app_shapes (route_id, poly, made) VALUES (?, ?, ?) ON CONFLICT(route_id) DO NOTHING")
    .bind(id, body.poly, Math.floor(Date.now() / 1000)).run();
  return new Response(JSON.stringify({ ok: true, kept: !!res?.meta?.changes }), { headers: HEAD });
}
