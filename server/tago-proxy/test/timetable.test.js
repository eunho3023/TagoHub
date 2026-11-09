import { test } from "node:test";
import assert from "node:assert/strict";
import { clockList, parseJeonjuTime, handleJeonjuTime } from "../src/timetable.js";

test("시각 목록: 앞뒤 공백·이상한 값 정리", () => {
  assert.deepEqual(clockList(" 06:00, 06:15,  ,25:99, 06:00"), [360, 375]);
});

test("전주 배차시간표: 토요일·일요일에 안 다니는 시각을 빼서 요일별로", () => {
  const t = parseJeonjuTime({ result: { COURSE_STIMELIST: " 06:00, 06:15, 06:30", SAT_NLIST: " 06:15", HOLI_NLIST: null, BRT_TEXT: "06:05 전북대농협앞 출발" } });
  assert.deepEqual(t.weekday, [360, 375, 390]);
  assert.deepEqual(t.sat, [360, 390]);
  assert.deepEqual(t.holi, [360, 375, 390]);
  assert.equal(t.note, "06:05 전북대농협앞 출발");
  assert.equal(parseJeonjuTime({ result: {} }), null);
});

test("전주 시간표 주소: 9자리만, 받은 것은 하루 저장", async () => {
  const store = new Map();
  globalThis.caches = { default: { match: async (k) => (store.has(k.url) ? new Response(store.get(k.url)) : undefined), put: async (k, r) => { store.set(k.url, await r.text()); } } };
  const waits = [];
  const ctx = { waitUntil: (p) => waits.push(p) };
  assert.equal((await handleJeonjuTime("12a", ctx)).status, 400);
  let calls = 0;
  const fetcher = async (u, o) => { calls++; assert.match(o.body, /routeId=305001764/); return new Response(JSON.stringify({ result: { COURSE_STIMELIST: "06:00, 06:15" } })); };
  const a = await (await handleJeonjuTime("305001764", ctx, fetcher)).json();
  await Promise.all(waits);
  assert.deepEqual(a.weekday, [360, 375]);
  await handleJeonjuTime("305001764", ctx, fetcher);
  assert.equal(calls, 1);
});

import { parseCheonanHtml, parseChangwon, parseUlsan, handleBusTime } from "../src/timetable.js";
test("천안 시간표 화면: 평일·주말·공휴일 칸을 나눠 읽음", () => {
  const html = "<dl><dt>평일 출발시간</dt><dd>06:00</dd><dd>06:20</dd></dl><dl><dt>주말 출발시간</dt><dd>07:00</dd></dl><dl><dt>공휴일 출발시간</dt></dl>";
  const t = parseCheonanHtml(html);
  assert.deepEqual([t.weekday, t.sat, t.holi], [[360, 380], [420], [420]]);
  assert.equal(parseCheonanHtml("<html></html>"), null);
});
test("창원: 기점(0)·종점(1) 출발을 따로", () => {
  const d = { rows: [{ updnSe: "0", startTime: "05:40" }, { updnSe: "1", startTime: "06:10" }] };
  const t = parseChangwon([d, d, { rows: [] }]);
  assert.deepEqual([t.weekday, t.weekdayDown, t.holi], [[340], [370], []]);
});
test("울산: 기점·순환 칸, 괄호 붙은 시각도 읽음", () => {
  const j = { rows: [{ fiducialInfo: [{ bttStarttime: "0620" }, { bttStarttime: "0650(경유)" }], circularInfo: [{ bttStarttime: "0530" }] }] };
  assert.deepEqual(parseUlsan(j, false), [380, 410]);
  assert.deepEqual(parseUlsan(j, true), [330]);
});
test("지역 시간표 주소: 모르는 지역·형식은 400", async () => {
  globalThis.caches = { default: { match: async () => undefined, put: async () => {} } };
  const ctx = { waitUntil() {} };
  assert.equal((await handleBusTime("seoul", "123456789", ctx)).status, 400);
  assert.equal((await handleBusTime("ulsan", "12a", ctx)).status, 400);
});

import { parseDaeguTable, parseJejuSheets, pickJinju } from "../src/timetable.js";
import { unzip, readXlsx } from "../src/xlsx.js";
test("대구 운행표: 주요 정류장 이름과 회차별 시각 (다른 기점 이름이 붙은 칸은 시각만)", () => {
  const html = "<table><thead><tr><th>순서</th><th>A역</th><th>B앞</th></tr></thead><tbody><tr><td>1</td><td >&nbsp;</td><td >C앞<br />05:30</td></tr><tr><td>2</td><td >05:40</td><td >05:52</td></tr></tbody></table>";
  assert.deepEqual(parseDaeguTable(html), { points: ["A역", "B앞"], trips: [[null, 330], [340, 352]] });
  assert.equal(parseDaeguTable("<table></table>"), null);
});
test("제주 시트: 노선번호 칸 아래 이 번호 회차만, X 는 안 지남, 같은 번호 시트만", () => {
  const rows = [["", "구분", "노선번호", "A", "B"], ["", "1", "300번", "6:00\n(출발)", "06:10"], ["", "2", "301번", "06:05", "X"], ["", "3", "300번", "X", "06:30"]];
  const t = parseJejuSheets([{ name: "300 A-B", rows }, { name: "301 A-B", rows }], "300");
  assert.equal(t.length, 1);
  assert.deepEqual(t[0].trips, [[360, 370], [null, 390]]);
  assert.deepEqual(t[0].days, ["weekday", "sat", "holi"]);
});
test("진주 갈래: 첫차~막차 안, 다른 갈래 한 번뿐인 회차는 뺌", () => {
  const me = { brt_id: 1, firsttime: "0620", lasttime: "2055" };
  const group = [me, { brt_id: 2, firsttime: "0755", lasttime: "0755" }];
  assert.deepEqual(pickJinju([380, 475, 530, 1300], me, group), [380, 530]);
  assert.deepEqual(pickJinju([380, 475], group[1], group), [475]);
});
test("엑셀(zip) 읽기: 저장·압축 파일을 풀고 공유 문자열로 칸을 채움", async () => {
  const enc = new TextEncoder();
  const files = {
    "xl/workbook.xml": '<workbook><sheets><sheet name="300 A-B" sheetId="1"/></sheets></workbook>',
    "xl/sharedStrings.xml": "<sst><si><t>노선번호</t></si><si><t>06:10</t></si></sst>",
    "xl/worksheets/sheet1.xml": '<worksheet><sheetData><row r="1"><c r="B1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row></sheetData></worksheet>',
  };
  // 저장(압축 안 함) zip 만들기
  const parts = [], central = [];
  let off = 0;
  for (const [name, text] of Object.entries(files)) {
    const n = enc.encode(name), d = enc.encode(text);
    const h = new Uint8Array(30 + n.length); const v = new DataView(h.buffer);
    v.setUint32(0, 0x04034b50, true); v.setUint32(18, d.length, true); v.setUint32(22, d.length, true); v.setUint16(26, n.length, true); h.set(n, 30);
    const c = new Uint8Array(46 + n.length); const cv = new DataView(c.buffer);
    cv.setUint32(0, 0x02014b50, true); cv.setUint32(20, d.length, true); cv.setUint32(24, d.length, true); cv.setUint16(28, n.length, true); cv.setUint32(42, off, true); c.set(n, 46);
    parts.push(h, d); central.push(c); off += h.length + d.length;
  }
  const cdSize = central.reduce((s, c) => s + c.length, 0);
  const e = new Uint8Array(22); const ev = new DataView(e.buffer);
  ev.setUint32(0, 0x06054b50, true); ev.setUint16(8, central.length, true); ev.setUint16(10, central.length, true); ev.setUint32(12, cdSize, true); ev.setUint32(16, off, true);
  const zip = new Uint8Array(off + cdSize + 22); let p = 0;
  for (const x of [...parts, ...central, e]) { zip.set(x, p); p += x.length; }
  assert.equal((await unzip(zip)).size, 3);
  const sh = await readXlsx(zip);
  assert.equal(sh[0].name, "300 A-B");
  assert.deepEqual(sh[0].rows[0], ["", "노선번호", "06:10"]);
});
