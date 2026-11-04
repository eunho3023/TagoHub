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
 * 다시 쓸 시간(초). 버스 도착·위치는 5초(여러 사람이 같은 걸 보면 한 번만 부르되 늦어지지 않게),
 * 노선·정류장 목록처럼 잘 안 바뀌는 것은 하루, 주변 정류장은 1시간, 그 밖은 1분.
 */
export function ttlFor(path) {
  const p = path.toLowerCase();
  if (/arvl|buslc|location|arrival|realtime|position/.test(p)) return 5;
  if (/prxmt|crdnt/.test(p)) return 3600;
  if (/schdul|kwrdfnd/.test(p)) return 86400;   // 지하철 역 시간표·역 찾기
  if (/strtpnt/.test(p)) return 1800;           // 기차 출발→도착 열차 (종류·요금)
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
  // resultCode 99: 공공데이터포털이 잠시 바쁨("가용한 세션이 존재하지 않습니다") → 저장하면 그동안 모두 빈 답을 받음
  return !/SERVICE_KEY_IS_NOT|LIMITED_NUMBER_OF_SERVICE|SERVICE_ACCESS_DENIED|returnAuthMsg|SERVICE ERROR|APPLICATION_ERROR|<resultCode>99<\/resultCode>/.test(body);
}

// ── 여러 기관 중계 (/h/<http|https>/<호스트>/<경로>) ──────────────────────────────
// 앱은 키 자리에 자리표시("TAGOHUB_<키 이름>")를 넣어 보내고, 서버가 같은 이름의 비밀 값으로 바꿔 넣는다.
// 키가 주소의 검색 조건·경로·Authorization 헤더 어디에 있든 같은 방식이라 기관마다 따로 짜지 않아도 됨.

// 중계를 허락하는 호스트 (기본 포트만). 뺀 것: 국가교통정보센터 openapi.its.go.kr(9443 포트라 Cloudflare 가 못 부름),
// 도시교통정보센터 utic.go.kr(등록한 IP 에서만 응답해서 Cloudflare 를 거치면 "허용된 IP가 아닙니다")
export const HOSTS = [
  "apis.data.go.kr",       // 공공데이터포털: TAGO·지자체 버스·공항·코레일 등
  "dapi.kakao.com",        // 카카오 로컬·길찾기 (REST 키는 Authorization 헤더)
  "apis-navi.kakaomobility.com", // 카카오모빌리티 길찾기
  "openapi.kric.go.kr",    // 철도공사 지하철
  "swopenapi.seoul.go.kr", // 서울 실시간 지하철 (키가 경로에)
  "t-data.seoul.go.kr",    // 서울 신호등
  "data.ex.co.kr",         // 한국도로공사
  "openapi.its.ulsan.kr",  // 울산 버스
];

// 서버 비밀 값으로 둘 수 있는 키 이름 (자리표시 TAGOHUB_<이름>)
export const KEY_NAMES = [
  "TAGO_SERVICE_KEY", "KLID_BUS_KEY", "KAC_API_KEY", "KRIC_SUBWAY_KEY",
  "SEOUL_SIGNAL_KEY", "EX_API_KEY", "SEOUL_SUBWAY_KEY", "KAKAO_REST_KEY",
];
export const PLACEHOLDER = /TAGOHUB_([A-Z_]+?_KEY)/g;

/** "/h/https/apis.data.go.kr/1613000/..." → { scheme, host, path }. 허락하지 않은 것이면 null */
export function hostTarget(pathname) {
  const m = /^\/h\/(https?)\/([a-z0-9.-]+)(\/.*)?$/.exec(pathname);
  if (!m || !HOSTS.includes(m[2])) return null;
  const path = m[3] || "/";
  if (path.includes("..")) return null;
  return { scheme: m[1], host: m[2], path };
}

/** 글자 안의 자리표시를 비밀 값으로 바꿈. [encode] 면 주소에 넣을 수 있게 인코딩. 없는 키를 쓰면 그 이름을 던짐 */
export function fillKeys(text, env, encode) {
  return text.replace(PLACEHOLDER, (_, name) => {
    const v = KEY_NAMES.includes(name) ? env[name] : undefined;
    if (!v) throw new Error("missing " + name);
    return encode ? encodeKey(v) : v.trim();
  });
}

/** 다시 쓸 시간: 공공데이터 규칙에 더해, 카카오 장소·주소 검색은 하루, 길찾기는 1분 */
export function hostTtl(host, path) {
  if (host === "dapi.kakao.com") return /search|geo/.test(path) ? 86400 : 60;
  if (host === "apis-navi.kakaomobility.com") return 60;
  return ttlFor(path);
}

// ── 웹 버전 (/w/dg/<기관>/<서비스>/<기능>) ─────────────────────────────────────
// 웹 페이지에는 앱 토큰을 넣을 수 없으므로(누구나 볼 수 있음) 토큰 없이 받되, 실시간 버스 도착·위치만,
// 정해진 질의 이름·값 모양만 허락한다. 결과는 앱과 같은 저장(5초)을 같이 쓰고, 주소(IP)마다 분당 요청 수를 제한한다.
export const WEB_OPS = {
  "1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList": ["cityCode", "nodeId"],
  "1613000/BusLcInfoInqireService/getRouteAcctoBusLcList": ["cityCode", "routeId"],
  // 지하철: 역 찾기, 역 시간표 (하루 저장)
  "1613000/SubwayInfo/GetKwrdFndSubwaySttnList": ["subwayStationName"],
  "1613000/SubwayInfo/GetSubwaySttnAcctoSchdulList": ["subwayStationId", "dailyTypeCode", "upDownTypeCode"],
  // 기차: 출발역 → 도착역 그날 열차 (종류·요금)
  "1613000/TrainInfo/GetStrtpntAlocFndTrainInfo": ["depPlaceId", "arrPlaceId", "depPlandTime"],
  // 고속·시외버스: 출발 → 도착 그날 버스 (등급·요금), 시외버스 터미널 찾기
  "1613000/ExpBusInfo/GetStrtpntAlocFndExpbusInfo": ["depTerminalId", "arrTerminalId", "depPlandTime"],
  "1613000/SuburbsBusInfo/GetStrtpntAlocFndSuberbsBusInfo": ["depTerminalId", "arrTerminalId", "depPlandTime"],
  "1613000/SuburbsBusInfo/GetSuberbsBusTrminlList": ["terminalNm"],
};
const WEB_VALUE = {
  cityCode: /^\d{1,6}$/, nodeId: /^[A-Za-z0-9_]{1,24}$/, routeId: /^[A-Za-z0-9_]{1,24}$/,
  subwayStationName: /^[가-힣A-Za-z0-9 ()·.-]{1,20}$/, subwayStationId: /^[A-Za-z0-9]{1,16}$/,
  dailyTypeCode: /^0[123]$/, upDownTypeCode: /^[UD]$/,
  depPlaceId: /^NAT\d{6}$/, arrPlaceId: /^NAT\d{6}$/, depPlandTime: /^20\d{6}$/,
  depTerminalId: /^NA[A-Z0-9]{3,10}$/, arrTerminalId: /^NA[A-Z0-9]{3,10}$/, terminalNm: /^[가-힣A-Za-z0-9 ()·.-]{1,20}$/,
};
/** 한 번에 받는 개수 (지하철 역 시간표는 하루치가 수백 개라 한 쪽에) */
const WEB_ROWS = {
  "1613000/SubwayInfo/GetSubwaySttnAcctoSchdulList": "1000", "1613000/TrainInfo/GetStrtpntAlocFndTrainInfo": "300",
  "1613000/ExpBusInfo/GetStrtpntAlocFndExpbusInfo": "300", "1613000/SuburbsBusInfo/GetStrtpntAlocFndSuberbsBusInfo": "300",
};

/** "/w/dg/1613000/..." + 검색 조건 → {path, search} (질의는 허락한 것만, 쪽 번호·개수는 정해 둠). 허락하지 않으면 null */
export function webRequest(pathname, search) {
  if (!pathname.startsWith("/w/dg/")) return null;
  const path = pathname.slice(6);
  const names = WEB_OPS[path];
  if (!names) return null;
  const given = new URLSearchParams(search);
  const out = new URLSearchParams();
  for (const n of names) {
    const v = given.get(n);
    if (v == null || !WEB_VALUE[n].test(v)) return null;
    out.set(n, v);
  }
  out.set("pageNo", "1");
  out.set("numOfRows", WEB_ROWS[path] || "100");
  out.set("_type", "xml");
  return { path, search: "?" + out.toString() };
}
