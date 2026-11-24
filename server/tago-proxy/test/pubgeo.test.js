import { test } from "node:test";
import assert from "node:assert/strict";
import { toKatec, fromKatec, vworldPlaces, vworldAddresses, vworldWhere, opinetStations, kakaoPlaces, pubRequest, handlePub, sdscStores, STORE_CATS } from "../src/pubgeo.js";

const near = (a, b, m) => Math.hypot((a[0] - b[0]) * 111000, (a[1] - b[1]) * 88000) < m;

test("KATEC: 오피넷 반포그린주유소 좌표가 브이월드 좌표와 몇 m 안, 왕복해도 같음", () => {
  const w = fromKatec(312740.1944, 545022.5152);
  assert.ok(near(w, [37.50324270057958, 127.01085909952566], 5), String(w));
  const k = toKatec(w[0], w[1]);
  assert.ok(Math.abs(k[0] - 312740.1944) < 0.05 && Math.abs(k[1] - 545022.5152) < 0.05, String(k));
});

const vw = (items) => ({ response: { status: "OK", result: { items } } });
const item = (title, category, x, y, road = "") => ({ title, category, address: { road, parcel: "" }, point: { x: String(x), y: String(y) } });

test("브이월드 장소: 같은 이름·자리는 하나로, 이름이 같은 것 → 교통시설 먼저", () => {
  const body = vw([
    item("신한은행청주터미널지점", "건물 > 제1종근린생활시설", 127.43, 36.62),
    item("청주터미널", "교통시설 > 버스터미널", 127.431, 36.626, "충북 청주시 흥덕구 풍산로 6"),
    item("청주터미널", "교통시설 > 버스터미널", 127.43101, 36.62601),
    item("청주터미널금융센터지점", "은행및저축기관 > 일반은행 > 신한은행", 127.432, 36.627),
  ]);
  const r = vworldPlaces(body, "청주 터미널", null);
  assert.equal(r.length, 3);
  assert.equal(r[0].name, "청주터미널");
  assert.equal(r[0].category, "버스터미널");
  assert.equal(r[0].address, "충북 청주시 흥덕구 풍산로 6");
  assert.deepEqual(vworldPlaces({ response: { status: "NOT_FOUND" } }, "x", null), []);
});

test("브이월드 장소: 같은 순위면 가까운 곳 먼저", () => {
  const body = vw([item("성심당", "음식점업", 127.38, 36.33), item("성심당", "음식점업", 127.43, 36.33)]);
  assert.equal(vworldPlaces(body, "성심당", [36.33, 127.43])[0].lng, 127.43);
});

test("브이월드 주소·좌표 → 주소", () => {
  const a = vworldAddresses(vw([{ address: { road: "서울특별시 중구 세종대로 110 (태평로1가)", parcel: "태평로1가 31", bldnm: "서울특별시청" }, point: { x: "126.9779", y: "37.5663" } }]));
  assert.deepEqual(a, [{ name: "서울특별시 중구 세종대로 110 (태평로1가)", address: "태평로1가 31 · 서울특별시청", lat: 37.5663, lng: 126.9779 }]);
  const w = vworldWhere({ response: { status: "OK", result: [{ type: "parcel", text: "충청북도 청주시 상당구 북문로3가 89-1", structure: { level1: "충청북도", level2: "청주시 상당구", level4L: "북문로3가", level4LC: "4311110400" } }] } });
  assert.deepEqual(w, { name: "충청북도 청주시 상당구 북문로3가 89-1", road: null, parcel: "충청북도 청주시 상당구 북문로3가 89-1", legal: "4311110400", region: "충청북도 청주시 상당구 북문로3가" });
  assert.equal(vworldWhere({ response: { status: "NOT_FOUND" } }), null);
});

test("오피넷 주유소: 위도·경도로 바꾸고 가까운 순, 상표 이름", () => {
  const r = opinetStations({ RESULT: { OIL: [
    { UNI_ID: "B", POLL_DIV_CD: "GSC", OS_NM: "먼 곳", PRICE: 1900, DISTANCE: 900, GIS_X_COOR: 312563, GIS_Y_COOR: 545411 },
    { UNI_ID: "A", POLL_DIV_CD: "SKE", OS_NM: "반포그린주유소", PRICE: 1865, DISTANCE: 0.0, GIS_X_COOR: 312740.1944, GIS_Y_COOR: 545022.5152 },
  ] } });
  assert.deepEqual(r.map((s) => s.id), ["A", "B"]);
  assert.equal(r[0].brand, "SK에너지");
  assert.ok(near([r[0].lat, r[0].lng], [37.50324, 127.01086], 5));
  assert.deepEqual(opinetStations({}), []);
});

test("카카오 결과도 같은 모양", () => {
  assert.deepEqual(kakaoPlaces({ documents: [{ place_name: "스타벅스 강남R점", road_address_name: "서울 강남구 강남대로 390", x: "127.0286", y: "37.4979", category_name: "음식점 > 카페 > 스타벅스" }] }),
    [{ name: "스타벅스 강남R점", address: "서울 강남구 강남대로 390", lat: 37.4979, lng: 127.0286, category: "스타벅스" }]);
});

test("요청 검사: 이상한 값은 null, 주유소는 500m 칸·반경으로 맞춤", () => {
  assert.equal(pubRequest("/w/pub/place", "?q="), null);
  assert.equal(pubRequest("/w/pub/place", "?q=" + "가".repeat(41)), null);
  assert.equal(pubRequest("/w/pub/where", "?lat=abc&lng=127"), null);
  assert.equal(pubRequest("/w/pub/gas", "?lat=37.5&lng=127&r=9000"), null);
  assert.equal(pubRequest("/w/pub/nope", ""), null);
  assert.equal(pubRequest("/w/pub/gas", "?lat=37.50312&lng=127.01088&r=2400").cacheKey, "gas?lat=37.505&lng=127.010&r=2500");
  assert.equal(pubRequest("/w/pub/where", "?lat=37.503123&lng=127.010881").cacheKey, "where?lat=37.5031&lng=127.0109");
});

function fakeFetch(routes) {
  const calls = [];
  const f = async (u, init) => {
    calls.push({ u: String(u), init });
    for (const [pat, body] of routes) if (String(u).includes(pat)) return new Response(typeof body === "string" ? body : JSON.stringify(body), { status: 200 });
    return new Response("no", { status: 404 });
  };
  f.calls = calls;
  return f;
}
const ctx = { waitUntil() {} };

test("장소: 브이월드가 못 찾으면 카카오로 한 번 더 (키는 헤더로)", async () => {
  const f = fakeFetch([["api.vworld.kr", { response: { status: "NOT_FOUND" } }], ["dapi.kakao.com", { documents: [{ place_name: "스타벅스 강남R점", x: "127.0286", y: "37.4979" }] }]]);
  const res = await handlePub(new Request("https://x/w/pub/place?q=스타벅스 강남"), new URL("https://x/w/pub/place?q=" + encodeURIComponent("스타벅스 강남")), { VWORLD_KEY: "vk", KAKAO_REST_KEY: "kk" }, ctx, { fetcher: f });
  const j = await res.json();
  assert.equal(j.src, "kakao");
  assert.equal(j.places[0].name, "스타벅스 강남R점");
  assert.equal(f.calls[1].init.headers.Authorization, "KakaoAK kk");
  assert.ok(f.calls[0].u.includes("key=vk") && f.calls[0].u.includes("type=place"));
});

test("장소: 브이월드가 찾으면 카카오에 묻지 않음", async () => {
  const f = fakeFetch([["api.vworld.kr", vw([item("서울역", "철도시설 > 철도/지하철 > 승강장", 126.97, 37.55)])]]);
  const j = await (await handlePub(new Request("https://x/"), new URL("https://x/w/pub/place?q=서울역"), { VWORLD_KEY: "vk", KAKAO_REST_KEY: "kk" }, ctx, { fetcher: f })).json();
  assert.equal(j.src, "vworld");
  assert.equal(f.calls.length, 1);
});

test("주유소: 오피넷에 KATEC 좌표로 묻고, 키가 없으면 500", async () => {
  const f = fakeFetch([["opinet.co.kr", '\n\n {"RESULT": {"OIL":[{"UNI_ID":"A","POLL_DIV_CD":"GSC","OS_NM":"반포그린주유소","PRICE":1865,"DISTANCE":0.0,"GIS_X_COOR":312740.1944,"GIS_Y_COOR":545022.5152}]}}\n']]);
  const j = await (await handlePub(new Request("https://x/"), new URL("https://x/w/pub/gas?lat=37.5032&lng=127.0109&r=1000"), { OPINET_KEY: "ok" }, ctx, { fetcher: f })).json();
  assert.equal(j.stations[0].name, "반포그린주유소");
  const q = new URL(f.calls[0].u).searchParams;
  assert.equal(q.get("code"), "ok");
  assert.ok(Math.abs(+q.get("x") - 312740) < 400 && Math.abs(+q.get("y") - 545022) < 400, q.toString());
  const miss = await handlePub(new Request("https://x/"), new URL("https://x/w/pub/gas?lat=37.5&lng=127&r=1000"), {}, ctx, { fetcher: f });
  assert.equal(miss.status, 500);
});

test("장소: 브이월드가 오류면 카카오 결과는 5분만 저장 (브이월드가 돌아오면 바로)", async () => {
  const f = fakeFetch([["dapi.kakao.com", { documents: [{ place_name: "서울역", x: "126.97", y: "37.55" }] }]]);
  const res = await handlePub(new Request("https://x/"), new URL("https://x/w/pub/place?q=서울역"), { VWORLD_KEY: "vk", KAKAO_REST_KEY: "kk" }, ctx, { fetcher: f });
  const j = await res.json();
  assert.equal(j.src, "kakao");
  assert.equal(j.short, undefined);
  assert.equal(res.headers.get("Cache-Control"), "public, max-age=300");
});

const store = (nm, br, lat, lon, scls, sname) => ({ bizesNm: nm, brchNm: br, lat, lon, indsSclsCd: scls, indsSclsNm: sname, rdnmAdr: "도로 " + nm });

test("상가정보: 가까운 순, 지점명 붙임, 같은 가게(GS25 = 지에스25)는 하나, 분류 거르기", () => {
  const body = { body: { items: [
    store("씨유", "먼점", 35.83, 127.15, "G20405", "편의점"),
    store("GS25", "광장점", 35.8201, 127.148, "G20405", "편의점"),
    store("지에스25", "광장점", 35.8201, 127.1481, "G20405", "편의점"),
    store("카페", "", 35.8202, 127.148, "I21201", "카페"),
    store("", "", 35.82, 127.148, "G20405", "편의점"),
  ] } };
  const out = sdscStores(body, 35.82, 127.148);
  assert.deepEqual(out.map((p) => p.name), ["GS25 광장점", "카페", "씨유 먼점"]);
  assert.equal(out[0].dist, 11);
  assert.equal("k" in out[0], false);
  assert.deepEqual(sdscStores(body, 35.82, 127.148, STORE_CATS.FD6.keep).map((p) => p.name), []);   // 음식점 단추에 카페는 빠짐
  assert.ok(STORE_CATS.HP8.keep.test("Q10201") && !STORE_CATS.HP8.keep.test("Q10401"));           // 병원·의원만
  assert.deepEqual(sdscStores({ response: { header: { resultCode: "03" } } }, 35.82, 127.148), []);  // 자료 없음
  assert.equal(STORE_CATS.BK9, undefined);   // 은행은 상가정보에 없음 (앱이 카카오로)
});

test("상가정보 요청: 분류·좌표 검사, 50m 칸, 가까운 반경부터 넓혀 15곳 넘으면 멈춤", async () => {
  assert.equal(pubRequest("/w/pub/store", "?cat=BK9&lat=35.82&lng=127.14"), null);
  assert.equal(pubRequest("/w/pub/store", "?cat=CS2&lat=x&lng=127.14"), null);
  assert.equal(pubRequest("/w/pub/store", "?cat=CS2&lat=35.82012&lng=127.14826").cacheKey, "store?cat=CS2&lat=35.8200&lng=127.1485");
  const seen = [];
  const many = (n) => ({ body: { items: Array.from({ length: n }, (_, i) => store("가게" + i, "", 35.82 + i * 1e-4, 127.148, "G20405", "편의점")) } });
  const fetcher = async (u) => { seen.push(u); const r = new URL(u).searchParams.get("radius"); return new Response(JSON.stringify(many(r === "300" ? 3 : 20))); };
  const env = { DATA_GO_KR_KEY: "a/b+c" };
  const url = new URL("https://x/w/pub/store?cat=CS2&lat=35.82&lng=127.148");
  const r = await handlePub(new Request(url), url, env, { waitUntil() {} }, { fetcher });
  const j = await r.json();
  assert.equal(j.places.length, 15);
  assert.deepEqual(seen.map((u) => new URL(u).searchParams.get("radius")), ["300", "800"]);
  assert.ok(seen[0].includes("serviceKey=a%2Fb%2Bc") && seen[0].includes("indsSclsCd=G20405") && seen[0].startsWith("https://apis.data.go.kr/B553077/"));
  assert.equal((await handlePub(new Request(url), url, {}, { waitUntil() {} }, { fetcher })).status, 500);   // 키 없음
});
