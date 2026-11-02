// 지도 탭: 내 위치 주변 정류장을 지도와 목록으로. 지도를 옮긴 뒤 "이 지역 정류장"으로 그 근처를 다시 찾음
import * as Location from 'expo-location';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import MapView from '@/components/MapView';
import { Api, Stop } from '@/lib/api';
import { distanceM, distanceText } from '@/lib/format';
import { MapEvent, MapState } from '@/lib/mapHtml';
import { openStop } from '@/lib/nav';
import { Loading, Note, Row, usePalette } from '@/lib/ui';

const CITY_HALL = { lat: 37.5665, lng: 126.978 };   // 위치를 못 받으면 서울시청

export default function MapTab() {
  const p = usePalette();
  const [here, setHere] = useState<{ lat: number; lng: number } | null>(null);
  const [origin, setOrigin] = useState(CITY_HALL);
  const [stops, setStops] = useState<Stop[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [center, setCenter] = useState<MapState['center']>({ ...CITY_HALL, zoom: 16 });
  const viewCenter = useRef(CITY_HALL);

  const load = useCallback(async (at: { lat: number; lng: number }) => {
    setError(null);
    setOrigin(at);
    try {
      const r = await Api.stopsNear(at.lat, at.lng, 600);
      setStops(r.stops);
    } catch (e) {
      setStops([]);
      setError((e as Error).message);
    }
  }, []);

  const locate = useCallback(async () => {
    try {
      const perm = await Location.requestForegroundPermissionsAsync();
      if (!perm.granted) throw new Error('위치 권한이 없어요');
      const pos = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
      const at = { lat: pos.coords.latitude, lng: pos.coords.longitude };
      setHere(at);
      setCenter({ ...at, zoom: 16 });
      await load(at);
    } catch {
      setError('내 위치를 받지 못해 서울시청 주변을 보여 줘요. 지도를 옮긴 뒤 "이 지역 정류장"을 눌러 보세요.');
      await load(CITY_HALL);
    }
  }, [load]);

  useEffect(() => { void locate(); }, [locate]);

  const state = useMemo<MapState>(() => ({
    center,
    markers: [
      ...(here ? [{ id: 'here', lat: here.lat, lng: here.lng, kind: 'here' as const, label: '내 위치' }] : []),
      ...(stops ?? []).map((s) => ({ id: s.id, lat: s.lat, lng: s.lng, kind: 'stop' as const, label: s.name })),
    ],
  }), [center, here, stops]);

  const onEvent = useCallback((e: MapEvent) => {
    if (e.type === 'moved') viewCenter.current = { lat: e.lat, lng: e.lng };
    if (e.type === 'marker') {
      const s = stops?.find((x) => x.id === e.id);
      if (s) openStop(s);
    }
  }, [stops]);

  return (
    <SafeAreaView style={[styles.fill, { backgroundColor: p.bg }]} edges={['top']}>
      <View style={styles.map}>
        <MapView state={state} onEvent={onEvent} style={styles.fill} />
        <View style={styles.buttons}>
          <Pressable style={[styles.btn, { backgroundColor: p.card }]} onPress={() => void load(viewCenter.current)}>
            <Text style={[styles.btnText, { color: p.accent }]}>이 지역 정류장</Text>
          </Pressable>
          <Pressable style={[styles.btn, { backgroundColor: p.card }]} onPress={() => void locate()}>
            <Text style={[styles.btnText, { color: p.accent }]}>내 위치</Text>
          </Pressable>
        </View>
      </View>
      <View style={styles.fill}>
        {error ? <Note text={error} error={!stops?.length} /> : null}
        {stops == null ? <Loading /> : (
          <FlatList
            data={stops}
            keyExtractor={(s) => `${s.city}:${s.id}`}
            ListEmptyComponent={error ? undefined : <Note text="근처에 정류장이 없어요." />}
            renderItem={({ item }) => (
              <Row
                title={item.name}
                sub={[item.no, distanceText(distanceM(origin.lat, origin.lng, item.lat, item.lng))].filter(Boolean).join(' · ')}
                onPress={() => openStop(item)}
              />
            )}
          />
        )}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  map: { height: '50%' },
  buttons: { position: 'absolute', right: 12, bottom: 12, gap: 8, alignItems: 'flex-end' },
  btn: { paddingHorizontal: 14, height: 40, borderRadius: 20, justifyContent: 'center', elevation: 3, shadowColor: '#000', shadowOpacity: 0.2, shadowRadius: 4, shadowOffset: { width: 0, height: 1 } },
  btnText: { fontSize: 14, fontWeight: '700' },
});
