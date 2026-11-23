// 지도 층: CCTV(국가교통정보센터 고속도로·국도), 휴게소(한국도로공사: 주유 가격·편의시설·브랜드·대표 메뉴), 주유소(카카오 장소).
// 앱 지도설정의 "CCTV"·"휴게소" 층과 검색의 "주유소"와 같은 자료. 그리기·켜기는 topis.js 교통 층
import { getText, getJson, esc, cachedText, tryLater } from "./core.js";
import { act } from "./ui.js";
import { gasPrices } from "./road.js";

// ── CCTV ──
export const CCTV_ZOOM = 12;
const CCTV_MAX = 200;
const CCTV_COLOR = "#37474f";
let cctvP = null, cctvAt = 0;
/** 고속도로·국도 CCTV 전체 [{id, name, lat, lng, url, road}] (서버가 10분 저장, 여기서는 10분마다 다시) */
export function cctvAll() {
  if (!cctvP || Date.now() - cctvAt > 600000) {
    cctvAt = Date.now();
    cctvP = Promise.all(["ex", "its"].map((t) => getJson("w/ov/cctv/" + t).then((rows) => (Array.isArray(rows) ? rows : [])
      .map(([name, lat, lng, url], i) => ({ id: `${t}:${i}`, name, lat, lng, url, road: t === "ex" ? "고속도로" : "국도" })))
      .catch(() => []))).then(([a, b]) => {
        const all = a.concat(b);
        if (!all.length) cctvP = null;
        return all;
      });
  }
  return cctvP;
}
/** 화면 안, 가운데에서 가까운 순 */
function nearCenter(list, b, max) {
  const cla = (b.s + b.n) / 2, clo = (b.w + b.e) / 2;
  return list.filter((x) => x.lat >= b.s && x.lat <= b.n && x.lng >= b.w && x.lng <= b.e)
    .map((x) => [x, Math.hypot(x.lat - cla, (x.lng - clo) * 0.8)]).sort((p, q) => p[1] - q[1]).slice(0, max).map(([x]) => x);
}
export function cctvDots(list, b) {
  if (!b) return [];
  return nearCenter(list, b, CCTV_MAX).map((x) => ({ id: "cctv:" + x.id, lat: x.lat, lng: x.lng, color: CCTV_COLOR, ring: "#fff", r: 7, label: "C" }));
}
export function cctvCard(x) {
  return `<div><b>${esc(x.name)}</b><small>${x.road} CCTV · 국가교통정보센터</small></div><button class="btn" ${act(() => playCctv(x))}>영상 보기</button>`;
}

// 영상: 공공 영상 서버(cctvsec.ktict.co.kr)는 https 와 다른 사이트에서 읽기(CORS)를 허락함 → http 주소를 https 로 바꿔 바로 재생.
// 사파리는 HLS 를 바로 틀고, 나머지 브라우저는 hls.js 로
const HLS_JS = "https://cdnjs.cloudflare.com/ajax/libs/hls.js/1.5.13/hls.min.js";
let hlsP = null;
const loadHls = () => hlsP || (hlsP = new Promise((res, rej) => {
  const s = document.createElement("script");
  s.src = HLS_JS;
  s.onload = () => (window.Hls ? res(window.Hls) : rej(new Error("hls.js")));
  s.onerror = () => { hlsP = null; rej(new Error("hls.js")); };
  document.head.appendChild(s);
}));
let player = null;
function closeCctv() {
  if (!player) return;
  try { player.hls?.destroy(); } catch (e) { /* 이미 닫힘 */ }
  player.box.remove();
  document.removeEventListener("keydown", player.onKey);
  player = null;
}
export async function playCctv(x) {
  closeCctv();
  const box = document.createElement("div");
  box.className = "cctvmodal";
  box.innerHTML = `<div class="cctvbox"><div class="cctvhead"><b>${esc(x.name)}</b><button class="cctvx" aria-label="닫기">✕</button></div>
    <video muted playsinline autoplay controls></video><small class="cctvnote">영상을 불러오는 중...</small>
    <small class="muted">국가교통정보센터(ITS) 제공 영상이에요. 기기나 영상 제공 상태에 따라 나오지 않을 수 있어요.</small></div>`;
  document.body.appendChild(box);
  const onKey = (e) => { if (e.key === "Escape") closeCctv(); };
  player = { box, hls: null, onKey };
  document.addEventListener("keydown", onKey);
  box.addEventListener("click", (e) => { if (e.target === box || e.target.closest(".cctvx")) closeCctv(); });
  const me = player, video = box.querySelector("video"), noteEl = box.querySelector(".cctvnote");
  const fail = () => { if (player === me) noteEl.textContent = "영상을 열지 못했어요. " + tryLater(); };
  video.addEventListener("playing", () => { if (player === me) noteEl.textContent = ""; });
  const src = x.url.replace(/^http:\/\//, "https://");
  if (video.canPlayType("application/vnd.apple.mpegurl")) {
    video.src = src;
    video.addEventListener("error", fail);
    return;
  }
  try {
    const Hls = await loadHls();
    if (player !== me) return;
    if (!Hls.isSupported()) return fail();
    const hls = new Hls({ lowLatencyMode: false, manifestLoadingMaxRetry: 2 });
    me.hls = hls;
    hls.on(Hls.Events.ERROR, (_, d) => { if (d.fatal) fail(); });
    hls.loadSource(src);
    hls.attachMedia(video);
  } catch (e) { fail(); }
}

// ── 휴게소 ──
export const REST_ZOOM = 9;
const REST_COLOR = "#2E7D32";
let restP = null;
/** 고속도로 휴게소 위치 [{id, cd, name, route, lat, lng}] (서버에 올린 앱과 같은 파일 ex_places.json) */
export function restAll() {
  if (!restP) restP = cachedText("data/files/ex_places.json").then((t) => (JSON.parse(t).ex_rest || []).map((r) => ({
    id: r.stdRestCd || r.unitCode, cd: r.stdRestCd || "", name: (r.unitName || "").trim(), route: r.routeName || "",
    lat: parseFloat(r.yValue), lng: parseFloat(r.xValue),
  })).filter((r) => r.name && r.lat > 32 && r.lng > 124)).catch(() => { restP = null; return []; });
  return restP;
}
export function restDots(list, b) {
  if (!b) return [];
  return nearCenter(list, b, 150).map((x) => ({ id: "rest:" + x.id, lat: x.lat, lng: x.lng, color: REST_COLOR, ring: "#fff", r: 8, label: "휴" }));
}
/** "서울만남(부산)휴게소" 와 "서울만남(부산)주유소" 를 같은 곳으로 (앱 ExRestParser.root) */
const restRoot = (n) => String(n || "").trim().replace(/(휴게소|주유소|졸음쉼터)$/, "");
const won = (n) => (n ? n.toLocaleString("ko-KR") + "원" : null);
const restMemo = new Map();
const rows = (path, cd) => getText("w/ex/restinfo/" + path, { stdRestCd: cd }).then((b) => JSON.parse(b).list || []).catch(() => []);
/** 휴게소 카드: 먼저 이름·노선, 받는 대로 주유 가격·편의시설·브랜드·대표 메뉴 */
export function restCard(x, fill) {
  const head = `<div><b>${esc(x.name)}</b><small>${esc(x.route)} 휴게소</small>`;
  if (!restMemo.has(x.id)) {
    restMemo.set(x.id, Promise.all([
      gasPrices().catch(() => []),
      x.cd ? rows("restConvList", x.cd) : [], x.cd ? rows("restBrandList", x.cd) : [], x.cd ? rows("restBestfoodList", x.cd) : [],
    ]).then(([gas, conv, brand, food]) => {
      const g = gas.find((v) => restRoot(v.name) === restRoot(x.name));
      const uniq = (a) => [...new Set(a.filter(Boolean))];
      const foods = food.filter((f) => f.foodNm).sort((a, b) => ((b.recommendyn === "Y" || b.bestfoodyn === "Y") - (a.recommendyn === "Y" || a.bestfoodyn === "Y")))
        .slice(0, 5).map((f) => f.foodNm + (f.foodCost ? " " + won(parseInt(f.foodCost, 10)) : ""));
      return [
        g && ["주유" + (g.company ? " " + g.company : ""), ["휘발유 " + won(g.gasoline), "경유 " + won(g.diesel), g.lpg ? "LPG " + won(g.lpg) : null].filter((v) => v && !v.endsWith("null")).join(" · ")],
        ["편의시설", uniq(conv.map((c) => c.psName)).join(" · ")],
        ["브랜드", uniq(brand.map((c) => c.brdName)).join(" · ")],
        ["대표 메뉴", uniq(foods).join(" · ")],
      ].filter((l) => l && l[1]);
    }).catch(() => []));
  }
  restMemo.get(x.id).then((lines) => fill(head + (lines.length ? lines.map(([k, v]) => `<small><b>${esc(k)}</b> ${esc(v)}</small>`).join("") : "<small>자세한 정보가 없어요</small>") + "</div>"));
  return head + "<small>주유 가격·편의시설을 불러오는 중...</small></div>";
}

// ── 주유소 (카카오 장소 분류 OL7) ──
// 카카오 장소 검색은 웹에서 11월 29일부터 (개인정보 처리방침 7일 전 공지, places.js KAKAO_ROUTING_FROM 과 같은 날)
export const GAS_FROM = Date.parse("2026-11-29T00:00:00+09:00");
export const GAS_ON = () => Date.now() >= GAS_FROM;
export const GAS_ZOOM = 14;
const GAS_COLOR = "#E65100";
const CELL = 0.02;
const cellP = new Map();
/** 0.02도 칸 하나의 주유소 (세 쪽, 45곳까지). 칸마다 한 번만 */
function gasCell(a, o) {
  const k = a + "_" + o;
  if (!cellP.has(k)) {
    const rect = [o * CELL, a * CELL, (o + 1) * CELL, (a + 1) * CELL].map((v) => v.toFixed(5)).join(",");
    const page = (n) => getJson("w/kakao/category", { code: "OL7", rect, page: n });
    cellP.set(k, (async () => {
      const out = [];
      for (let n = 1; n <= 3; n++) {
        const r = await page(n);
        out.push(...(r.documents || []));
        if (r.meta?.is_end !== false) break;
      }
      return out.map((d) => ({ id: d.id, name: d.place_name, lat: parseFloat(d.y), lng: parseFloat(d.x), addr: d.road_address_name || d.address_name || "", phone: d.phone || "", url: d.place_url || "" }));
    })().catch(() => { cellP.delete(k); return []; }));
  }
  return cellP.get(k);
}
/** 화면 범위의 주유소 (가운데 칸부터 9칸까지) */
export async function gasIn(b) {
  if (!GAS_ON()) return [];
  const cla = (b.s + b.n) / 2 / CELL, clo = (b.w + b.e) / 2 / CELL;
  const cells = [];
  for (let a = Math.floor(b.s / CELL); a <= Math.floor(b.n / CELL); a++)
    for (let o = Math.floor(b.w / CELL); o <= Math.floor(b.e / CELL); o++) cells.push([a, o, Math.hypot(a + 0.5 - cla, o + 0.5 - clo)]);
  const lists = await Promise.all(cells.sort((x, y) => x[2] - y[2]).slice(0, 9).map(([a, o]) => gasCell(a, o)));
  const seen = new Set();
  return lists.flat().filter((g) => !seen.has(g.id) && seen.add(g.id));
}
export function gasDots(list, b) {
  if (!b) return [];
  return nearCenter(list, b, 120).map((x) => ({ id: "gas:" + x.id, lat: x.lat, lng: x.lng, color: GAS_COLOR, ring: "#fff", r: 8, label: "주" }));
}
export function gasCard(x) {
  return `<div><b>${esc(x.name)}</b>${x.addr ? `<small>${esc(x.addr)}</small>` : ""}${x.phone ? `<small>${esc(x.phone)}</small>` : ""}</div>` +
    (x.url ? `<a class="btn" href="${esc(x.url.replace(/^http:/, "https:"))}" target="_blank" rel="noopener">카카오맵</a>` : "");
}
