// 지하철역: 실시간 도착 (20초마다 새로 받음, 서울·수도권)
import { useLocalSearchParams } from 'expo-router';
import Stack from 'expo-router/stack';
import { useCallback, useState } from 'react';
import { FlatList, StyleSheet, Text, View } from 'react-native';

import { Api, SubwayArrival } from '@/lib/api';
import { lineColor, lineLabel, remaining, subwayEta, subwayOrder } from '@/lib/format';
import { Badge, Loading, Note, Row, useEvery, useNow, usePalette } from '@/lib/ui';

export default function StationScreen() {
  const p = usePalette();
  const { name, lines } = useLocalSearchParams<{ name: string; lines?: string }>();
  const [rows, setRows] = useState<SubwayArrival[] | null>(null);
  const [got, setGot] = useState(0);
  const [note, setNote] = useState<string | null>(null);
  const now = useNow();

  const load = useCallback(async () => {
    try {
      const r = await Api.subwayArrivals(name);
      if (!r.supported) { setNote(r.message ?? '이 역은 실시간 정보를 지원하지 않아요.'); setRows([]); return; }
      setRows([...r.arrivals].sort((a, b) => subwayOrder(a.sec, a.msg) - subwayOrder(b.sec, b.msg)));
      setGot(Date.now());
      setNote(null);
    } catch (e) {
      setNote(`${(e as Error).message}\n실시간 도착은 서울·수도권 역만 돼요.`);
      setRows((old) => old ?? []);
    }
  }, [name]);
  useEvery(() => { void load(); }, 20000, [load]);

  const lineList = (lines ?? '').split('|').filter(Boolean);
  return (
    <View style={[styles.fill, { backgroundColor: p.bg }]}>
      <Stack.Screen options={{ title: `${name}역` }} />
      <View style={[styles.head, { backgroundColor: p.card, borderColor: p.line }]}>
        <Text style={[styles.name, { color: p.text }]}>{name}</Text>
        <View style={styles.lines}>{lineList.map((l) => <Badge key={l} text={lineLabel(l)} color={lineColor(l)} round />)}</View>
      </View>
      {note ? <Note text={note} error={!rows?.length} /> : null}
      {rows == null ? <Loading /> : (
        <FlatList
          data={rows}
          keyExtractor={(a, i) => `${a.line}:${a.dest}:${i}`}
          ListEmptyComponent={note ? undefined : <Note text="지금 도착 예정인 열차가 없어요." />}
          renderItem={({ item }) => {
            const sec = item.sec ? remaining(item.sec, got, now) : null;
            const eta = subwayEta(sec, item.msg);
            return (
              <Row
                left={<Badge text={lineLabel(item.line)} color={lineColor(item.line)} round />}
                title={item.dest ? `${item.dest}행` : (item.text ?? '')}
                sub={[item.updown, item.now ? `현재 ${item.now}` : '', item.express ? '급행' : '', item.last ? '막차' : ''].filter(Boolean).join(' · ') || undefined}
                right={<Text style={[styles.eta, { color: eta === '곧 도착' ? p.danger : p.text }]}>{eta}</Text>}
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
  head: { padding: 16, borderBottomWidth: StyleSheet.hairlineWidth, flexDirection: 'row', alignItems: 'center', gap: 10 },
  name: { fontSize: 22, fontWeight: '800' },
  lines: { flexDirection: 'row', gap: 4 },
  eta: { fontSize: 15, fontWeight: '700' },
});
