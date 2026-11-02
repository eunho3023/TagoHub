// 화면 이동 (여러 탭에서 같은 화면을 열 때)
import { router } from 'expo-router';

import type { Stop } from './api';

export function openStop(s: Stop) {
  router.push({ pathname: '/stop', params: { city: s.city, id: s.id, name: s.name, no: s.no ?? '', lat: String(s.lat), lng: String(s.lng) } });
}

export function openRoute(city: string, route: string, focus?: string) {
  router.push({ pathname: '/route', params: { city, route, focus: focus ?? '' } });
}
