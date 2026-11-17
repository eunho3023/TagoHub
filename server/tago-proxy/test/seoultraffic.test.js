import { test } from "node:test";
import assert from "node:assert/strict";
import { tmToWgs84, rows, trafficFor, accidents, handleSeoulTraffic, bikes, bikeName, nationalBikes, stdBikes } from "../src/seoultraffic.js";

/** caches.default 흉내 */
function fakeCaches() {
  const m = new Map();
  globalThis.caches = { default: { match: async (r) => { const v = m.get(r.url); return v ? new Response(v) : undefined; }, put: async (r, res) => { m.set(r.url, await res.text()); } } };
  return m;
}
const ctx = { waitUntil: (p) => p };

test("서울 TM(EPSG:5181) → 위경도: 원점과 월드컵대교 교통량 지점", () => {
  const [la0, lo0] = tmToWgs84(200000, 500000);
  assert.ok(Math.abs(la0 - 38) < 1e-6 && Math.abs(lo0 - 127) < 1e-6);
  const [la, lo] = tmToWgs84(189882, 450789);
  assert.ok(Math.abs(la - 37.55657) < 1e-4 && Math.abs(lo - 126.88549) < 1e-4, `${la},${lo}`);
});

test("응답 줄 읽기: 태그는 소문자, 글자 참조는 풂", () => {
  const r = rows("<AccInfo><row><ACC_ID>1</ACC_ID><acc_info>공사&#13;-통제 : 양방향 &amp; 우회</acc_info></row></AccInfo>");
  assert.deepEqual(r, [{ acc_id: "1", acc_info: "공사\n-통제 : 양방향 & 우회" }]);
});

test("링크별 실시간 속도: 묶음마다 따로 저장해 동시에 와도 덮어쓰지 않음", async () => {
  const store = fakeCaches();
  let asked = 0;
  const fetcher = async (u) => { asked++; const id = u.split("/").pop(); return { ok: true, text: async () => `<TrafficInfo><list_total_count>1</list_total_count><row><link_id>${id}</link_id><prcs_spd>23.4</prcs_spd><prcs_trv_time>95</prcs_trv_time></row></TrafficInfo>` }; };
  const pending = [];
  const c2 = { waitUntil: (p) => pending.push(p) };
  assert.deepEqual(await trafficFor(["1000002600", "1000002500"], "k", c2, fetcher), { 1000002500: [23, 95], 1000002600: [23, 95] });
  assert.equal(asked, 2);
  await Promise.all(pending);
  // 같은 묶음은 순서가 달라도 저장한 것을 줌
  assert.deepEqual(await trafficFor(["1000002500", "1000002600"], "k", c2, fetcher), { 1000002500: [23, 95], 1000002600: [23, 95] });
  assert.equal(asked, 2, "저장한 것은 다시 묻지 않음");
  // 다른 묶음 둘을 동시에: 둘 다 남음
  const a = Array.from({ length: 30 }, (_, i) => String(2000000000 + i)), b = Array.from({ length: 30 }, (_, i) => String(3000000000 + i));
  const [ga, gb] = await Promise.all([trafficFor(a, "k", c2, fetcher), trafficFor(b, "k", c2, fetcher)]);
  assert.equal(Object.keys(ga).length + Object.keys(gb).length, 60);
  await Promise.all(pending);
  assert.equal([...store.keys()].filter((k) => k.includes("/traffic/g/")).length, 3);
  // 한 요청에 묻는 것은 30개까지 (바깥 요청 수 제한)
  const many = Array.from({ length: 50 }, (_, i) => String(4000000000 + i));
  assert.equal(Object.keys(await trafficFor(many, "k", c2, fetcher)).length, 30);
  assert.equal(asked, 92);
});

test("링크 속도: 서울 API 가 다 실패하면 저장하지 않아 다음에 다시 물음", async () => {
  const store = fakeCaches();
  const pending = [];
  const c2 = { waitUntil: (p) => pending.push(p) };
  assert.deepEqual(await trafficFor(["1000002500"], "k", c2, async () => ({ ok: false, text: async () => "" })), {});
  await Promise.all(pending);
  assert.equal(store.size, 0);
});

test("돌발: 위경도로 바꾸고 유형 이름·날짜를 붙임", async () => {
  fakeCaches();
  const fetcher = async (u) => {
    if (u.includes("/AccMainCode/")) return { ok: true, text: async () => "<x><list_total_count>1</list_total_count><row><acc_type>A04</acc_type><acc_type_nm>공사</acc_type_nm></row></x>" };
    if (u.includes("/AccSubCode/")) return { ok: true, text: async () => "<x><list_total_count>1</list_total_count><row><acc_dtype>04B01</acc_dtype><acc_dtype_nm>시설물보수</acc_dtype_nm></row></x>" };
    return { ok: true, text: async () => "<AccInfo><list_total_count>1</list_total_count><row><acc_id>1071788</acc_id><occr_date>20260407</occr_date><occr_time>1000</occr_time><exp_clr_date>20270301</exp_clr_date><exp_clr_time>000000</exp_clr_time><acc_type>A04</acc_type><acc_dtype>04B01</acc_dtype><link_id>1220031600</link_id><grs80tm_x>206076.71</grs80tm_x><grs80tm_y>446245.69</grs80tm_y><acc_info>봉은교 공사</acc_info></row></AccInfo>" };
  };
  const [a] = await accidents("k", ctx, fetcher);
  assert.equal(a.type, "공사");
  assert.equal(a.sub, "시설물보수");
  assert.equal(a.from, "2026-04-07 10:00");
  assert.equal(a.to, "2027-03-01 00:00");
  assert.ok(a.lat > 37.4 && a.lat < 37.6 && a.lng > 127.0 && a.lng < 127.2);
});

test("요청 검사: 키 없음 503, 뺀 교통량 주소·모르는 주소 404", async () => {
  fakeCaches();
  assert.equal((await handleSeoulTraffic(new URL("https://x/w/topis/acc"), {}, ctx)).status, 503);
  assert.equal((await handleSeoulTraffic(new URL("https://x/w/topis/vol?spot=A-01&ymd=20261116&hh=09"), { SEOUL_SUBWAY_KEY: "k" }, ctx)).status, 404);
  assert.equal((await handleSeoulTraffic(new URL("https://x/w/topis/spots"), { SEOUL_SUBWAY_KEY: "k" }, ctx)).status, 404);
  assert.equal((await handleSeoulTraffic(new URL("https://x/w/topis/nope"), { SEOUL_SUBWAY_KEY: "k" }, ctx)).status, 404);
  const empty = await handleSeoulTraffic(new URL("https://x/w/topis/traffic?links=abc"), { SEOUL_SUBWAY_KEY: "k" }, ctx);
  assert.deepEqual(await empty.json(), {});
});

test("따릉이 대여소: 1,000곳씩 4쪽을 한꺼번에 묻고 빈 쪽은 버림, 이름 앞 번호는 뗌", async () => {
  fakeCaches();
  const asked = [];
  const fetcher = async (u) => {
    const range = u.split("bikeList/")[1];
    asked.push(range);
    const a = +range.split("/")[0];
    if (a === 3001) return { ok: true, json: async () => ({ CODE: "INFO-200", MESSAGE: "해당하는 데이터가 없습니다." }) };
    const row = a === 1
      ? [{ stationId: "ST-4", stationName: "102. 망원역 1번출구 앞", stationLatitude: "37.55564880", stationLongitude: "126.91062927", parkingBikeTotCnt: "7", rackTotCnt: "15" }]
      : [{ stationId: "ST-9" + a, stationName: "엉뚱한 곳", stationLatitude: "0", stationLongitude: "0", parkingBikeTotCnt: "1", rackTotCnt: "1" }];
    return { ok: true, json: async () => ({ rentBikeStatus: { list_total_count: row.length, row } }) };
  };
  const list = await bikes("k", ctx, fetcher);
  assert.deepEqual(asked.sort(), ["1/1000/", "1001/2000/", "2001/3000/", "3001/4000/"]);
  assert.deepEqual(list, [{ id: "ST-4", name: "망원역 1번출구 앞", lat: 37.555649, lng: 126.910629, bikes: 7, racks: 15 }]);
  assert.equal(bikeName("2001. 서울숲 입구"), "서울숲 입구");
  fakeCaches();
  assert.equal(await bikes("k", ctx, async () => ({ ok: false })), null);
});

test("전국 공영자전거: 첫 쪽의 전체 수로 나머지 쪽을 묻고, 서울은 빼고, ID 는 지역코드:ID (2026-11-17 실제 응답 모양)", async () => {
  fakeCaches();
  const asked = [];
  const item = (cd, nm, id, n) => ({ lcgvmnInstCd: cd, lcgvmnInstNm: nm, rntstnId: id, rntstnNm: "탄방동_한가람아파트(2동)", lat: "36.3482010000", lot: "127.3904090000", bcyclTpkctNocs: String(n) });
  const fetcher = async (u) => {
    const p = +new URL(u).searchParams.get("pageNo");
    asked.push(p);
    const items = p === 1 ? [item("1100000000", "서울특별시", "ST-10", 3)] : [item("3000000000", "대전광역시", "ST0004", 2)];
    return { ok: true, json: async () => ({ header: { resultCode: "K0", resultMsg: "NORMAL_SERVICE" }, body: { totalCount: 1500, pageNo: p, item: items } }) };
  };
  const list = await nationalBikes("raw key", ctx, fetcher);
  assert.deepEqual(asked.sort(), [1, 2]);
  assert.deepEqual(list, [{ id: "3000000000:ST0004", name: "탄방동 한가람아파트(2동)", lat: 36.348201, lng: 127.390409, bikes: 2, racks: 0 }]);
  fakeCaches();
  assert.equal(await nationalBikes("k", ctx, async () => ({ ok: true, json: async () => ({ header: { resultCode: "K10" }, body: {} }) })), null);
});

test("따릉이+전국 합치기: 한쪽이 실패해도 받은 쪽은 보여 줌", async () => {
  fakeCaches();
  const fetcher = async (u) => {
    if (u.includes("bikeList")) return { ok: false };
    return { ok: true, json: async () => ({ header: { resultCode: "K0" }, body: { totalCount: 1, item: [{ lcgvmnInstCd: "3611000000", rntstnId: "A1", rntstnNm: "세종시청", lat: "36.48", lot: "127.28", bcyclTpkctNocs: "4" }] } }) };
  };
  const res = await handleSeoulTraffic(new URL("https://x/w/topis/bike"), { SEOUL_SUBWAY_KEY: "s", DATA_GO_KR_KEY: "d" }, ctx, fetcher);
  const all = await res.json();
  assert.deepEqual(all.filter((b) => b.bikes != null).map((b) => [b.id, b.bikes]), [["3611000000:A1", 4]]);
  // 실시간 대수 없는 대여소(표준데이터)도 붙음. 따릉이를 못 받았으니 서울 것도 남음
  assert.ok(all.some((b) => b.id.startsWith("std:") && b.bikes === null && b.lat > 37.41 && b.lat < 37.72 && b.lng > 126.76 && b.lng < 127.19));
  assert.match(res.headers.get("Cache-Control"), /max-age=30/);
});

test("표준데이터 대여소: 실시간 대여소 80m 안·서울(따릉이 받았을 때)은 빼고 bikes 는 null", () => {
  const std = [
    ["세종 가까운 곳", 36.48, 127.2805, 10, "24시간"],
    ["세종 먼 곳", 36.49, 127.29, 8, ""],
    ["서울 어딘가", 37.5, 127.0, 5, ""],
  ];
  const live = [{ id: "3611000000:A1", lat: 36.48, lng: 127.28, bikes: 4 }];
  assert.deepEqual(stdBikes(live, true, std), [{ id: "std:1", name: "세종 먼 곳", lat: 36.49, lng: 127.29, bikes: null, racks: 8, info: "" }]);
  assert.deepEqual(stdBikes([], false, std).map((b) => b.id), ["std:0", "std:1", "std:2"]);
});
