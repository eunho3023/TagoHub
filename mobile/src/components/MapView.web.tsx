// 웹용 지도: iframe 안에 mapHtml 을 띄우고 postMessage 로 주고받음 (WebView 는 웹에서 안 돌아감)
import { useEffect, useMemo, useRef } from 'react';
import { StyleSheet, View } from 'react-native';

import { useServerConfig } from '@/lib/config';
import { mapHtml, MapEvent } from '@/lib/mapHtml';

import type { MapViewProps } from './MapView';

export default function MapView({ state, onEvent, style }: MapViewProps) {
  const frame = useRef<HTMLIFrameElement | null>(null);
  const ready = useRef(false);
  const latest = useRef(state);
  latest.current = state;
  const handler = useRef(onEvent);
  handler.current = onEvent;
  const config = useServerConfig();
  const html = useMemo(() => mapHtml(config?.kakaoJs), [config?.kakaoJs]);

  useEffect(() => {
    const listen = (e: MessageEvent) => {
      if (e.source !== frame.current?.contentWindow || typeof e.data !== 'string') return;
      let ev: MapEvent;
      try { ev = JSON.parse(e.data) as MapEvent; } catch { return; }
      if (ev.type === 'ready') {
        ready.current = true;
        frame.current?.contentWindow?.postMessage(JSON.stringify(latest.current), '*');
      }
      handler.current?.(ev);
    };
    window.addEventListener('message', listen);
    return () => window.removeEventListener('message', listen);
  }, []);

  useEffect(() => { ready.current = false; }, [html]);

  useEffect(() => {
    if (ready.current) frame.current?.contentWindow?.postMessage(JSON.stringify(state), '*');
  }, [state]);

  return (
    <View style={[styles.box, style]}>
      <iframe ref={frame} srcDoc={html} title="지도" style={{ border: 0, width: '100%', height: '100%' }} />
    </View>
  );
}

const styles = StyleSheet.create({ box: { overflow: 'hidden' } });
