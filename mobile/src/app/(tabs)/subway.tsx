// 지하철 탭: 역 이름으로 검색 (실시간 도착은 서울·수도권)
import { router } from 'expo-router';
import { useState } from 'react';
import { FlatList, StyleSheet, View } from 'react-native';

import { Api, Station } from '@/lib/api';
import { lineColor, lineLabel } from '@/lib/format';
import { Badge, Loading, Note, Row, SearchBox, usePalette } from '@/lib/ui';

export default function SubwayTab() {
  const p = usePalette();
  const [q, setQ] = useState('');
  const [busy, setBusy] = useState(false);
  const [stations, setStations] = useState<Station[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const search = async () => {
    const t = q.trim();
    if (!t) return;
    setBusy(true);
    setError(null);
    try {
      setStations((await Api.subwaySearch(t)).stations);
    } catch (e) {
      setError((e as Error).message);
    }
    setBusy(false);
  };

  return (
    <View style={[styles.fill, { backgroundColor: p.bg }]}>
      <SearchBox value={q} onChange={setQ} onSubmit={search} placeholder="역 이름" />
      {error ? <Note text={error} error /> : null}
      {busy ? <Loading /> : (
        <FlatList
          data={stations ?? []}
          keyExtractor={(s) => s.name}
          keyboardShouldPersistTaps="handled"
          ListEmptyComponent={stations ? <Note text="검색 결과가 없어요." /> : <Note text="역을 검색하면 실시간 도착 정보를 볼 수 있어요 (서울·수도권)." />}
          renderItem={({ item }) => (
            <Row
              title={item.name}
              sub={item.lines.join(' · ')}
              right={<View style={styles.lines}>{item.lines.slice(0, 4).map((l) => <Badge key={l} text={lineLabel(l)} color={lineColor(l)} round />)}</View>}
              onPress={() => router.push({ pathname: '/station', params: { name: item.name, lines: item.lines.join('|') } })}
            />
          )}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({ fill: { flex: 1 }, lines: { flexDirection: 'row', gap: 4 } });
