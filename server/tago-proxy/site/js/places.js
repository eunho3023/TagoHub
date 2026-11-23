// 길찾기 (카카오): 장소 찾기 /w/kakao/place(11월 23일부터 공공기관 /w/pub/place), 대중교통 /w/kakao/transit, 자동차 /w/kakao/car(카카오모빌리티), 도보·자전거 /w/kakao/walk·bicycle.
// 앱처럼 수단마다 걸리는 시간을 함께 보여 주고, 경유지 5곳까지. 집·회사·학교 저장, 최근 목적지.
import { getText, esc, distText, onReturn, tryLater, PUB_ON } from "./core.js";
import { act, header, note, sectionHead, bindInput, chips } from "./ui.js";
import { go, isWide } from "./nav.js";
import { MapView } from "./map.js";
import { HomeWork, Recent } from "./store.js";
import { currentLocation } from "./alight.js";

export const MY_PLACE = "내 위치";
/**
 * 길찾기·장소 검색은 좌표를 카카오로 보내야 해서 웹에서는 막아 두었다가(2026-11-05), 개인정보 처리방침·이용약관을 바꾸고
 * 공지(2026-11-22, notices.json)한 뒤 2026-11-23 부터 켬 (처음엔 11-29 였다가 사용자 결정으로 앞당김)
 */
export const KAKAO_ROUTING_FROM = Date.parse("2026-11-23T00:00:00+09:00");
export const KAKAO_ROUTING = Date.now() >= KAKAO_ROUTING_FROM;
/** 길찾기 상태 (다른 화면에 다녀와도 남음) */
export const dir = { from: null, to: null, vias: [], editing: "from", q: "", routes: null, status: null, picked: null, mode: "transit", other: {} };
/** 경유지 최대 (카카오모빌리티 자동차 길찾기 한도와 같음, 앱 KakaoDrive.MAX_WAYPOINTS) */
const MAX_VIAS = 5;
/** 경유지마다 대중교통 갈아타기 여유 (앱 TripController.joinVia 와 같게 3분) */
const VIA_BUFFER_S = 180;
const MODES = [["transit", "대중교통"], ["car", "자동차"], ["walk", "도보"], ["bicycle", "자전거"]];
const MODE_COLOR = { car: "#2563eb", walk: "#16A34A", bicycle: "#E08A00" };
/** 출발 → 경유지들 → 도착 의 구간들 */
const legsOf = () => { const pts = [dir.from, ...dir.vias, dir.to]; return pts.slice(1).map((b, i) => [pts[i], b]); };
/** 출발·경유·도착이 바뀌면 받은 경로를 모두 버림 */
function changed() { dir.routes = null; dir.picked = null; dir.other = {}; }

export async function searchPlaces(q, near) {
  // 11월 23일부터 공공기관(브이월드, 못 찾으면 서버가 카카오로 한 번 더): 서버가 정리한 모양 그대로
  if (PUB_ON()) {
    const body = JSON.parse(await getText("w/pub/place", { q: q.trim(), x: near?.lng, y: near?.lat }));
    return (body.places || []).filter((p) => p.name && Number.isFinite(p.lat) && Number.isFinite(p.lng));
  }
  const body = JSON.parse(await getText("w/kakao/place", { q: q.trim(), x: near?.lng, y: near?.lat }));
  return (body.documents || []).map((m) => ({
    name: m.place_name, address: m.road_address_name || m.address_name || null,
    lat: parseFloat(m.y), lng: parseFloat(m.x), category: (m.category_name || "").split(" > ").pop() || null,
  })).filter((p) => p.name && Number.isFinite(p.lat) && Number.isFinite(p.lng));
}

async function transit(from, to) {
  const body = JSON.parse(await getText("w/kakao/transit", { sx: from.lng, sy: from.lat, ex: to.lng, ey: to.lat }));
  const routes = (body.routes || []).map((r) => {
    const p = r.properties || {};
    return {
      totalDistance: +p.totalDistance || 0, totalTime: +p.totalTime || 0, transfers: +p.transfers || 0, fare: p.fare?.value != null ? +p.fare.value : null,
      steps: (r.steps || []).map((s) => {
        const sp = s.properties || {};
        return {
          type: sp.type, guidance: sp.guidance || null, distance: +sp.distance || 0, time: +sp.time || 0,
          stops: (sp.stops || []).map((x) => x.name).filter(Boolean), vehicles: (sp.vehicles || []).map((x) => x.name).filter(Boolean),
          points: (s.path?.points || []).map((xy) => [parseFloat(xy[1]), parseFloat(xy[0])]).filter((p2) => Number.isFinite(p2[0])),
        };
      }).filter((s) => s.type),
    };
  });
  return { status: body.status || "INVALID_RESPONSE", routes };
}

/** 대중교통 (경유지가 있으면 구간마다 받아 빠른 3개씩 이어 붙임, 경유마다 3분 여유 — 앱 TripController.plan 과 같게) */
async function transitAll() {
  const legs = legsOf();
  const res = await Promise.all(legs.map(([a, b]) => transit(a, b)));
  if (res.length === 1) return res[0];
  const bad = res.find((r) => !r.routes.length);
  if (bad) return { status: bad.status, routes: [] };
  let combos = [{ parts: [], t: 0 }];
  for (const r of res) {
    const top = [...r.routes].sort((x, y) => x.totalTime - y.totalTime).slice(0, 3);
    combos = combos.flatMap((c) => top.map((x) => ({ parts: [...c.parts, x], t: c.t + x.totalTime }))).sort((a, b) => a.t - b.t).slice(0, 9);
  }
  return { status: "OK", routes: combos.slice(0, 6).map((c) => joinRoutes(c.parts)) };
}
function joinRoutes(parts) {
  const steps = [];
  parts.forEach((p, i) => {
    if (i) steps.push({ type: "VIA", name: dir.vias[i - 1]?.name || "경유지", time: VIA_BUFFER_S, distance: 0, stops: [], vehicles: [], points: [] });
    steps.push(...p.steps);
  });
  const sum = (f) => parts.reduce((a, p) => a + f(p), 0);
  return {
    totalTime: sum((p) => p.totalTime) + VIA_BUFFER_S * (parts.length - 1), totalDistance: sum((p) => p.totalDistance),
    transfers: sum((p) => p.transfers), fare: parts.every((p) => p.fare != null) ? sum((p) => p.fare) : null, steps,
  };
}

/** 도보·자전거 한 구간 {sec, m, pts} (못 찾으면 null) */
async function rideLeg(kind, a, b) {
  const body = JSON.parse(await getText("w/kakao/" + kind, { sx: a.lng, sy: a.lat, ex: b.lng, ey: b.lat }));
  const r = body.route || {}, p = r.properties || {}, pts = [];
  for (const leg of r.legs || []) for (const st of leg.steps || []) for (const xy of st.path?.points || []) {
    const q = [parseFloat(xy[1]), parseFloat(xy[0])];
    if (Number.isFinite(q[0]) && Number.isFinite(q[1])) pts.push(q);
  }
  return body.status === "OK" && pts.length >= 2 ? { sec: +p.totalTime || 0, m: +p.totalDistance || 0, pts } : null;
}
/** 도보·자전거: 경유지가 있으면 구간을 이어 붙임 */
async function ride(kind) {
  const legs = await Promise.all(legsOf().map(([a, b]) => rideLeg(kind, a, b)));
  if (legs.some((x) => !x)) return { none: true };
  return { sec: legs.reduce((a, x) => a + x.sec, 0), m: legs.reduce((a, x) => a + x.m, 0), pts: legs.flatMap((x) => x.pts) };
}
/** 자동차 (카카오모빌리티, 경유지는 한 번에): {sec, m, toll, taxi, pts} */
async function car() {
  const body = JSON.parse(await getText("w/kakao/car", {
    sx: dir.from.lng, sy: dir.from.lat, ex: dir.to.lng, ey: dir.to.lat, via: dir.vias.map((v) => v.lng + "," + v.lat).join("|"),
  }));
  const r = (body.routes || [])[0];
  if (!r || r.result_code !== 0 || !r.summary) return { none: true, msg: r?.result_msg || null };
  const pts = [];
  for (const sec of r.sections || []) for (const road of sec.roads || []) {
    const v = road.vertexes || [];
    for (let i = 0; i + 1 < v.length; i += 2) pts.push([+v[i + 1], +v[i]]);
  }
  const s = r.summary;
  return { sec: +s.duration || 0, m: +s.distance || 0, toll: s.fare?.toll ?? null, taxi: s.fare?.taxi ?? null, pts };
}

const MESSAGES = { EQUAL_POINTS: "출발지와 도착지가 같아요", STARTNODES_NULL: "출발지 근처에 대중교통이 없어요", ENDNODES_NULL: "도착지 근처에 대중교통이 없어요", NO_RESULTS: "대중교통 경로를 찾지 못했어요" };
const duration = (min) => (min >= 60 ? `${Math.floor(min / 60)}시간${min % 60 ? " " + (min % 60) + "분" : ""}` : min + "분");
const won = (n) => n.toLocaleString("ko-KR") + "원";
const stepColor = (t) => (t === "BUS" ? "#2E9E5B" : t === "SUBWAY" ? "#3B7DDD" : t === "VIA" ? "#E08A00" : "#9E9E9E");
const label = (s) => (s.type === "BUS" ? "버스 " + (s.vehicles[0] || s.guidance || "") : s.type === "SUBWAY" ? s.vehicles[0] || s.guidance || "지하철" : s.type === "VIA" ? "경유 " + s.name : `걷기 ${Math.ceil(s.time / 60)}분`);

/** 홈·집/회사에서: 도착은 고른 곳, 출발은 내 위치 (못 받으면 출발을 고르게) */
export function startDirections(to) {
  if (!KAKAO_ROUTING) { go("navi"); return; }
  Object.assign(dir, { to, from: null, vias: [], editing: "from", q: "", routes: null, picked: null, other: {} });
  go("navi");
  currentLocation().then((here) => {
    if (here && !dir.from) { dir.from = { name: MY_PLACE, lat: here[0], lng: here[1], address: null }; dir.editing = null; changed(); window.dispatchEvent(new Event("hashchange")); }
  });
}

export function directionsScreen(root, p, ctx) {
  if (!KAKAO_ROUTING) {
    root.innerHTML = header("길찾기", "대중교통 길찾기") + note("준비 중이에요") +
      note("11월 29일부터 웹에서도 길찾기와 장소 검색을 할 수 있어요 (공지사항 참고). 그 전에는 출발·도착 좌표를 보내지 않아서, 정류장 찾기·내 주변 정류장·내 위치는 이 기기 안에서만 처리돼요.");
    return null;
  }
  let alive = true, seq = 0;
  const box = (k, title, place) => `<button class="pbox ${dir.editing === k ? "on" : ""}" ${act(() => { dir.editing = k; dir.q = ""; draw(); })}><small>${title}</small><b>${esc(place?.name || "장소 찾기")}</b></button>`;
  const use = (pl) => {
    if (dir.editing === "via") { if (dir.vias.length < MAX_VIAS) dir.vias.push(pl); }
    else if (dir.editing === "from") dir.from = pl; else dir.to = pl;
    dir.editing = dir.editing === "from" && !dir.to ? "to" : null;
    dir.q = ""; changed();
    draw();
  };
  const sig = () => JSON.stringify([dir.from, ...dir.vias, dir.to].map((x) => x && [x.lat, x.lng]));
  /** 자동차·도보·자전거 시간도 함께 받음 (수단 칩에 시간, 앱 PlanList 처럼) */
  function loadOthers() {
    const key = sig();
    for (const k of ["car", "walk", "bicycle"]) {
      if (k in dir.other) continue;
      dir.other[k] = null;
      (k === "car" ? car() : ride(k)).catch(() => ({ none: true, err: true })).then((r) => {
        if (sig() !== key) return;
        dir.other[k] = r;
        if (alive) draw();
      });
    }
  }
  const minutesOf = (k) => {
    if (k === "transit") return dir.routes && dir.routes.length ? Math.ceil(Math.min(...dir.routes.map((r) => r.totalTime)) / 60) : null;
    const o = dir.other[k];
    return o && !o.none ? Math.max(1, Math.ceil(o.sec / 60)) : null;
  };
  async function load() {
    if (!dir.from || !dir.to || dir.routes) return;
    const my = ++seq;
    try {
      const r = await transitAll();
      if (my !== seq) return;
      dir.status = r.status; dir.routes = r.routes;
      if (r.routes.length && dir.to.name !== MY_PLACE) Recent.addPlace(dir.to);
    } catch (e) { if (my === seq) { dir.status = "ERROR"; dir.routes = []; } }
    if (alive) draw();
  }
  function draw() {
    if (!alive) return;
    let html = header("길찾기", "대중교통·자동차·도보·자전거") + `<div class="pboxes">${box("from", "출발", dir.from)}<button class="swap" ${act(() => { [dir.from, dir.to] = [dir.to, dir.from]; dir.vias.reverse(); changed(); draw(); })}>⇄</button>${box("to", "도착", dir.to)}</div>` +
      // 경유지 (5곳까지): 이름 옆 ✕ 로 빼기
      `<div class="vias">${dir.vias.map((v, i) => `<span class="via"><small>경유${dir.vias.length > 1 ? i + 1 : ""}</small>${esc(v.name)}<button class="mini" aria-label="경유지 빼기" ${act(() => { dir.vias.splice(i, 1); changed(); draw(); })}>✕</button></span>`).join("")}` +
      `${dir.vias.length < MAX_VIAS ? `<button class="mini ${dir.editing === "via" ? "on" : ""}" ${act(() => { dir.editing = "via"; dir.q = ""; draw(); })}>+ 경유지 추가</button>` : ""}</div>`;
    if (dir.editing) {
      html += `<div class="searchbox"><input id="pq" type="search" placeholder="${dir.editing === "from" ? "출발" : dir.editing === "via" ? "경유지" : "도착"} 장소·주소·역 이름" value="${esc(dir.q)}" autocomplete="off"></div><div id="pl"></div>`;
      root.innerHTML = html;
      const inp = bindInput(root, "#pq", (v) => { dir.q = v; places(); }, 400);
      inp?.focus({ preventScroll: true });
      places();
      return;
    }
    if (!dir.from || !dir.to) { root.innerHTML = html + note("출발·도착 장소를 골라 주세요"); return; }
    // 수단 고르기: 받은 수단은 걸리는 시간도 (앱처럼)
    html += chips(MODES.map(([k, l]) => { const m = minutesOf(k); return { label: l + (m != null ? " " + duration(m) : ""), on: dir.mode === k, run: () => { dir.mode = k; dir.picked = null; draw(); } }; }));
    loadOthers();
    if (!dir.routes) load();
    if (dir.mode !== "transit") { drawRide(html); return; }
    if (!dir.routes) { root.innerHTML = html + note("경로를 찾는 중..."); return; }
    if (!dir.routes.length) { root.innerHTML = html + note(MESSAGES[dir.status] || "경로를 받지 못했어요. " + tryLater()); return; }
    const r = dir.routes[dir.picked];
    if (r) {
      html += `<button class="link pad" ${act(() => { dir.picked = null; draw(); })}>← 다른 경로</button><div class="map-slot"></div>
        <div class="list"><div class="sum">${duration(Math.ceil(r.totalTime / 60))} · 환승 ${r.transfers}번${r.fare != null ? " · " + won(r.fare) : ""}</div>
        ${r.steps.map((s) => `<div class="step"><span class="stype" style="background:${stepColor(s.type)}">${s.type === "BUS" ? "버스" : s.type === "SUBWAY" ? "지하철" : s.type === "VIA" ? "경유" : "걷기"}</span><span class="grow">${
          s.type === "VIA" ? `<b>${esc(s.name)}</b><small>들렀다 가기 (갈아타기 여유 ${VIA_BUFFER_S / 60}분 포함)</small>` :
          s.type === "WALKING" ? `${distText(s.distance)} 걷기 (약 ${Math.ceil(s.time / 60)}분)${s.guidance ? `<small>${esc(s.guidance)}</small>` : ""}` :
          `<b>${esc((s.vehicles[0] || s.guidance || "") + (s.vehicles.length > 1 ? `  (또는 ${s.vehicles.slice(1, 4).join(", ")})` : ""))}</b><small>${esc(`${s.stops[0] || ""} → ${s.stops[s.stops.length - 1] || ""} · ${Math.max(0, s.stops.length - 1)}${s.type === "SUBWAY" ? "개 역" : "정거장"} · 약 ${Math.ceil(s.time / 60)}분`)}</small>`}</span></div>`).join("")}
        ${note("카카오 대중교통 길찾기 결과예요")}</div>`;
      root.innerHTML = html;
      ctx.useMap(root.querySelector(".map-slot"));
      MapView.lines(r.steps.filter((s) => s.points.length >= 2).map((s) => ({ pts: s.points, color: stepColor(s.type), width: s.type === "WALKING" ? 3 : 6, dash: s.type === "WALKING" })));
      MapView.dots(endDots());
      MapView.fit([...r.steps.flatMap((s) => s.points), ...[dir.from, ...dir.vias, dir.to].map((x) => [x.lat, x.lng])]);
      return;
    }
    root.innerHTML = html + `<div class="list">${dir.routes.map((x, i) => {
      const total = Math.max(1, x.steps.reduce((a, s) => a + s.time, 0));
      return `<button class="row col" ${act(() => { dir.picked = i; draw(); })}>
        <span><b class="dur">${duration(Math.ceil(x.totalTime / 60))}</b> <small class="inl">환승 ${x.transfers}번${x.fare != null ? " · " + won(x.fare) : ""} · ${distText(x.totalDistance)}</small></span>
        <span class="bar8">${x.steps.map((s) => `<i style="flex:${Math.max(1, s.time) / total};background:${stepColor(s.type)}"></i>`).join("")}</span>
        <small>${esc(x.steps.filter((s) => s.type !== "WALKING").map((s) => label(s) + (s.stops[0] ? ` (${s.stops[0]})` : "")).join("  →  "))}</small></button>`;
    }).join("")}</div>`;
  }
  /** 출발·경유·도착 점 */
  const endDots = () => [{ id: "from", lat: dir.from.lat, lng: dir.from.lng, color: "#2E9E5B", ring: "#fff", r: 7, label: "출발" },
    ...dir.vias.map((v, i) => ({ id: "via" + i, lat: v.lat, lng: v.lng, color: "#E08A00", ring: "#fff", r: 6, label: "경유" + (dir.vias.length > 1 ? i + 1 : "") })),
    { id: "to", lat: dir.to.lat, lng: dir.to.lng, color: "#D7262E", ring: "#fff", r: 7, label: "도착" }];
  /** 자동차·도보·자전거 결과 */
  function drawRide(html) {
    const o = dir.other[dir.mode], name = MODES.find(([k]) => k === dir.mode)[1];
    if (!o) { root.innerHTML = html + note("경로를 찾는 중..."); return; }
    if (o.none) {
      root.innerHTML = html + note(o.err ? "경로를 받지 못했어요. " + tryLater() : `${name} 경로를 찾지 못했어요${o.msg ? " (" + o.msg + ")" : ""}`);
      return;
    }
    const extra = dir.mode === "car" ? [o.toll ? "통행료 " + won(o.toll) : null, o.taxi ? "택시 약 " + won(o.taxi) : null].filter(Boolean).join(" · ") : "";
    root.innerHTML = html + `<div class="map-slot"></div><div class="list"><div class="sum">${duration(Math.max(1, Math.ceil(o.sec / 60)))} · ${distText(o.m)}${extra ? " · " + extra : ""}</div>` +
      note(dir.mode === "car" ? "카카오모빌리티 자동차 길찾기 결과예요 (지금 길 막힘 반영)" : `카카오 ${name} 길찾기 결과예요`) + `</div>`;
    ctx.useMap(root.querySelector(".map-slot"));
    MapView.lines([{ pts: o.pts, color: MODE_COLOR[dir.mode], width: 5, dash: dir.mode === "walk" }]);
    MapView.dots(endDots());
    MapView.fit([...o.pts, ...[dir.from, ...dir.vias, dir.to].map((x) => [x.lat, x.lng])]);
  }
  async function places() {
    const out = root.querySelector("#pl");
    if (!out) return;
    const q = dir.q.trim();
    if (!q) {
      const hw = [["집", HomeWork.home], ["회사", HomeWork.work], ["학교", HomeWork.school]];
      out.innerHTML = sectionHead("자주 가는 곳") + `<div class="list">${hw.map(([l, pl]) => `<button class="row" ${pl ? act(() => use(pl)) : ""}><b class="w3">${l}</b><span class="grow ${pl ? "" : "muted"}">${esc(pl?.name || `장소를 찾아 결과 줄의 ${l} 단추로 정해요`)}</span></button>`).join("")}</div>` +
        (Recent.places.items.length ? sectionHead("최근 목적지") + `<div class="list">${Recent.places.items.map((pl) => `<button class="row" ${act(() => use(pl))}><span class="grow"><b>${esc(pl.name)}</b><small>${esc(pl.address || "")}</small></span></button>`).join("")}</div>` : "");
      return;
    }
    if (q.length < 2) { out.innerHTML = note("두 글자 이상 입력해 주세요"); return; }
    const my = ++seq;
    out.innerHTML = note("찾는 중...");
    const other = dir.editing === "to" ? dir.from : dir.to;
    const r = await searchPlaces(q, other).catch(() => []);
    if (my !== seq || !alive) return;
    out.innerHTML = r.length ? `<div class="list">${r.map((pl) => `<div class="row"><button class="grow plain" ${act(() => use(pl))}><b>${esc(pl.name)}</b><small>${esc(pl.address || "")}</small></button>
      ${pl.category ? `<small class="cat">${esc(pl.category)}</small>` : ""}<button class="mini" ${act((e, t) => { HomeWork.saveHome(pl); t.textContent = "집 ✓"; })}>집</button><button class="mini" ${act((e, t) => { HomeWork.saveWork(pl); t.textContent = "회사 ✓"; })}>회사</button><button class="mini" ${act((e, t) => { HomeWork.saveSchool(pl); t.textContent = "학교 ✓"; })}>학교</button></div>`).join("")}</div>` : note("찾는 장소가 없어요");
  }
  draw();
  // 못 받은 채로 다른 앱에 갔다 돌아오거나 인터넷이 다시 켜지면 다시 받음 (onReturn)
  const offReturn = onReturn(() => { if (alive && dir.status === "ERROR" && dir.routes && !dir.routes.length) { dir.routes = null; draw(); } });
  return () => { alive = false; offReturn(); };
}
