// 홈 (폰 앱 홈과 같은 모양): 로고·"어디로 가시나요?", 바로가기(집·회사·즐겨찾기·최근), 최근 검색, 즐겨찾기(가장 먼저 오는 버스), 내 주변 정류장, 공지.
import { esc, busColor, subwayColor, meters } from "./core.js";
import { act, icon, note, bindInput } from "./ui.js";
import { go, isWide } from "./nav.js";
import { Favorites, Recent, HomeWork } from "./store.js";
import * as B from "./busdata.js";
import { remember } from "./bus.js";
import { startDirections } from "./places.js";
import { latestNotice } from "./more.js";
import { currentLocation } from "./alight.js";

const st = { q: "", near: null, nearNote: null, first: new Map() };

export function render(root) {
  let alive = true;
  root.innerHTML = `<div class="homecard">
      <div class="brand">${logo()}<div class="grow"><b>TagoHub</b><small>한 번에 연결되는 교통 정보</small></div>
        ${isWide() ? "" : `<button class="outline" ${act(() => go("map"))}>지도</button>`}</div>
      <label class="bigsearch">${icon("pin", 20)}<input id="q" type="search" placeholder="정류장 이름으로 찾기" value="${esc(st.q)}" autocomplete="off"></label>
    </div><div id="body"></div>`;
  const body = root.querySelector("#body");
  let seq = 0;

  const draw = async () => {
    if (!alive) return;
    if (st.q.trim()) {
      const my = ++seq;
      if (st.q.trim().length < 2) { body.innerHTML = note("두 글자 이상 입력해 주세요"); return; }
      body.innerHTML = note("찾는 중...");
      // 정류장 이름 찾기는 기기 안에서 (이름 목록 파일만 받음, 검색어·위치는 보내지 않음)
      const r = (await B.searchStops(st.q, 30).catch(() => null)) || [];
      if (my !== seq || !alive) return;
      body.innerHTML = r.length ? `<div class="list">${r.map((s) => { remember(s); return `<button class="row" ${act(() => { st.q = ""; go("stop", s.id, { lat: s.lat, lng: s.lng }); })}>
        ${icon("bus", 20)}<span class="grow"><b>${esc(s.name)}</b><small>${s.no ? "정류장 번호 " + esc(s.no) : ""}</small></span></button>`; }).join("")}</div>` : note("찾는 정류장이 없어요");
      return;
    }
    const favStops = Favorites.stops.items.slice(0, 4), favSt = Favorites.stations.items, rec = Recent.places.items;
    const home = HomeWork.home, work = HomeWork.work;
    const tile = (label, ic, run) => `<button class="tile" ${act(run)}><span class="tile-ic">${icon(ic, 26)}</span><span>${label}</span></button>`;
    body.innerHTML = `<div class="tiles">
        ${tile("집", "home", () => (home ? startDirections(home) : go("navi")))}
        ${tile("회사", "work", () => (work ? startDirections(work) : go("navi")))}
        ${tile("즐겨찾기", "star", () => body.querySelector("#fav")?.scrollIntoView({ behavior: "smooth" }))}
        ${tile("최근", "clock", () => body.querySelector("#rec")?.scrollIntoView({ behavior: "smooth" }))}
      </div>
      <section id="rec" class="sec"><h3>최근 검색</h3>${rec.length ? `<div class="pills">${rec.slice(0, 6).map((p) => `<button class="pillbtn" ${act(() => startDirections(p))}>${esc(p.name)}</button>`).join("")}</div>` : `<p class="muted">길찾기한 곳이 여기에 남아요.</p>`}</section>
      <section id="fav" class="sec"><h3>즐겨찾기</h3>${favStops.length || favSt.length ? `<div class="card">${[
        ...favStops.map((s) => {
          remember(s);
          const got = st.first.get(s.id), a = got?.a;
          const right = !got ? "..." : !a ? "도착 정보 없음" : B.arrivalParts(a, Math.floor((Date.now() - got.at) / 1000))[0];
          return `<button class="crow" ${act(() => go("stop", s.id, { lat: s.lat, lng: s.lng }))}>
            <span class="badge" style="background:${a ? busColor(a.routeType) : "#2563EB"}">${a ? esc(a.routeNo) : icon("bus", 16)}</span>
            <b class="grow">${esc(s.name)}</b><span class="eta-s ${a && (a.arrSeconds ?? 999) <= 180 ? "soon" : ""}">${esc(right)}</span></button>`;
        }),
        ...favSt.map((s) => `<button class="crow" ${act(() => go("subway", s.id, { name: s.name, line: s.line }))}>
            <span class="badge" style="background:${subwayColor(s.line)}">${icon("bus", 16)}</span><b class="grow">${esc(s.name)}</b><span class="muted">${esc(s.line)}</span></button>`),
      ].join("")}</div>` : `<p class="muted">정류장이나 역 화면의 ☆ 를 누르면 여기에 모여요.</p>`}</section>
      <section class="sec"><h3 class="flex"><span class="grow">내 주변 정류장</span>${"geolocation" in navigator ? `<button class="link" ${act(findNear)}>${st.near ? "다시 찾기" : "찾기"}</button>` : ""}</h3>
        ${st.nearNote ? `<p class="muted">${esc(st.nearNote)}</p>` : !st.near ? `<p class="muted">"찾기"를 누르면 지금 있는 곳 근처 정류장이 보여요.</p>` : ""}
        ${st.near && st.near.length ? `<div class="card">${st.near.map((s) => { remember(s); return `<button class="crow" ${act(() => go("stop", s.id, { lat: s.lat, lng: s.lng }))}>
            <span class="badge" style="background:var(--primary)">${icon("bus", 16)}</span><b class="grow">${esc(s.name)}</b><span class="muted">${s.no ? "정류장 번호 " + esc(s.no) : ""}</span></button>`; }).join("")}</div>` : ""}
      </section>
      <section class="sec"><div class="card"><button class="crow" ${act(() => go("notices"))}><b class="blue">공지</b><span class="grow">${esc(latestNotice()?.title || "공지사항 · 제보")}</span></button></div></section>`;
  };

  async function findNear() {
    st.nearNote = "위치를 받는 중..."; draw();
    const here = await currentLocation();
    if (!here) { st.nearNote = "위치를 받지 못했어요 (위치 사용을 허락해 주세요)"; draw(); return; }
    const [la, lo] = here;
    const got = (await B.stopsIn(la - 0.004, lo - 0.005, la + 0.004, lo + 0.005).catch(() => [])).sort((a, b) => meters(la, lo, a.lat, a.lng) - meters(la, lo, b.lat, b.lng)).slice(0, 6);
    st.near = got; st.nearNote = got.length ? null : "근처 400m 안에 정류장이 없어요";
    draw();
  }

  // 즐겨찾기 정류장의 가장 먼저 오는 버스 (30초마다)
  const refresh = () => Favorites.stops.items.slice(0, 4).forEach(async (s) => {
    const a = await B.arrivals(s);
    if (a.state === "ok") st.first.set(s.id, { a: a.list[0] || null, at: a.at });
    else if (a.state === "unsupported") st.first.set(s.id, { a: null, at: a.at });
    if (alive && !st.q.trim()) draw();
  });
  refresh();
  const t = setInterval(refresh, 30000);
  bindInput(root, "#q", (v) => { st.q = v; draw(); }, 400);
  draw();
  return () => { alive = false; clearInterval(t); };
}

function logo() {
  return `<svg width="34" height="34" viewBox="0 0 48 48" fill="none" stroke-linecap="round" stroke-linejoin="round">
    <path d="M10 11h14v14h15M24 25v13" stroke="var(--primary)" stroke-width="6"/>
    <circle cx="10" cy="11" r="4.5" fill="#13887E"/><circle cx="39" cy="25" r="4.5" fill="#F0A33A"/><circle cx="24" cy="38" r="4.5" fill="var(--primary)"/></svg>`;
}
