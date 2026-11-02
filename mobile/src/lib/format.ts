// 화면에 보여 줄 글과 색 (서버 응답을 사람이 읽는 말로). 화면 코드와 따로 두어 `npm test` 로 시험함.

/** 도착까지 남은 초 → "3분 05초" / "곧 도착" / "정보없음" */
export function etaText(sec: number | null | undefined): string {
  if (sec == null) return '정보없음';
  if (sec <= 30) return '곧 도착';
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return m ? `${m}분 ${String(s).padStart(2, '0')}초` : `${s}초`;
}

/** 받은 뒤 흐른 시간만큼 남은 초를 줄임 (0 아래로는 안 내려감) */
export function remaining(sec: number | null | undefined, receivedAt: number, now: number): number | null {
  if (sec == null) return null;
  return Math.max(0, Math.round(sec - (now - receivedAt) / 1000));
}

/** 지하철 도착 한 줄: 초가 있으면 "곧 도착"/"N분", 없으면 서버 문구("[3]번째 전역" → "3정거장 전") */
export function subwayEta(sec: number | null | undefined, msg: string | null | undefined): string {
  if (sec) return sec < 60 ? '곧 도착' : `${Math.ceil(sec / 60)}분`;
  return (msg ?? '').replace(/\[(\d+)\]번째 전역.*/, '$1정거장 전').slice(0, 14);
}

/** 지하철 도착 정렬 순서: 초가 있으면 그 값, 도착·진입·출발 문구면 맨 앞, 나머지는 뒤 */
export function subwayOrder(sec: number | null | undefined, msg: string | null | undefined): number {
  if (sec) return sec;
  return /도착|진입|출발/.test(msg ?? '') ? 1 : 99999;
}

/** 버스 노선 종류 → 색 (웹 버전과 같음) */
export function routeColor(type: string | null | undefined): string {
  const t = type ?? '';
  if (/간선/.test(t)) return '#3D5BAB';
  if (/지선/.test(t)) return '#3E9A2E';
  if (/광역|급행|직행|좌석/.test(t)) return '#D7262E';
  if (/마을/.test(t)) return '#7FA92A';
  if (/순환/.test(t)) return '#C9940A';
  if (/심야|올빼미/.test(t)) return '#2B2B5E';
  if (/농어촌/.test(t)) return '#E5622C';
  if (/공항/.test(t)) return '#0090D2';
  if (/일반/.test(t)) return '#14A0A8';
  return '#607080';
}

const LINE_COLORS: Record<string, string> = {
  '1호선': '#0052A4', '2호선': '#00A84D', '3호선': '#EF7C1C', '4호선': '#00A5DE', '5호선': '#996CAC',
  '6호선': '#CD7C2F', '7호선': '#747F00', '8호선': '#E6186C', '9호선': '#BDB092',
  공항철도: '#0090D2', 경의중앙선: '#5FB59A', 수인분당선: '#E0A800', 신분당선: '#D4003B', 경춘선: '#0C8E72', 우이신설선: '#9DB516',
};

/** 지하철 노선 이름 → 색 ("수도권 2호선" 처럼 앞뒤에 글이 붙어도 찾음) */
export function lineColor(line: string | null | undefined): string {
  const l = line ?? '';
  if (LINE_COLORS[l]) return LINE_COLORS[l];
  const hit = Object.entries(LINE_COLORS).find(([k]) => l.includes(k.replace('선', '')));
  return hit ? hit[1] : '#4F5B6B';
}

/** 노선 동그라미에 넣을 짧은 글: "2호선" → "2", "공항철도" → "공항" */
export function lineLabel(line: string | null | undefined): string {
  const l = line ?? '';
  const m = /^(\d+)호선/.exec(l);
  return m ? m[1] : l.replace('선', '').slice(0, 2);
}

/** 두 좌표 사이 거리(m) */
export function distanceM(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const r = 6371000;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * r * Math.asin(Math.sqrt(a));
}

/** 거리 글: 950 → "950m", 1234 → "1.2km" */
export function distanceText(m: number): string {
  return m < 1000 ? `${Math.round(m)}m` : `${(m / 1000).toFixed(1)}km`;
}

/** 서버 주소 정리: 앞뒤 공백·끝의 / 를 떼고, http(s):// 가 없으면 http:// 를 붙임. 비어 있으면 '' */
export function normalizeBase(raw: string): string {
  const t = raw.trim().replace(/\/+$/, '');
  if (!t) return '';
  return /^https?:\/\//i.test(t) ? t : `http://${t}`;
}

/** 서버가 넘긴 오류 글을 사람이 읽는 말로 (공공 API 의 날것 응답이 그대로 오는 경우) */
export function friendlyError(msg: string): string {
  if (/SERVICE_KEY_IS_NOT_REGISTERED|등록되지 않은 서비스키/.test(msg)) return '서버의 공공데이터 키가 이 서비스에 아직 등록(승인)되지 않았어요.';
  if (/LIMITED_NUMBER_OF_SERVICE_REQUESTS|요청.*초과/.test(msg)) return '오늘 공공데이터 호출 한도를 다 썼어요. 내일 다시 해 주세요.';
  return msg.length > 200 ? `${msg.slice(0, 200)}…` : msg;
}
