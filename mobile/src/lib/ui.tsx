// 여러 화면이 같이 쓰는 작은 부품 (색은 시스템 라이트·다크를 따름)
import { ReactNode, useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, useColorScheme, View } from 'react-native';

export function usePalette() {
  const dark = useColorScheme() === 'dark';
  return dark
    ? { bg: '#0f1115', card: '#1a1d23', text: '#eceef2', sub: '#9aa3af', line: '#2a2f38', accent: '#4c8dff', danger: '#ff6b6b' }
    : { bg: '#f4f5f7', card: '#ffffff', text: '#14171c', sub: '#5f6875', line: '#e3e6ea', accent: '#1f6feb', danger: '#d93025' };
}

/** 1초마다 다시 그리게 하는 시계 (도착 시간을 줄여 보여 줄 때) */
export function useNow(ms = 1000): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(t);
  }, [ms]);
  return now;
}

/** [fn] 을 바로 한 번, 그 뒤 [ms] 마다 부름 */
export function useEvery(fn: () => void, ms: number, deps: unknown[]) {
  useEffect(() => {
    fn();
    const t = setInterval(fn, ms);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}

export function Row({ left, title, sub, right, onPress }: { left?: ReactNode; title: string; sub?: string; right?: ReactNode; onPress?: () => void }) {
  const p = usePalette();
  return (
    <Pressable onPress={onPress} disabled={!onPress} style={({ pressed }) => [styles.row, { backgroundColor: pressed ? p.line : p.card, borderColor: p.line }]}>
      {left}
      <View style={styles.grow}>
        <Text style={[styles.title, { color: p.text }]} numberOfLines={1}>{title}</Text>
        {sub ? <Text style={[styles.sub, { color: p.sub }]} numberOfLines={1}>{sub}</Text> : null}
      </View>
      {right}
    </Pressable>
  );
}

export function Badge({ text, color, round }: { text: string; color: string; round?: boolean }) {
  return (
    <View style={[styles.badge, { backgroundColor: color }, round && styles.round]}>
      <Text style={styles.badgeText} numberOfLines={1}>{text}</Text>
    </View>
  );
}

export function Note({ text, error }: { text: string; error?: boolean }) {
  const p = usePalette();
  return <Text style={[styles.note, { color: error ? p.danger : p.sub }]}>{text}</Text>;
}

export function Loading() {
  const p = usePalette();
  return <ActivityIndicator style={styles.loading} color={p.accent} />;
}

export function SearchBox({ value, onChange, onSubmit, placeholder }: { value: string; onChange: (v: string) => void; onSubmit: () => void; placeholder: string }) {
  const p = usePalette();
  return (
    <TextInput
      value={value}
      onChangeText={onChange}
      onSubmitEditing={onSubmit}
      placeholder={placeholder}
      placeholderTextColor={p.sub}
      returnKeyType="search"
      autoCorrect={false}
      style={[styles.search, { backgroundColor: p.card, color: p.text, borderColor: p.line }]}
    />
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: 16, paddingVertical: 12, borderBottomWidth: StyleSheet.hairlineWidth, minHeight: 56 },
  grow: { flex: 1 },
  title: { fontSize: 16, fontWeight: '600' },
  sub: { fontSize: 13, marginTop: 2 },
  badge: { minWidth: 44, paddingHorizontal: 8, height: 26, borderRadius: 6, alignItems: 'center', justifyContent: 'center' },
  round: { minWidth: 28, width: 28, height: 28, borderRadius: 14, paddingHorizontal: 0 },
  badgeText: { color: '#fff', fontWeight: '700', fontSize: 13 },
  note: { padding: 16, fontSize: 14, lineHeight: 20 },
  loading: { padding: 24 },
  search: { margin: 12, paddingHorizontal: 14, height: 46, borderRadius: 10, borderWidth: StyleSheet.hairlineWidth, fontSize: 16 },
});
