// 공공데이터포털(apis.data.go.kr) 중계 규칙. Worker(index.js)와 시험(test/proxy.test.js)이 함께 쓴다.
// 앱은 키 없이 /dg/<기관번호>/<서비스>/<기능>?... 로 묻고, 서버가 키를 붙여 공공데이터포털에 묻는다.

export const UPSTREAM = "https://apis.data.go.kr/";

// 중계를 허락하는 기관(경로 첫 칸). 아무 공공데이터나 대신 불러 주는 열린 중계가 되지 않게
export const ALLOWED = [
  "1613000", // 국토교통부 TAGO (버스·지하철·기차·고속버스 등)
  "4641000", // 전주시 버스
  "6410000", // 경기도 버스
  "6260000", // 부산 버스
  "6290000", // 여수 버스
  "4810000", // 울산 버스
];

/** 앱이 보낸 경로("/dg/1613000/ArvlInfoInqireService/...")에서 공공데이터포털 경로("1613000/...")를 꺼냄. 허락하지 않은 경로면 null */
export function upstreamPath(pathname) {
  if (!pathname.startsWith("/dg/")) return null;
  const rest = pathname.slice(4);
  if (rest.includes("..") || rest.includes("//")) return null;
  const agency = rest.split("/")[0];
  return ALLOWED.includes(agency) && rest.length > agency.length + 1 ? rest : null;
}

/**
 * 다시 쓸 시간(초). 버스 도착·위치는 15초(서버 갱신이 30~60초라 여러 사람이 같은 걸 보면 한 번만 부름),
 * 노선·정류장 목록처럼 잘 안 바뀌는 것은 하루, 주변 정류장은 1시간, 그 밖은 1분.
 */
export function ttlFor(path) {
  const p = path.toLowerCase();
  if (/arvl|buslc|location|arrival|realtime|position/.test(p)) return 15;
  if (/prxmt|crdnt/.test(p)) return 3600;
  if (/ctycode|thrghsttn|routeinfo|routeno|sttnno|trminl|grad|sttnlist|nosun|drawing/.test(p)) return 86400;
  return 60;
}

/** 공공데이터포털 키: Encoding 키(%2F 포함)는 그대로, Decoding 키(+, / 포함)는 인코딩 (앱 Network.kt 와 같은 규칙) */
export function encodeKey(raw) {
  const k = raw.trim();
  return k.includes("%") ? k : encodeURIComponent(k);
}

/** 앱이 보낸 검색 조건에 키를 붙인 실제 요청 주소. 앱이 serviceKey 를 보내도 버리고 서버 키를 씀 */
export function upstreamUrl(path, search, rawKey) {
  const params = new URLSearchParams(search);
  params.delete("serviceKey");
  params.delete("ServiceKey");
  const q = params.toString();
  return `${UPSTREAM}${path}?serviceKey=${encodeKey(rawKey)}${q ? "&" + q : ""}`;
}

/** 저장해 둘 때 쓰는 이름 (키를 빼고 검색 조건을 정렬해 같은 요청이면 같은 이름) */
export function cacheKey(path, search) {
  const params = [...new URLSearchParams(search)].filter(([k]) => k.toLowerCase() !== "servicekey");
  params.sort(([a, av], [b, bv]) => (a === b ? (av < bv ? -1 : av > bv ? 1 : 0) : a < b ? -1 : 1));
  return `https://cache.tagohub/${path}?${new URLSearchParams(params).toString()}`;
}

/** 저장해도 되는 응답인지: 정상(200)이고 키·한도 오류가 아닌 것만 (오류를 저장하면 그동안 모두 오류를 받음) */
export function cacheable(status, body) {
  if (status !== 200) return false;
  return !/SERVICE_KEY_IS_NOT|LIMITED_NUMBER_OF_SERVICE|SERVICE_ACCESS_DENIED|returnAuthMsg|SERVICE ERROR|APPLICATION_ERROR/.test(body);
}
