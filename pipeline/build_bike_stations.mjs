// 전국자전거대여소표준데이터(공공데이터포털 표준데이터 15017319) → server/tago-proxy/src/bikestations.js
// 실시간 대수가 없는 대여소 위치·거치대 수·운영시간·요금. 서버 /w/topis/bike 가 실시간 자료 옆에 붙여 보냄 (실시간 대여소 가까이 있는 것은 뺌).
// 로그인·인증키 필요 없음 (포털 화면의 JSON '다운로드' 단추가 쓰는 주소를 그대로 부름. pipeline/fetch_datago_std.py 와 같은 방식).
//
//     node pipeline/build_bike_stations.mjs
import { writeFileSync } from "node:fs";

const PK = "15017319";
const BASE = "https://www.data.go.kr";
const UA = { "User-Agent": "Mozilla/5.0" };
const OUT = new URL("../server/tago-proxy/src/bikestations.js", import.meta.url);

const get = async (u) => {
  const r = await fetch(u, { headers: UA });
  if (!r.ok) throw new Error(`${r.status} ${u}`);
  return r.json();
};

const head = await get(`${BASE}/download/columList.json?pk=${PK}&ext=JSON`);
const vo = head.tableVO, total = +head.totalCount, per = 10000;
const cols = Array.isArray(vo.colNmList) ? vo.colNmList : [vo.colNmList];
const rows = [];
for (let page = 1; (page - 1) * per < total; page++) {
  const q = new URLSearchParams();
  for (const c of cols) q.append("colNmList", c);
  q.set("totalCount", total); q.set("svcTableNm", vo.svcTableNm); q.set("perPage", per); q.set("page", page); q.set("publicDataPk", PK);
  rows.push(...(await get(`${BASE}/download/standard.json?${q}`)));
}

const t = (s) => String(s ?? "").replace(/\s+/g, " ").trim();
const hm = (s) => (/^\d{1,2}:\d{2}$/.test(t(s)) ? t(s) : "");
let ref = "";
const out = [];
const seen = new Set();
for (const r of rows) {
  const lat = Math.round(+r.LATITUDE * 1e6) / 1e6, lng = Math.round(+r.LONGITUDE * 1e6) / 1e6;
  if (!(lat > 33 && lat < 39 && lng > 124 && lng < 132)) continue;
  const name = t(r.BCYCL_LEND_NM) || "공영자전거 대여소";
  const k = `${name}|${lat}|${lng}`;
  if (seen.has(k)) continue;
  seen.add(k);
  const open = hm(r.OPER_OPEN_HM), close = hm(r.OPER_CLOSE_HM);
  const info = [
    open && close ? (open === "00:00" && (close === "23:59" || close === "24:00") ? "24시간" : `${open}~${close}`) : "",
    t(r.RSTDE) && t(r.RSTDE) !== "연중무휴" ? `${t(r.RSTDE)} 쉼` : "",
    t(r.CHRGE_SE) === "무료" ? "무료" : t(r.BCYCL_USE_CHARGE).slice(0, 40),
    t(r.PHONE_NUMBER),
  ].filter(Boolean).join(" · ");
  out.push([name, lat, lng, +r.HOLDER_CO || 0, info]);
  if (t(r.REFERENCE_DATE) > ref) ref = t(r.REFERENCE_DATE);
}
out.sort((a, b) => a[1] - b[1] || a[2] - b[2]);

writeFileSync(OUT, `// 만든 곳: pipeline/build_bike_stations.mjs (전국자전거대여소표준데이터, 공공데이터포털 ${PK}). 손으로 고치지 말 것
// 실시간 대수 없는 대여소 [이름, 위도, 경도, 거치대 수, 운영시간·요금·전화]. 기준일 ${ref}, ${out.length}곳
export const STD_BIKES_REF = ${JSON.stringify(ref)};
export const STD_BIKES = [
${out.map((x) => JSON.stringify(x)).join(",\n")},
];
`);
console.log(`${rows.length}건 → ${out.length}곳 (기준일 ${ref}) → ${OUT.pathname}`);
