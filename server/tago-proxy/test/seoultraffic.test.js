import { test } from "node:test";
import assert from "node:assert/strict";
import { tmToWgs84, rows, trafficFor, accidents, handleSeoulTraffic } from "../src/seoultraffic.js";

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
