// 노선: 정류장 타임라인과 운행 중인 버스 위치 (10초마다 새로 받음). 지도에는 노선 선과 버스
import { useLocalSearchParams } from 'expo-router';
import Stack from 'expo-router/stack';
import { useCallback, useMemo, useRef, useState } from 'react';
import { FlatList, StyleSheet, Text, View } from 'react-native';

import MapView from '@/components/MapView';
import { Api, RouteDetail } from '@/lib/api';
import { routeColor } from '@/lib/format';
import { MapState } from '@/lib/mapHtml';
import { Loading, Note, useEvery, usePalette } from '@/lib/ui';

export default function RouteScreen() {
  const p = usePalette();
  const { city, route, focus } = useLocalSearchParams<{ city: string; route: string; focus?: string }>();
  const [data, setData] = useState<RouteDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const first = useRef(true);

  const load = useCallback(async () => {
    try {
      setData(await Api.busRoute(city, route));
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [city, route]);
  useEvery(() => { void load(); }, 10000, [load]);

  const color = routeColor(data?.info.routeType);
  const map = useMemo<MapState>(() => {
    const pts = (data?.stops ?? []).filter((s) => s.lat && s.lng).map((s) => [s.lat!, s.lng!] as [number, number]);
    const fit = first.current && pts.length > 1;
    if (fit) first.current = false;
    return {
      markers: (data?.buses ?? []).filter((b) => b.lat && b.lng).map((b, i) => ({ id: `bus${i}`, lat: b.lat!, lng: b.lng!, kind: 'bus' as const, label: b.vehicleNo ?? '버스' })),
      line: pts.length > 1 ? { points: pts, color } : null,
      fit,
    };
  }, [data, color]);

  if (!data) return <View style={[styles.fill, { backgroundColor: p.bg }]}>{error ? <Note text={error} error /> : <Loading />}</View>;

  const busAt = (s: RouteDetail['stops'][number]) => data.buses.find((b) => (b.ord != null && b.ord === s.ord) || b.nodeId === s.id);
  return (
    <View style={[styles.fill, { backgroundColor: p.bg }]}>
      <Stack.Screen options={{ title: `${data.info.routeNo}번` }} />
      <MapView state={map} style={styles.map} />
      <View style={[styles.head, { backgroundColor: color }]}>
        <Text style={styles.no}>{data.info.routeNo}</Text>
        <Text style={styles.meta}>{[data.info.start, data.info.end].filter(Boolean).join(' → ')}</Text>
        <Text style={styles.meta}>{[data.info.interval ? `배차 ${data.info.interval}분` : '', `운행 중 ${data.buses.length}대`].filter(Boolean).join(' · ')}</Text>
      </View>
      {error ? <Note text={error} error /> : null}
      <FlatList
        data={data.stops}
        keyExtractor={(s, i) => `${s.id}:${i}`}
        initialScrollIndex={Math.max(0, data.stops.findIndex((s) => s.id === focus))}
        getItemLayout={(_, i) => ({ length: 56, offset: 56 * i, index: i })}
        renderItem={({ item }) => {
          const bus = busAt(item);
          const here = item.id === focus;
          return (
            <View style={[styles.stop, { borderColor: p.line, backgroundColor: here ? p.line : p.card }]}>
              <View style={[styles.rail, { backgroundColor: color }]} />
              <View style={[styles.dot, { borderColor: color, backgroundColor: bus ? color : p.card }]} />
              <View style={styles.fill}>
                <Text style={[styles.stopName, { color: p.text }]} numberOfLines={1}>{item.name}</Text>
                <Text style={{ color: bus ? color : p.sub, fontSize: 12 }} numberOfLines={1}>{bus ? `버스 · ${bus.vehicleNo ?? '운행 중'}` : item.no}</Text>
              </View>
            </View>
          );
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  map: { height: 200 },
  head: { padding: 16, gap: 2 },
  no: { color: '#fff', fontSize: 28, fontWeight: '900' },
  meta: { color: '#fff', fontSize: 14, opacity: 0.95 },
  stop: { height: 56, flexDirection: 'row', alignItems: 'center', gap: 12, paddingRight: 16, borderBottomWidth: StyleSheet.hairlineWidth },
  rail: { position: 'absolute', left: 27, top: 0, bottom: 0, width: 4 },
  dot: { marginLeft: 21, width: 16, height: 16, borderRadius: 8, borderWidth: 3 },
  stopName: { fontSize: 15, fontWeight: '600' },
});
