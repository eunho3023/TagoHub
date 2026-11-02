// 설정 탭: 서버 주소와 서버 상태 (키는 서버에만 있고 앱에는 없음)
import { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput } from 'react-native';

import { getBase, setBase } from '@/lib/api';
import { reloadServerConfig, useServerConfig } from '@/lib/config';
import { Note, usePalette } from '@/lib/ui';

export default function SettingsTab() {
  const p = usePalette();
  const config = useServerConfig();
  const [value, setValue] = useState('');
  const [saved, setSaved] = useState('');
  const [checking, setChecking] = useState(false);

  useEffect(() => { void getBase().then((b) => { setValue(b); setSaved(b); }); }, []);

  const save = async () => {
    setChecking(true);
    const b = await setBase(value);
    setValue(b);
    setSaved(b);
    await reloadServerConfig();
    setChecking(false);
  };

  const ok = (v?: boolean) => (v ? '있음' : '없음');
  return (
    <ScrollView style={{ backgroundColor: p.bg }} contentContainerStyle={styles.pad} keyboardShouldPersistTaps="handled">
      <Text style={[styles.h, { color: p.text }]}>서버 주소</Text>
      <Text style={[styles.sub, { color: p.sub }]}>
        PC에서 `python web/server.py --host 0.0.0.0` 을 켜고, 같은 와이파이의 폰에서 PC 주소(예: 192.168.0.5:8000)를 넣으세요. 인터넷에 올린 서버가 있으면 그 주소를 넣으면 돼요.
      </Text>
      <TextInput
        value={value}
        onChangeText={setValue}
        placeholder="192.168.0.5:8000"
        placeholderTextColor={p.sub}
        autoCapitalize="none"
        autoCorrect={false}
        keyboardType="url"
        style={[styles.input, { backgroundColor: p.card, color: p.text, borderColor: p.line }]}
      />
      <Pressable style={[styles.btn, { backgroundColor: p.accent }]} onPress={save} disabled={checking}>
        <Text style={styles.btnText}>{checking ? '확인 중…' : '저장하고 연결 확인'}</Text>
      </Pressable>

      <Text style={[styles.h, { color: p.text, marginTop: 24 }]}>서버 상태</Text>
      {config ? (
        <Text style={[styles.sub, { color: p.sub }]}>
          {`연결됨 (${saved})\nTAGO 키 ${ok(config.tago)} · 카카오 키 ${ok(config.kakao)} · 서울 지하철 키 ${ok(config.seoul)}\n` +
            `지도: ${config.kakaoJs ? '카카오맵 (안 뜨면 OpenStreetMap)' : 'OpenStreetMap'} · 정류장 데이터 ${config.db?.state ?? '?'}`}
        </Text>
      ) : (
        <Note text={saved ? `${saved} 에 연결하지 못했어요. 서버가 켜져 있는지 확인해 주세요.` : '서버 주소를 넣어 주세요.'} error />
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  pad: { padding: 16 },
  h: { fontSize: 17, fontWeight: '700', marginBottom: 6 },
  sub: { fontSize: 14, lineHeight: 20 },
  input: { marginTop: 12, paddingHorizontal: 14, height: 46, borderRadius: 10, borderWidth: StyleSheet.hairlineWidth, fontSize: 16 },
  btn: { marginTop: 12, height: 46, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  btnText: { color: '#fff', fontWeight: '700', fontSize: 15 },
});
