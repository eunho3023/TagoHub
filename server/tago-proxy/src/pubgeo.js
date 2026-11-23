// 공공기관 위치 자료 (/w/pub/...): 카카오 대신 쓰는 장소·주소·주유소 (2026-11-23, 11월 30일부터 웹·앱이 이쪽으로)
// - 장소 이름 검색: 브이월드(국토교통부) 검색, 못 찾으면 카카오로 한 번 더
// - 주소 → 좌표, 좌표 → 주소·법정동: 브이월드 검색·지오코더
// - 주유소: 오피넷(한국석유공사) 반경 검색 (좌표가 KATEC 이라 바꿔서 주고받음)
// 결과는 웹·앱이 같이 쓰는 모양으로 정리해 돌려주고 Cloudflare 임시 저장에 둠. 요청한 사람은 함께 저장하지 않음

const VWORLD = "https://api.vworld.kr/req/";
const OPINET = "https://www.opinet.co.kr/api/aroundAll.do";
const KAKAO_KEYWORD = "https://dapi.kakao.com/v2/local/search/keyword.json";
const COORD = /^-?\d{1,3}(\.\d{1,17})?$/;
const HEADERS = { "Content-Type": "application/json; charset=utf-8", "Access-Control-Allow-Origin": "*" };

/** 웹·앱이 공공기관 자료로 바꾸는 날 (개인정보 처리방침 7일 전 공지, 2026-11-23 공지) */
export const PUB_FROM = Date.parse("2026-11-30T00:00:00+09:00");

// ── KATEC(TM128) ↔ 위도·경도 (오피넷 좌표) ──
// KATEC: Bessel 타원체, 원점 북위 38°·동경 128°, 축척 0.9999, 원점 값 (400000, 600000)
// 측지계 바꾸기는 proj4 의 KATEC 값 (+towgs84=-115.80,474.99,674.11,1.16,-2.31,-1.63,6.43, position vector)
const BESSEL = { a: 6377397.155, f: 1 / 299.1528128 };
const WGS84 = { a: 6378137, f: 1 / 298.257223563 };
const TOWGS = [-115.8, 474.99, 674.11, 1.16, -2.31, -1.63, 6.43];
const LAT0 = 38, LON0 = 128, K0 = 0.9999, FE = 400000, FN = 600000;
const RAD = Math.PI / 180, SEC = RAD / 3600;

function toXyz(lat, lon, { a, f }) {
  const e2 = f * (2 - f), p = lat * RAD, l = lon * RAD, n = a / Math.sqrt(1 - e2 * Math.sin(p) ** 2);
  return [n * Math.cos(p) * Math.cos(l), n * Math.cos(p) * Math.sin(l), n * (1 - e2) * Math.sin(p)];
}
function fromXyz([x, y, z], { a, f }) {
  const e2 = f * (2 - f), lon = Math.atan2(y, x), r = Math.hypot(x, y);
  let lat = Math.atan2(z, r * (1 - e2));
  for (let i = 0; i < 6; i++) { const n = a / Math.sqrt(1 - e2 * Math.sin(lat) ** 2); lat = Math.atan2(z + e2 * n * Math.sin(lat), r); }
  return [lat / RAD, lon / RAD];
}
/** Bessel → WGS84 (dir 1) 또는 거꾸로 (dir -1) */
function helmert([x, y, z], dir) {
  const [dx, dy, dz] = TOWGS.slice(0, 3).map((v) => v * dir);
  const [rx, ry, rz] = TOWGS.slice(3, 6).map((v) => v * SEC * dir);
  const s = 1 + (TOWGS[6] * 1e-6) * dir;
  if (dir > 0) return [dx + s * (x - rz * y + ry * z), dy + s * (rz * x + y - rx * z), dz + s * (-ry * x + rx * y + z)];
  const [u, v, w] = [x + dx, y + dy, z + dz];
  return [s * (u - rz * v + ry * w), s * (rz * u + v - rx * w), s * (-ry * u + rx * v + w)];
}
function meridian(p, a, e2) {
  const e4 = e2 * e2, e6 = e4 * e2;
  return a * ((1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * p - (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * Math.sin(2 * p)
    + (15 * e4 / 256 + 45 * e6 / 1024) * Math.sin(4 * p) - (35 * e6 / 3072) * Math.sin(6 * p));
}
/** 위도·경도(WGS84) → KATEC [x, y] (m) */
export function toKatec(lat, lng) {
  const [bl, bo] = fromXyz(helmert(toXyz(lat, lng, WGS84), -1), BESSEL);
  const { a, f } = BESSEL, e2 = f * (2 - f), ep2 = e2 / (1 - e2);
  const p = bl * RAD, n = a / Math.sqrt(1 - e2 * Math.sin(p) ** 2), t = Math.tan(p) ** 2, c = ep2 * Math.cos(p) ** 2;
  const A = (bo - LON0) * RAD * Math.cos(p), m = meridian(p, a, e2), m0 = meridian(LAT0 * RAD, a, e2);
  const x = FE + K0 * n * (A + (1 - t + c) * A ** 3 / 6 + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * A ** 5 / 120);
  const y = FN + K0 * (m - m0 + n * Math.tan(p) * (A * A / 2 + (5 - t + 9 * c + 4 * c * c) * A ** 4 / 24 + (61 - 58 * t + t * t + 600 * c - 330 * ep2) * A ** 6 / 720));
  return [x, y];
}
/** KATEC [x, y] → 위도·경도(WGS84) */
export function fromKatec(x, y) {
  const { a, f } = BESSEL, e2 = f * (2 - f), ep2 = e2 / (1 - e2), e4 = e2 * e2, e6 = e4 * e2;
  const m = meridian(LAT0 * RAD, a, e2) + (y - FN) / K0;
  const mu = m / (a * (1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256)), e1 = (1 - Math.sqrt(1 - e2)) / (1 + Math.sqrt(1 - e2));
  const p1 = mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * Math.sin(2 * mu) + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * Math.sin(4 * mu)
    + (151 * e1 ** 3 / 96) * Math.sin(6 * mu) + (1097 * e1 ** 4 / 512) * Math.sin(8 * mu);
  const n1 = a / Math.sqrt(1 - e2 * Math.sin(p1) ** 2), t1 = Math.tan(p1) ** 2, c1 = ep2 * Math.cos(p1) ** 2;
  const r1 = a * (1 - e2) / (1 - e2 * Math.sin(p1) ** 2) ** 1.5, d = (x - FE) / (n1 * K0);
  const lat = p1 - (n1 * Math.tan(p1) / r1) * (d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d ** 4 / 24
    + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d ** 6 / 720);
  const lon = LON0 * RAD + (d - (1 + 2 * t1 + c1) * d ** 3 / 6 + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d ** 5 / 120) / Math.cos(p1);
  return fromXyz(helmert(toXyz(lat / RAD, lon / RAD, BESSEL), 1), WGS84);
}

// ── 응답 정리 ──
const num = (v) => { const n = parseFloat(v); return Number.isFinite(n) ? n : null; };
const meters = (a, b, c, d) => Math.hypot((a - c) * 111000, (b - d) * 111000 * Math.cos(a * RAD));
const TRANSIT = /철도|지하철|버스터미널|터미널|공항|여객선|항만|역사/;

/** 브이월드 응답의 결과 목록 (없거나 오류면 []) */
function vwItems(body) {
  const r = body && body.response;
  if (!r || r.status !== "OK") return [];
  return (r.result && r.result.items) || [];
}

/**
 * 브이월드 장소 검색 결과 → [{name, address, lat, lng, category}].
 * 같은 이름·자리(같은 주소나 30m 안)는 하나로, 이름이 검색어와 같은 것 → 검색어로 시작 → 교통시설 → 가까운 곳 순.
 * 브이월드는 원래 순서가 들쭉날쭉(청주터미널 → 은행 지점 먼저)이라 다시 줄 세움
 */
export function vworldPlaces(body, query, near) {
  const q = String(query || "").replace(/\s+/g, "");
  const out = [];
  for (const it of vwItems(body)) {
    const lat = num(it.point && it.point.y), lng = num(it.point && it.point.x), name = String(it.title || "").trim();
    if (lat == null || lng == null || !name) continue;
    const a = it.address || {};
    // 같은 이름이 같은 주소이거나 30m 안이면 하나 (서울역 승강장이 여러 줄로 나왔음)
    if (out.some((p) => p.name === name && ((a.road || a.parcel) && p.address === (a.road || a.parcel) || meters(p.lat, p.lng, lat, lng) < 30))) continue;
    const category = String(it.category || "").split(">").map((s) => s.trim()).filter(Boolean).pop() || null;
    out.push({ name, address: a.road || a.parcel || null, lat, lng, category, transit: TRANSIT.test(String(it.category || "")) });
  }
  const rank = (p) => {
    const n = p.name.replace(/\s+/g, "");
    return (n === q ? 0 : n.startsWith(q) ? 1 : 2) * 2 + (p.transit ? 0 : 1);
  };
  const dist = (p) => (near ? meters(p.lat, p.lng, near[0], near[1]) : 0);
  return out.map((p, i) => ({ p, i, r: rank(p), d: dist(p) }))
    .sort((x, y) => x.r - y.r || (near ? x.d - y.d : x.i - y.i))
    .map(({ p }) => ({ name: p.name, address: p.address, lat: p.lat, lng: p.lng, category: p.category }));
}

/** 카카오 키워드 검색 결과 → 같은 모양 (브이월드가 못 찾았을 때) */
export function kakaoPlaces(body) {
  return ((body && body.documents) || []).map((d) => ({
    name: d.place_name, address: d.road_address_name || d.address_name || null,
    lat: num(d.y), lng: num(d.x), category: String(d.category_name || "").split(" > ").pop() || null,
  })).filter((p) => p.name && p.lat != null && p.lng != null);
}

/** 브이월드 주소 검색 → [{name 도로명(없으면 지번), address 지번·건물 이름, lat, lng}] */
export function vworldAddresses(body) {
  const out = [];
  for (const it of vwItems(body)) {
    const a = it.address || {}, lat = num(it.point && it.point.y), lng = num(it.point && it.point.x);
    if (lat == null || lng == null) continue;
    const name = (a.road || a.parcel || "").trim();
    if (!name || out.some((p) => p.name === name)) continue;
    out.push({ name, address: [a.road ? a.parcel : null, a.bldnm].filter(Boolean).join(" · ") || null, lat, lng });
  }
  return out;
}

/**
 * 브이월드 좌표 → 주소 → {name 꾹 누른 곳 이름, road, parcel, legal 법정동 코드 10자리, region "시·도 시·군·구 동"}.
 * name 은 건물 이름(있으면) → 도로명 → 지번 (카카오 때와 같은 순서)
 */
export function vworldWhere(body) {
  const r = body && body.response;
  if (!r || r.status !== "OK" || !Array.isArray(r.result)) return null;
  const road = r.result.find((x) => x.type === "road"), parcel = r.result.find((x) => x.type === "parcel");
  const s = (parcel || road || {}).structure || {};
  const legal = /^\d{10}$/.test(s.level4LC || "") ? s.level4LC : null;
  const building = (road && road.structure && road.structure.detail) || "";
  return {
    name: building.trim() || (road && road.text) || (parcel && parcel.text) || null,
    road: (road && road.text) || null, parcel: (parcel && parcel.text) || null, legal,
    region: [s.level1, s.level2, s.level4L || s.level3].filter(Boolean).join(" ") || null,
  };
}

const BRANDS = { SKE: "SK에너지", GSC: "GS칼텍스", HDO: "HD현대오일뱅크", SOL: "S-OIL", RTE: "알뜰주유소", RTX: "고속도로 알뜰", NHO: "농협 알뜰", ETC: "자가상표", E1G: "E1", SKG: "SK가스" };
/** 오피넷 반경 검색 → [{id, name, brand, price 휘발유 원, lat, lng, dist m}] (가까운 순) */
export function opinetStations(body) {
  const list = body && body.RESULT && body.RESULT.OIL;
  if (!Array.isArray(list)) return [];
  return list.map((o) => {
    const x = num(o.GIS_X_COOR), y = num(o.GIS_Y_COOR);
    if (x == null || y == null || !o.UNI_ID) return null;
    const [lat, lng] = fromKatec(x, y);
    return { id: String(o.UNI_ID), name: String(o.OS_NM || "주유소"), brand: BRANDS[o.POLL_DIV_CD] || null, price: num(o.PRICE), lat: +lat.toFixed(6), lng: +lng.toFixed(6), dist: Math.round(num(o.DISTANCE) || 0) };
  }).filter(Boolean).sort((a, b) => a.dist - b.dist);
}

// ── 요청 ──
const r5 = (v) => Number(v).toFixed(5);
const vwUrl = (path, params, key) => VWORLD + path + "?" + new URLSearchParams({ ...params, format: "json", errorformat: "json", key }).toString();

/** 경로·질의 → 할 일 { cacheKey, ttl, run(env, fetchJson) } (허락하지 않으면 null) */
export function pubRequest(pathname, search) {
  const p = new URLSearchParams(search);
  const near = () => { const x = p.get("x"), y = p.get("y"); return x && y && COORD.test(x) && COORD.test(y) ? [+r5(y), +r5(x)] : null; };
  if (pathname === "/w/pub/place") {
    const q = (p.get("q") || "").trim();
    if (!q || q.length > 40 || /[<>{}\\]/.test(q)) return null;
    const at = near();
    return {
      cacheKey: "place?" + new URLSearchParams({ q, ...(at ? { y: at[0], x: at[1] } : {}) }), ttl: 86400,
      async run(env, get) {
        const body = await get(vwUrl("search", { service: "search", request: "search", version: "2.0", crs: "EPSG:4326", size: "30", page: "1", query: q, type: "place" }, env.VWORLD_KEY)).catch(() => null);
        const places = vworldPlaces(body, q, at).slice(0, 15);
        if (places.length || !env.KAKAO_REST_KEY) return { src: "vworld", places };
        // 브이월드가 답을 못 함(오류) → 카카오 결과는 5분만 저장 (브이월드가 돌아오면 바로 브이월드로)
        const short = !body;
        // 브이월드가 못 찾음(띄어 쓴 검색어 "스타벅스 강남" 등) → 카카오
        const qs = new URLSearchParams({ query: q, size: "15", ...(at ? { x: at[1], y: at[0], sort: "accuracy" } : {}) });
        const kb = await get(KAKAO_KEYWORD + "?" + qs, { Authorization: "KakaoAK " + env.KAKAO_REST_KEY.trim() }).catch(() => null);
        return { src: "kakao", places: kakaoPlaces(kb), ...(short ? { short: true } : {}) };
      },
    };
  }
  if (pathname === "/w/pub/address") {
    const q = (p.get("q") || "").trim();
    if (!q || q.length > 60 || /[<>{}\\]/.test(q)) return null;
    return {
      cacheKey: "address?" + new URLSearchParams({ q }), ttl: 86400,
      async run(env, get) {
        const ask = (category) => get(vwUrl("search", { service: "search", request: "search", version: "2.0", crs: "EPSG:4326", size: "10", page: "1", query: q, type: "address", category }, env.VWORLD_KEY)).catch(() => null);
        let places = vworldAddresses(await ask("road"));
        if (!places.length) places = vworldAddresses(await ask("parcel"));
        return { places: places.slice(0, 5) };
      },
    };
  }
  if (pathname === "/w/pub/where") {
    const lat = p.get("lat"), lng = p.get("lng");
    if (!lat || !lng || !COORD.test(lat) || !COORD.test(lng)) return null;
    // 약 10m 칸으로 맞춰 같은 자리 요청끼리 저장을 같이 씀
    const la = (+lat).toFixed(4), lo = (+lng).toFixed(4);
    return {
      cacheKey: `where?lat=${la}&lng=${lo}`, ttl: 86400,
      async run(env, get) {
        const body = await get(vwUrl("address", { service: "address", request: "getAddress", version: "2.0", crs: "epsg:4326", point: `${lo},${la}`, type: "both", zipcode: "false", simple: "false" }, env.VWORLD_KEY));
        return vworldWhere(body) || { name: null, road: null, parcel: null, legal: null, region: null };
      },
    };
  }
  if (pathname === "/w/pub/gas") {
    const lat = p.get("lat"), lng = p.get("lng"), rad = Math.round(+(p.get("r") || 3000));
    if (!lat || !lng || !COORD.test(lat) || !COORD.test(lng) || !(rad >= 300 && rad <= 5000)) return null;
    // 약 500m 칸·500m 단위 반경으로 맞춤. 기름값이 바뀌니 10분만
    const la = (Math.round(+lat * 200) / 200).toFixed(3), lo = (Math.round(+lng * 200) / 200).toFixed(3), r = Math.ceil(rad / 500) * 500;
    return {
      cacheKey: `gas?lat=${la}&lng=${lo}&r=${r}`, ttl: 600,
      async run(env, get) {
        const [x, y] = toKatec(+la, +lo);
        const qs = new URLSearchParams({ code: env.OPINET_KEY || "", x: x.toFixed(1), y: y.toFixed(1), radius: String(r), prodcd: "B027", sort: "2", out: "json" });
        return { stations: opinetStations(await get(OPINET + "?" + qs)) };
      },
    };
  }
  return null;
}

/** 키 이름 (없으면 그 요청은 500) */
const NEEDS = { "/w/pub/place": "VWORLD_KEY", "/w/pub/address": "VWORLD_KEY", "/w/pub/where": "VWORLD_KEY", "/w/pub/gas": "OPINET_KEY" };

/** GET /w/pub/... (src/index.js). limit: 저장에 없을 때만 주소마다 분당 제한 */
export async function handlePub(request, url, env, ctx, { fetcher = fetch, limit = null } = {}) {
  const job = pubRequest(url.pathname, url.search);
  if (!job) return new Response(JSON.stringify({ error: "bad request" }), { status: 400, headers: HEADERS });
  if (!env[NEEDS[url.pathname]]) return new Response(JSON.stringify({ error: "server key missing" }), { status: 500, headers: HEADERS });
  const cache = typeof caches !== "undefined" ? caches.default : null;
  // pub2: 첫 배포 때 브이월드가 막혀 카카오·빈 결과가 저장됐던 것 버림
  const key = new Request("https://cache.tagohub/pub2/" + job.cacheKey);
  const hit = cache && await cache.match(key);
  if (hit) { const out = new Response(hit.body, hit); out.headers.set("X-Cache", "HIT"); return out; }
  if (limit && !(await limit())) return new Response(JSON.stringify({ error: "too many requests" }), { status: 429, headers: { ...HEADERS, "Retry-After": "60" } });
  const get = async (u, headers = {}) => {
    const res = await fetcher(u, { headers: { "User-Agent": "TagoHub", Accept: "application/json", ...headers } });
    if (!res.ok) throw new Error("HTTP " + res.status);
    return JSON.parse((await res.text()).trim());
  };
  try {
    const data = await job.run(env, get);
    const short = !!data.short;
    delete data.short;
    const out = new Response(JSON.stringify(data), { headers: { ...HEADERS, "Cache-Control": "public, max-age=" + (short ? 300 : job.ttl), "X-Cache": "MISS" } });
    // 빈 결과는 짧게 (공공기관이 잠깐 안 될 때 하루 동안 빈 답이 나가지 않게)
    const empty = Array.isArray(data.places) ? !data.places.length : Array.isArray(data.stations) ? !data.stations.length : !data.name;
    if (cache) {
      const keep = out.clone();
      if (empty || short) keep.headers.set("Cache-Control", "public, max-age=300");
      ctx.waitUntil(cache.put(key, keep));
    }
    return out;
  } catch (e) {
    return new Response(JSON.stringify({ error: "upstream", detail: String(e && e.message || e).slice(0, 80) }), { status: 502, headers: HEADERS });
  }
}
