// TagoHub 서버(web/server.py) 부르기. 키는 서버가 들고 있고, 앱은 서버 주소만 앎 (앱을 풀어 봐도 키가 없음).
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Platform } from 'react-native';

import { friendlyError, normalizeBase } from './format';

const BASE_KEY = 'serverBase';

/**
 * 기본 서버 주소: 빌드할 때 EXPO_PUBLIC_API_BASE 로 정하고(배포한 Cloud Run 주소 등), 없으면
 * 웹에서는 같은 PC 의 서버(localhost:8000). 폰에서는 설정 탭에서 PC 주소(예: 192.168.0.5:8000)를 넣어야 함.
 */
const DEFAULT_BASE = normalizeBase(process.env.EXPO_PUBLIC_API_BASE ?? '') || (Platform.OS === 'web' ? 'http://localhost:8000' : '');

let base = DEFAULT_BASE;
let loaded: Promise<void> | null = null;

function load(): Promise<void> {
  loaded ??= AsyncStorage.getItem(BASE_KEY)
    .then((v) => { if (v) base = normalizeBase(v); })
    .catch(() => {});
  return loaded;
}

export async function getBase(): Promise<string> {
  await load();
  return base;
}

/** 서버 주소를 바꿈. 비우면 기본 주소로 */
export async function setBase(raw: string): Promise<string> {
  await load();
  const v = normalizeBase(raw);
  base = v || DEFAULT_BASE;
  try {
    if (v) await AsyncStorage.setItem(BASE_KEY, v);
    else await AsyncStorage.removeItem(BASE_KEY);
  } catch { /* 저장을 못 해도 이번 실행 동안은 씀 */ }
  return base;
}

export class ApiError extends Error {}

export async function api<T>(path: string, params?: Record<string, string | number | undefined | null>): Promise<T> {
  const b = await getBase();
  if (!b) throw new ApiError('서버 주소가 없어요. 설정 탭에서 서버 주소를 넣어 주세요.');
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params ?? {})) if (v != null && v !== '') q.set(k, String(v));
  const url = `${b}${path}${q.toString() ? `?${q}` : ''}`;
  let res: Response;
  try {
    res = await fetch(url);
  } catch {
    throw new ApiError('서버에 연결하지 못했어요. 서버가 켜져 있는지, 주소가 맞는지 확인해 주세요.');
  }
  const body = (await res.json().catch(() => ({ error: '서버 응답을 읽지 못했어요.' }))) as T & { error?: string };
  if (!res.ok || body.error) throw new ApiError(friendlyError(body.error || `서버 오류 (${res.status})`));
  return body;
}

// ── 서버 응답 모양 (web/server.py 의 route() 와 같음) ──────────────────────────

export type Stop = { id: string; name: string; lat: number; lng: number; no: string; city: string };

export type BusArrival = {
  routeId: string; routeNo: string; routeType?: string | null; vehicleType?: string | null;
  prevStops?: number | null; sec?: number | null;
};
export type Arrivals<A> = { supported: boolean; message?: string; arrivals: A[]; at?: number };

export type StopRoute = { routeId: string; routeNo: string; routeType?: string | null; start?: string | null; end?: string | null };

export type RouteDetail = {
  info: { routeNo: string; routeType?: string | null; start?: string | null; end?: string | null; interval?: number | null };
  stops: { id: string; name: string; no: string; ord?: number | null; lat?: number | null; lng?: number | null }[];
  buses: { vehicleNo?: string | null; nodeId: string; ord?: number | null; lat?: number | null; lng?: number | null }[];
};

export type Station = { name: string; lines: string[]; lat?: number | null; lng?: number | null };

export type SubwayArrival = {
  line: string; updown?: string | null; text?: string | null; sec?: number | null; msg?: string | null;
  now?: string | null; dest?: string | null; express?: boolean; last?: boolean;
};

export type ServerConfig = { tago: boolean; kakao: boolean; seoul: boolean; kakaoJs?: string; db?: { state?: string; message?: string } };

export const Api = {
  config: () => api<ServerConfig>('/api/config'),
  stopsNear: (lat: number, lng: number, r = 500) => api<{ stops: Stop[] }>('/api/stops/near', { lat, lng, r }),
  stopsSearch: (q: string) => api<{ stops: Stop[] }>('/api/stops/search', { q }),
  busArrivals: (city: string, node: string) => api<Arrivals<BusArrival>>('/api/bus/arrivals', { city, node }),
  stopRoutes: (city: string, node: string) => api<{ routes: StopRoute[] }>('/api/bus/stoproutes', { city, node }),
  busRoute: (city: string, route: string) => api<RouteDetail>('/api/bus/route', { city, route }),
  subwaySearch: (q: string) => api<{ stations: Station[] }>('/api/subway/search', { q }),
  subwayArrivals: (station: string) => api<Arrivals<SubwayArrival>>('/api/subway/arrivals', { station }),
};
