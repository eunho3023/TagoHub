// 길찾기 (카카오): 장소 찾기 /w/kakao/place, 대중교통 경로 /w/kakao/transit. 집·회사 저장, 최근 목적지.
import { getText, esc, distText } from "./core.js";
import { act, header, note, sectionHead, bindInput } from "./ui.js";
import { go, isWide } from "./nav.js";
import { MapView } from "./map.js";
import { HomeWork, Recent } from "./store.js";
import { currentLocation } from "./alight.js";

export const MY_PLACE = "내 위치";
/** 길찾기 상태 (다른 화면에 다녀와도 남음) */
export const dir = { from: null, to: null, editing: "from", q: "", routes: null, status: null, picked: null };

export async function searchPlaces(q, near) {
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

const MESSAGES = { EQUAL_POINTS: "출발지와 도착지가 같아요", STARTNODES_NULL: "출발지 근처에 대중교통이 없어요", ENDNODES_NULL: "도착지 근처에 대중교통이 없어요", NO_RESULTS: "대중교통 경로를 찾지 못했어요" };
const duration = (min) => (min >= 60 ? `${Math.floor(min / 60)}시간${min % 60 ? " " + (min % 60) + "분" : ""}` : min + "분");
const won = (n) => n.toLocaleString("ko-KR") + "원";
const stepColor = (t) => (t === "BUS" ? "#2E9E5B" : t === "SUBWAY" ? "#3B7DDD" : "#9E9E9E");
const label = (s) => (s.type === "BUS" ? "버스 " + (s.vehicles[0] || s.guidance || "") : s.type === "SUBWAY" ? s.vehicles[0] || s.guidance || "지하철" : `걷기 ${Math.ceil(s.time / 60)}분`);

/** 홈·집/회사에서: 도착은 고른 곳, 출발은 내 위치 (못 받으면 출발을 고르게) */
export function startDirections(to) {
  Object.assign(dir, { to, from: null, editing: "from", q: "", routes: null, picked: null });
  go("navi");
  currentLocation().then((here) => {
    if (here && !dir.from) { dir.from = { name: MY_PLACE, lat: here[0], lng: here[1], address: null }; dir.editing = null; dir.routes = null; window.dispatchEvent(new Event("hashchange")); }
  });
}

export function directionsScreen(root, p, ctx) {
  let alive = true, seq = 0;
  const box = (k, title, place) => `<button class="pbox ${dir.editing === k ? "on" : ""}" ${act(() => { dir.editing = k; dir.q = ""; draw(); })}><small>${title}</small><b>${esc(place?.name || "장소 찾기")}</b></button>`;
  const use = (pl) => {
    if (dir.editing === "from") dir.from = pl; else dir.to = pl;
    dir.editing = dir.editing === "from" && !dir.to ? "to" : null;
    dir.q = ""; dir.routes = null; dir.picked = null;
    draw();
  };
  async function load() {
    if (!dir.from || !dir.to || dir.routes) return;
    const my = ++seq;
    try {
      const r = await transit(dir.from, dir.to);
      if (my !== seq) return;
      dir.status = r.status; dir.routes = r.routes;
      if (r.routes.length && dir.to.name !== MY_PLACE) Recent.addPlace(dir.to);
    } catch (e) { if (my === seq) { dir.status = "ERROR"; dir.routes = []; } }
    if (alive) draw();
  }
  function draw() {
    if (!alive) return;
    let html = header("길찾기", "대중교통 길찾기") + `<div class="pboxes">${box("from", "출발", dir.from)}<button class="swap" ${act(() => { [dir.from, dir.to] = [dir.to, dir.from]; dir.routes = null; dir.picked = null; draw(); })}>⇄</button>${box("to", "도착", dir.to)}</div>`;
    if (dir.editing) {
      html += `<div class="searchbox"><input id="pq" type="search" placeholder="${dir.editing === "from" ? "출발" : "도착"} 장소·주소·역 이름" value="${esc(dir.q)}" autocomplete="off"></div><div id="pl"></div>`;
      root.innerHTML = html;
      const inp = bindInput(root, "#pq", (v) => { dir.q = v; places(); }, 400);
      inp?.focus({ preventScroll: true });
      places();
      return;
    }
    if (!dir.from || !dir.to) { root.innerHTML = html + note("출발·도착 장소를 골라 주세요"); return; }
    if (!dir.routes) { root.innerHTML = html + note("경로를 찾는 중..."); load(); return; }
    if (!dir.routes.length) { root.innerHTML = html + note(MESSAGES[dir.status] || "경로를 받지 못했어요. 잠시 뒤 다시 시도해 주세요."); return; }
    const r = dir.routes[dir.picked];
    if (r) {
      html += `<button class="link pad" ${act(() => { dir.picked = null; draw(); })}>← 다른 경로</button><div class="map-slot"></div>
        <div class="list"><div class="sum">${duration(Math.ceil(r.totalTime / 60))} · 환승 ${r.transfers}번${r.fare != null ? " · " + won(r.fare) : ""}</div>
        ${r.steps.map((s) => `<div class="step"><span class="stype" style="background:${stepColor(s.type)}">${s.type === "BUS" ? "버스" : s.type === "SUBWAY" ? "지하철" : "걷기"}</span><span class="grow">${
          s.type === "WALKING" ? `${distText(s.distance)} 걷기 (약 ${Math.ceil(s.time / 60)}분)${s.guidance ? `<small>${esc(s.guidance)}</small>` : ""}` :
          `<b>${esc((s.vehicles[0] || s.guidance || "") + (s.vehicles.length > 1 ? `  (또는 ${s.vehicles.slice(1, 4).join(", ")})` : ""))}</b><small>${esc(`${s.stops[0] || ""} → ${s.stops[s.stops.length - 1] || ""} · ${Math.max(0, s.stops.length - 1)}${s.type === "SUBWAY" ? "개 역" : "정거장"} · 약 ${Math.ceil(s.time / 60)}분`)}</small>`}</span></div>`).join("")}
        ${note("카카오 대중교통 길찾기 결과예요")}</div>`;
      root.innerHTML = html;
      ctx.useMap(root.querySelector(".map-slot"));
      MapView.lines(r.steps.filter((s) => s.points.length >= 2).map((s) => ({ pts: s.points, color: stepColor(s.type), width: s.type === "WALKING" ? 3 : 6, dash: s.type === "WALKING" })));
      MapView.dots([{ id: "from", lat: dir.from.lat, lng: dir.from.lng, color: "#2E9E5B", ring: "#fff", r: 7, label: "출발" }, { id: "to", lat: dir.to.lat, lng: dir.to.lng, color: "#D7262E", ring: "#fff", r: 7, label: "도착" }]);
      MapView.fit([...r.steps.flatMap((s) => s.points), [dir.from.lat, dir.from.lng], [dir.to.lat, dir.to.lng]]);
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
  async function places() {
    const out = root.querySelector("#pl");
    if (!out) return;
    const q = dir.q.trim();
    if (!q) {
      const hw = [["집", HomeWork.home], ["회사", HomeWork.work]];
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
      ${pl.category ? `<small class="cat">${esc(pl.category)}</small>` : ""}<button class="mini" ${act((e, t) => { HomeWork.saveHome(pl); t.textContent = "집 ✓"; })}>집</button><button class="mini" ${act((e, t) => { HomeWork.saveWork(pl); t.textContent = "회사 ✓"; })}>회사</button></div>`).join("")}</div>` : note("찾는 장소가 없어요");
  }
  draw();
  return () => { alive = false; };
}
