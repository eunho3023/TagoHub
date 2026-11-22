// 홈 (폰 앱 홈과 같은 모양): 로고·"어디로 가시나요?"·지도 바로가기, 실시간 도착 카드, 바로가기(집·회사·즐겨찾기·최근), 최근 검색, 즐겨찾기, 내 주변 정류장, 공지.
import { esc, busColor, subwayColor, meters, distText, livePoll } from "./core.js";
import { act, icon, note, bindInput } from "./ui.js";
import { go, isWide } from "./nav.js";
import { Favorites, Recent, HomeWork } from "./store.js";
import * as B from "./busdata.js";
import { remember } from "./bus.js";
import { startDirections } from "./places.js";
import { latestNotice } from "./more.js";
import { locate, geoMessage } from "./geo.js";
import { stopsMap } from "./app.js";

// 지역 도착(전주·제주·울산·대전·경기)을 기다리는 시간. 넘으면 TAGO 도착부터 (정류장 화면 bus.js 와 같은 값)
const QUICK_LOCAL_MS = 3000;
// 실시간 도착 카드가 한 번에 기다리는 시간. 넘으면 못 받은 것으로 보고 2·4·6초 뒤 다시 (늦게 온 답은 다음 번에 서버 저장분으로 바로 옴)
const LIVE_WAIT_MS = 8000;

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
      <div class="brand">${logo()}<div class="grow"><b>타볼까</b><small>원하는 곳 어디든지! 모두가 함께 만들어가는 지도!</small></div></div>
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
    const favStops = Favorites.stops.items, favSt = Favorites.stations.items, rec = Recent.places.items;
    const home = HomeWork.home, work = HomeWork.work, school = HomeWork.school;
    const tile = (label, ic, run) => `<button class="tile" ${act(run)}><span class="tile-ic">${icon(ic, 26)}</span><span>${label}</span></button>`;
    body.innerHTML = `${liveSection()}
      <div class="tiles">
        ${tile("집", "home", () => (home ? startDirections(home) : go("navi")))}
        ${tile("회사", "work", () => (work ? startDirections(work) : go("navi")))}
        ${tile("학교", "school", () => (school ? startDirections(school) : go("navi")))}
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
  let liveSeq = 0;
  // 도착을 정말 받았는지: 빈 목록인데 일부 자료(TAGO 등)를 못 받았으면 "버스 없음"이 아니라 못 받은 것
  // (지역 도착이 늦어 TAGO 로 물었는데 TAGO 도 막혔을 때 "700m 안에 없어요"·"도착 정보 없음"으로 보였음)
  const received = (x) => x.state === "ok" && (x.list.length > 0 || !x.partial);
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

  // 실패(못 받음)면 false: livePoll 이 2·4·6초 뒤 다시 물음. 그동안은 받아 둔 카드(2분 안)나 불러오는 모양을 두고,
  // 마지막 시도(lastTry)에서만 "받지 못했어요". 단추로 부르면(인자 없음) 바로 결과를 보여 줌
  async function paintLive(opt) {
    const lastTry = opt?.lastTry ?? true;
    if (!alive || st.q.trim()) return;
    if (!("geolocation" in navigator)) {
      st.live = { state: "noperm", rows: null, at: 0, note: "이 브라우저는 위치를 지원하지 않아요." };
      draw();
      return;
    }
    // 주기 실행 중 "늦어요" 안내가 떠 있으면 그대로 둠 (불러오는 모양과 번갈아 깜빡이지 않게). 단추로 부르면 불러오는 모양부터
    const byButton = opt?.lastTry === undefined;
    if (st.live.state !== "ok" && (byButton || st.live.state !== "error")) { st.live = { ...st.live, state: "loading" }; draw(); }
    let here;
    try {
      here = await locate();
    } catch (e) {
      st.live = { state: "noperm", rows: null, at: 0, note: await geoMessage(e).catch(() => "위치 권한이 꺼져 있어요.") };
      draw();
      return;
    }
    const [la, lo] = here;
    const fail = () => {
      if (!alive) return false;
      const fresh = st.live.state === "ok" && Date.now() - st.live.at < 120000;
      // 받아 둔 것(2분 안)이 없으면 계속 불러오는 모양 대신 늦다고 알림 (공공데이터 서버가 20초 넘게 답이 없을 때가 있음)
      if (!lastTry) { if (!fresh) { st.live = { state: "error", rows: null, at: 0, note: "도착 정보가 늦게 와요. 다시 받는 중..." }; draw(); } return false; }
      st.live = { state: "error", rows: null, at: 0, note: "도착 정보를 받지 못했어요. 버스 자료 서버가 바쁠 때 그래요. 잠시 뒤 저절로 다시 받아요." };
      draw();
      return false;
    };
    try {
      const near = (await B.stopsIn(la - 0.005, lo - 0.006, la + 0.005, lo + 0.006))
        .map((s) => ({ s, m: meters(la, lo, s.lat, s.lng) }))
        .filter((x) => x.m <= 700)
        .sort((a, b) => a.m - b.m)
        .slice(0, 2);
      // 지역 도착(전주 등)이 3초 안에 안 오면 TAGO 도착을 먼저 보여 주고, 지역 도착은 오는 대로 바꿈 (정류장 화면과 같음).
      // 전주시 서버가 20~40초 답이 없을 때 이 카드가 그동안 계속 불러오는 모양이었음
      const seq = ++liveSeq;
      const lists = await Promise.all(near.map(({ s }) => Promise.race([B.arrivals(s, { quickMs: QUICK_LOCAL_MS }), new Promise((r) => setTimeout(() => r({ state: "failed", list: [] }), LIVE_WAIT_MS))]).catch(() => ({ state: "failed", list: [] }))));
      const show = (got) => {
        const rows = [];
        near.forEach(({ s, m }, i) => {
          if (!received(got[i])) return;
          (got[i].list || []).slice(0, 2).forEach((a) => rows.push({ stop: s, a, m }));
        });
        st.live = { state: "ok", rows: rows.slice(0, 4), at: Date.now(), note: null };
      };
      if (lists.some((x) => x.later)) {
        Promise.all(lists.map((x) => (x.later ? x.later.catch(() => x) : x))).then((full) => {
          if (!alive || seq !== liveSeq || st.q.trim()) return;
          const best = full.map((x, i) => (received(x) ? x : lists[i]));
          if (best.some(received)) { show(best); draw(); }
        });
      }
      // 근처 정류장 도착을 하나도 못 받았으면 실패 ("700m 안에 없어요"로 보이던 것)
      if (near.length && !lists.some(received)) return fail();
      if (!alive) return;
      show(lists);
    } catch (e) {
      return fail();
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

  // 즐겨찾기 정류장(모두)의 가장 먼저 오는 버스 (정류장 화면과 같은 15초마다, 다른 앱에서 돌아오면 바로). 못 받은 정류장은 전에 받은 것을 그대로 두고 몇 초 뒤 다시.
  // 예전엔 한도를 아끼려고 4곳·30초마다
  const refresh = async () => {
    const got = await Promise.all(Favorites.stops.items.map(async (s) => {
      const a = await B.arrivals(s, { quickMs: QUICK_LOCAL_MS });
      if (received(a)) st.first.set(s.id, { a: a.list[0] || null, at: a.at });
      else if (a.state === "unsupported") st.first.set(s.id, { a: null, at: a.at });
      a.later?.then((b) => { if (alive && received(b)) { st.first.set(s.id, { a: b.list[0] || null, at: b.at }); if (!st.q.trim()) draw(); } });
      if (alive && !st.q.trim()) draw();
      return received(a) || a.state === "unsupported";
    }));
    return got.every(Boolean);
  };
  const favPoll = livePoll(refresh, 15000);
  // 실시간 도착 카드: 첫 화면에서 바로 채우고, 남은 시간은 1초마다 줄인다.
  // 서버 저장(5초)이 짧아 10초 주기면 사실상 최신이다. 탭이 가려지면 요청을 멈춘다.
  // livePoll: 가려지면 멈추고, 돌아오거나 인터넷이 다시 켜지면 바로, 못 받으면 2·4·6초 뒤 다시
  const POLL_MS = 10000;
  const livePollH = livePoll(paintLive, POLL_MS);
  const tk = setInterval(() => { if (!document.hidden) tickEta(); }, 1000);
  bindInput(root, "#q", (v) => { st.q = v; draw(); }, 400);
  draw();
  return () => {
    alive = false;
    if (stopMap) stopMap();
    favPoll.stop(); clearInterval(tk);
    livePollH.stop();
  };
}

function logo() {
  // 타볼까: 버스·전철로 보이는 탈것 정면, 앞유리에 지도(길·위치 핀)
  return `<svg width="34" height="34" viewBox="10 10 88 88"><path fill="#2563EB" d="M32 10h44a22 22 0 0 1 22 22v44a22 22 0 0 1-22 22H32a22 22 0 0 1-22-22V32a22 22 0 0 1 22-22z"/><g transform="translate(54 54) scale(.86) translate(-54 -54)"><path fill="#fff" d="M36 20h36a12 12 0 0 1 12 12v40a8 8 0 0 1-8 8H32a8 8 0 0 1-8-8V32a12 12 0 0 1 12-12z"/><clipPath id="tbw"><path d="M38 27h32a7 7 0 0 1 7 7v22H31V34a7 7 0 0 1 7-7z"/></clipPath><g clip-path="url(#tbw)"><path fill="#DDF6EF" d="M30 26h48v31H30z"/><path d="M44 26v32M64 26v32" stroke="#fff" stroke-width="2"/><path d="M28 50c12-6 18 2 28-6s14-10 24-8" fill="none" stroke="#2FC4A3" stroke-width="4"/></g><path fill="#FF5A6E" d="M58 44c-3.5-4.5-6-7-6-11a6 6 0 1 1 12 0c0 4-2.5 6.5-6 11z"/><circle cx="58" cy="33" r="2.4" fill="#fff"/><circle cx="40" cy="68" r="4.2" fill="#FFC43D"/><circle cx="68" cy="68" r="4.2" fill="#FFC43D"/><rect x="31" y="80" width="10" height="8" rx="2.5" fill="#0B2A7A"/><rect x="67" y="80" width="10" height="8" rx="2.5" fill="#0B2A7A"/></g></svg>`;
}
