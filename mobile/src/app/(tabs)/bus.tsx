// 버스 탭: 정류장 이름·번호로 검색
import { useState } from 'react';
import { FlatList, StyleSheet, View } from 'react-native';

import { Api, Stop } from '@/lib/api';
import { openStop } from '@/lib/nav';
import { Loading, Note, Row, SearchBox, usePalette } from '@/lib/ui';

export default function BusTab() {
  const p = usePalette();
  const [q, setQ] = useState('');
  const [busy, setBusy] = useState(false);
  const [stops, setStops] = useState<Stop[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const search = async () => {
    const t = q.trim();
    if (!t) return;
    setBusy(true);
    setError(null);
    try {
      setStops((await Api.stopsSearch(t)).stops);
    } catch (e) {
      setError((e as Error).message);
    }
    setBusy(false);
  };

  return (
    <View style={[styles.fill, { backgroundColor: p.bg }]}>
      <SearchBox value={q} onChange={setQ} onSubmit={search} placeholder="정류장 이름이나 번호" />
      {error ? <Note text={error} error /> : null}
      {busy ? <Loading /> : (
        <FlatList
          data={stops ?? []}
          keyExtractor={(s) => `${s.city}:${s.id}`}
          keyboardShouldPersistTaps="handled"
          ListEmptyComponent={stops ? <Note text="검색 결과가 없어요." /> : <Note text="정류장을 검색하면 실시간 도착 정보를 볼 수 있어요." />}
          renderItem={({ item }) => <Row title={item.name} sub={item.no || undefined} onPress={() => openStop(item)} />}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({ fill: { flex: 1 } });
