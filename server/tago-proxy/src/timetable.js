// 지자체 버스정보 사이트의 공식 배차시간표 (기점 출발 시각 전체). 공공데이터 API 에는 첫차·막차·배차 간격만 있어
// 정류장 시간표를 더 정확하게 만들려고 씀. 지금은 전주(its.jeonju.go.kr)만.
// GET /w/bus/jeonju/time/<노선 9자리> → {weekday:[분...], sat:[분...], holi:[분...], note} (하루 저장)

const HEAD = { "Access-Control-Allow-Origin": "*", "Content-Type": "application/json; charset=utf-8" };
const TTL = 86400;

/** "06:00, 06:15" → [360, 375] (이상한 값은 버림, 정렬·중복 제거) */
export function clockList(s) {
  const out = new Set();
  for (const t of String(s || "").split(",")) {
    const m = /^\s*(\d{1,2}):(\d\d)\s*$/.exec(t);
    if (m && +m[1] < 30 && +m[2] < 60) out.add(+m[1] * 60 + +m[2]);
  }
  return [...out].sort((a, b) => a - b);
}

/** 전주 selectBisRouteTimeInfo 응답 → 요일별 기점 출발 시각. SAT_NLIST·HOLI_NLIST 는 토요일·일요일(공휴일)에 다니지 않는 시각 */
export function parseJeonjuTime(j) {
  const r = j && j.result;
  if (!r) return null;
  const all = clockList(r.COURSE_STIMELIST || (Array.isArray(j.timeList) ? j.timeList.join(",") : ""));
  if (!all.length) return null;
  const satOff = new Set(clockList(r.SAT_NLIST)), holiOff = new Set(clockList(r.HOLI_NLIST));
  return {
    weekday: all,
    sat: all.filter((m) => !satOff.has(m)),
    holi: all.filter((m) => !holiOff.has(m)),
    note: typeof r.BRT_TEXT === "string" ? r.BRT_TEXT.slice(0, 200) : null,
    source: "전주시 버스정보시스템",
  };
}

export async function handleJeonjuTime(id, ctx, fetcher = fetch) {
  if (!/^\d{9}$/.test(id || "")) return new Response(JSON.stringify({ error: "bad id" }), { status: 400, headers: HEAD });
  const key = new Request(`https://cache.tagohub/bus/jeonju/time/${id}`);
  const hit = await caches.default.match(key);
  if (hit) return new Response(hit.body, { headers: { ...HEAD, "X-Cache": "HIT" } });
  let out = null;
  try {
    const res = await fetcher("https://its.jeonju.go.kr/bis/selectBisRouteTimeInfo.do", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8", Accept: "application/json" },
      body: `locale=ko&routeId=${id}`,
    });
    if (res.ok) out = parseJeonjuTime(await res.json());
  } catch (_) { /* 없음 */ }
  const body = JSON.stringify(out || { none: true });
  const ttl = out ? TTL : 3600;
  ctx.waitUntil(caches.default.put(key, new Response(body, { headers: { "Cache-Control": `public, max-age=${ttl}` } })));
  return new Response(body, { headers: { ...HEAD, "X-Cache": "MISS" } });
}
