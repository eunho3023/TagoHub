// 정류장: 실시간 도착 (15초마다 새로 받고, 그 사이에는 1초씩 줄여 보여 줌). 노선을 누르면 노선 화면
import { useLocalSearchParams } from 'expo-router';
import Stack from 'expo-router/stack';
import { useCallback, useMemo, useState } from 'react';
import { FlatList, StyleSheet, Text, View } from 'react-native';

import MapView from '@/components/MapView';
import { Api, BusArrival, StopRoute } from '@/lib/api';
import { etaText, remaining, routeColor } from '@/lib/format';
import { MapState } from '@/lib/mapHtml';
import { openRoute } from '@/lib/nav';
import { Badge, Loading, Note, Row, useEvery, useNow, usePalette } from '@/lib/ui';

type Line = BusArrival & { info?: StopRoute };

export default function StopScreen() {
  const p = usePalette();
  const { city, id, name, no, lat, lng } = useLocalSearchParams<{ city: string; id: string; name: string; no?: string; lat?: string; lng?: string }>();
  const [rows, setRows] = useState<Line[] | null>(null);
  const [got, setGot] = useState(0);
  const [note, setNote] = useState<string | null>(null);
  const now = useNow();

  const load = useCallback(async () => {
    try {
      const [a, r] = await Promise.all([Api.busArrivals(city, id), Api.stopRoutes(city, id).catch(() => ({ routes: [] as StopRoute[] }))]);
      if (!a.supported) { setNote(a.message ?? '이 정류장은 실시간 정보를 받을 수 없어요.'); setRows([]); return; }
      const known = new Map(r.routes.map((x) => [x.routeId, x]));
      const lines: Line[] = a.arrivals.map((x) => ({ ...x, info: known.get(x.routeId) }));
      const has = new Set(lines.map((x) => x.routeId));
      r.routes.filter((x) => !has.has(x.routeId)).forEach((x) => lines.push({ routeId: x.routeId, routeNo: x.routeNo, routeType: x.routeType, sec: null, info: x }));
      setNote(null);
      setRows(lines);
      setGot(Date.now());
    } catch (e) {
      setNote((e as Error).message);
      setRows((old) => old ?? []);
    }
  }, [city, id]);
  useEvery(() => { void load(); }, 15000, [load]);

  const la = Number(lat), ln = Number(lng);
  const map = useMemo<MapState | null>(() => (Number.isFinite(la) && Number.isFinite(ln) && la !== 0
    ? { center: { lat: la, lng: ln, zoom: 17 }, markers: [{ id, lat: la, lng: ln, kind: 'pin', label: name }] }
    : null), [la, ln, id, name]);

  return (
    <View style={[styles.fill, { backgroundColor: p.bg }]}>
      <Stack.Screen options={{ title: name || '정류장' }} />
      {map ? <MapView state={map} style={styles.map} /> : null}
      <View style={[styles.head, { backgroundColor: p.card, borderColor: p.line }]}>
        <Text style={[styles.name, { color: p.text }]}>{name}</Text>
        <Text style={{ color: p.sub }}>{[no, got ? `${new Date(got).toTimeString().slice(0, 8)} 갱신` : ''].filter(Boolean).join(' · ')}</Text>
      </View>
      {note ? <Note text={note} error={!rows?.length} /> : null}
      {rows == null ? <Loading /> : (
        <FlatList
          data={rows}
          keyExtractor={(x) => x.routeId}
          ListEmptyComponent={note ? undefined : <Note text="이 정류장의 버스 정보가 없어요." />}
          renderItem={({ item }) => {
            const sec = remaining(item.sec, got, now);
            const ends = item.info ? [item.info.start, item.info.end].filter(Boolean).join(' → ') : '';
            return (
              <Row
                left={<Badge text={item.routeNo} color={routeColor(item.routeType)} />}
                title={ends || item.routeNo}
                sub={[item.routeType, item.vehicleType].filter(Boolean).join(' · ') || undefined}
                right={
                  <View style={styles.eta}>
                    <Text style={[styles.etaText, { color: sec != null && sec <= 30 ? p.danger : p.text }]}>{etaText(sec)}</Text>
                    {item.prevStops != null ? <Text style={{ color: p.sub, fontSize: 12 }}>{item.prevStops}정류장 전</Text> : null}
                  </View>
                }
                onPress={() => openRoute(city, item.routeId, id)}
              />
            );
          }}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  map: { height: 180 },
  head: { padding: 16, borderBottomWidth: StyleSheet.hairlineWidth, gap: 4 },
  name: { fontSize: 20, fontWeight: '800' },
  eta: { alignItems: 'flex-end' },
  etaText: { fontSize: 15, fontWeight: '700', fontVariant: ['tabular-nums'] },
});
