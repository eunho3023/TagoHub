// 홈 (폰 앱 홈과 같은 모양): 로고·"어디로 가시나요?"·지도 바로가기, 실시간 도착 카드, 바로가기(집·회사·즐겨찾기·최근), 최근 검색, 즐겨찾기, 내 주변 정류장, 공지.
import { esc, busColor, subwayColor, meters, distText } from "./core.js";
import { act, icon, note, bindInput } from "./ui.js";
import { go, isWide } from "./nav.js";
import { Favorites, Recent, HomeWork } from "./store.js";
import * as B from "./busdata.js";
import { remember } from "./bus.js";
import { startDirections } from "./places.js";
import { latestNotice } from "./more.js";
import { locate, geoMessage } from "./geo.js";
import { stopsMap } from "./app.js";

const st = {
  q: "",
  near: null,
  nearNote: null,
  first: new Map(),
  // 실시간 도착 카드: idle | loading | ok | noperm | error
  live: { state: "idle", rows: null, at: 0, note: null },
};

export function render(root, _p, ctx) {
  let alive = true;
  root.innerHTML = `<div class="homecard">
      <div class="brand">${logo()}<div class="grow"><b>모두의 지도</b><small>원하는 곳 어디든지!</small></div></div>
      <label class="bigsearch">${icon("pin", 20)}<input id="q" type="search" placeholder="정류장 이름으로 찾기" value="${esc(st.q)}" autocomplete="off"></label>
    </div>${ctx && !ctx.wide ? `<div class="map-slot home-map"></div>` : ""}<div id="body"></div>`;
  const body = root.querySelector("#body");
  const mapSlot = root.querySelector(".home-map");
  let stopMap = null;
  if (mapSlot) { ctx.useMap(mapSlot); stopMap = stopsMap(mapSlot, null); }
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
      // 어느 방면인지 (지나는 노선의 다음 정류장, 위 15개)
      const smalls = body.querySelectorAll(".row small");
      r.slice(0, 15).forEach((s, i) => {
        if (!s.routes.length) return;
        B.towardOf(s, s.routes.slice(0, 4).map((id) => ({ id, city: s.city }))).then((t) => {
          if (t && smalls[i] && my === seq) smalls[i].textContent = [s.no ? "정류장 번호 " + esc(s.no) : null, t + " 방면"].filter(Boolean).join(" · ");
        }).catch(() => {});
      });
      return;
    }
    const favStops = Favorites.stops.items.slice(0, 4), favSt = Favorites.stations.items, rec = Recent.places.items;
    const home = HomeWork.home, work = HomeWork.work;
    const tile = (label, ic, run) => `<button class="tile" ${act(run)}><span class="tile-ic">${icon(ic, 26)}</span><span>${label}</span></button>`;
    body.innerHTML = `${liveSection()}
      <div class="tiles">
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

  /* ───────── 실시간 도착 카드 ───────── */
  function liveSection() {
    const L = st.live;
    let rows = "";
    if (L.state === "loading" || L.state === "idle") {
      rows = `<div class="live-skel"><i></i><i></i></div>`;
    } else if (L.state === "noperm" || L.state === "error") {
      rows = `<p class="live-note">${esc(L.note || "위치를 확인하지 못했어요.")}</p>
        <button class="live-more" ${act(paintLive)}>다시 시도</button>`;
    } else if (!L.rows || !L.rows.length) {
      rows = `<p class="live-note">700m 안에 도착 정보가 있는 정류장이 없어요.</p>
        <button class="live-more" ${act(findNear)}>내 주변 정류장 보기</button>`;
    } else {
      rows = L.rows.map(({ stop, a, m }, i) => {
        const text = B.arrivalParts(a, Math.floor((Date.now() - L.at) / 1000))[0];
        const soon = (a.arrSeconds ?? 999) <= 30;
        const meta = [distText(m), a.prevStops != null ? a.prevStops + "정류장 전" : null].filter(Boolean).join(" · ");
        return `<button class="live-row" ${act(() => go("stop", stop.id, { lat: stop.lat, lng: stop.lng }))}>
          <span class="badge" style="background:${busColor(a.routeType)}">${esc(a.routeNo)}</span>
          <span class="grow"><b>${esc(stop.name)}</b><small>${esc(meta)}</small></span>
          <span class="live-eta${soon ? " soon" : ""}" data-live="${i}">${esc(text)}</span></button>`;
      }).join("") + `<button class="live-more" ${act(findNear)}>내 주변 정류장 모두 보기</button>`;
    }
    const at = L.at ? `<span class="live-at">${new Date(L.at).toTimeString().slice(0, 5)} 기준</span>` : "";
    return `<div class="livesec"><div class="livecard">
      <div class="live-head"><span class="live-dot"></span><b>실시간 도착</b>${at}<span class="grow"></span>
        <button class="link" ${act(paintLive)}>새로고침</button></div>
      ${rows}</div></div>`;
  }

  async function paintLive() {
    if (!alive || st.q.trim()) return;
    if (!("geolocation" in navigator)) {
      st.live = { state: "noperm", rows: null, at: 0, note: "이 브라우저는 위치를 지원하지 않아요." };
      draw();
      return;
    }
    if (st.live.state !== "ok") { st.live = { ...st.live, state: "loading" }; draw(); }
    let here;
    try {
      here = await locate();
    } catch (e) {
      st.live = { state: "noperm", rows: null, at: 0, note: await geoMessage(e).catch(() => "위치 권한이 꺼져 있어요.") };
      draw();
      return;
    }
    const [la, lo] = here;
    try {
      const near = (await B.stopsIn(la - 0.005, lo - 0.006, la + 0.005, lo + 0.006).catch(() => []))
        .map((s) => ({ s, m: meters(la, lo, s.lat, s.lng) }))
        .filter((x) => x.m <= 700)
        .sort((a, b) => a.m - b.m)
        .slice(0, 2);
      const lists = await Promise.all(near.map(({ s }) => B.arrivals(s).catch(() => ({ state: "error", list: [] }))));
      const rows = [];
      near.forEach(({ s, m }, i) => {
        const got = lists[i];
        if (got.state !== "ok") return;
        (got.list || []).slice(0, 2).forEach((a) => rows.push({ stop: s, a, m }));
      });
      if (!alive) return;
      st.live = { state: "ok", rows: rows.slice(0, 4), at: Date.now(), note: null };
    } catch (e) {
      if (!alive) return;
      st.live = { state: "error", rows: null, at: 0, note: "도착 정보를 받지 못했어요." };
    }
    draw();
  }

  // 받아 둔 도착 시각으로 1초마다 남은 시간만 다시 계산한다 (서버 요청 없음).
  // 20초·10초 주기 사이에도 "3분"이 "2분"으로 줄어 실제처럼 보이게.
  function tickEta() {
    const L = st.live;
    if (L.state !== "ok" || !L.rows || !L.rows.length) return;
    const nodes = body.querySelectorAll("[data-live]");
    if (!nodes.length) return;
    const el = Math.floor((Date.now() - L.at) / 1000);
    nodes.forEach((n) => {
      const r = L.rows[+n.dataset.live];
      if (!r) return;
      const text = B.arrivalParts(r.a, el)[0];
      if (n.textContent !== text) n.textContent = text;
      n.classList.toggle("soon", (r.a.arrSeconds ?? 1e9) - el <= 30);
    });
  }

  async function findNear() {
    st.nearNote = "위치를 받는 중..."; draw();
    let here;
    try { here = await locate(); } catch (e) { st.nearNote = e.message; draw(); return; }
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
  // 실시간 도착 카드: 첫 화면에서 바로 채우고, 남은 시간은 1초마다 줄인다.
  // 서버 저장(5초)이 짧아 10초 주기면 사실상 최신이다. 탭이 가려지면 요청을 멈춘다.
  const POLL_MS = 10000;
  let lt = 0;
  const startPoll = () => { if (!lt) lt = setInterval(() => { if (!document.hidden) paintLive(); }, POLL_MS); };
  const stopPoll = () => { clearInterval(lt); lt = 0; };
  const onVis = () => { if (document.hidden) stopPoll(); else { paintLive(); startPoll(); } };
  const onOnline = () => paintLive();
  document.addEventListener("visibilitychange", onVis);
  window.addEventListener("online", onOnline);
  paintLive();
  startPoll();
  const tk = setInterval(() => { if (!document.hidden) tickEta(); }, 1000);
  bindInput(root, "#q", (v) => { st.q = v; draw(); }, 400);
  draw();
  return () => {
    alive = false;
    if (stopMap) stopMap();
    clearInterval(t); clearInterval(tk);
    stopPoll();
    document.removeEventListener("visibilitychange", onVis);
    window.removeEventListener("online", onOnline);
  };
}

function logo() {
  return `<svg width="34" height="34" viewBox="10 10 88 88"><path fill="#2563EB" d="M32 10h44a22 22 0 0 1 22 22v44a22 22 0 0 1-22 22H32a22 22 0 0 1-22-22V32a22 22 0 0 1 22-22z"/><path fill="#fff" d="M26 40l18-6v42l-18 6z"/><path fill="#CFDDFF" d="M44 34l20 6v42l-20-6z"/><path fill="#fff" d="M64 40l18-6v42l-18 6z"/><path d="M30 70c8-6 16 0 24-6s16-6 24-12" fill="none" stroke="#2FC4A3" stroke-width="3.5" stroke-linecap="round"/><path fill="#FF5A6E" d="M54 66c-7-9-12-14-12-21.5a12 12 0 1 1 24 0C66 52 61 57 54 66z"/><circle cx="54" cy="44.5" r="4.8" fill="#fff"/></svg>`;
}
