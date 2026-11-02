// 폰(안드로이드·아이폰)용 지도: WebView 안에 mapHtml 을 띄움. 웹은 MapView.web.tsx (iframe)
import { useEffect, useMemo, useRef } from 'react';
import { StyleProp, ViewStyle } from 'react-native';
import { WebView, WebViewMessageEvent } from 'react-native-webview';

import { useServerConfig } from '@/lib/config';
import { mapHtml, MapEvent, MapState } from '@/lib/mapHtml';

export type MapViewProps = {
  state: MapState;
  onEvent?: (e: MapEvent) => void;
  style?: StyleProp<ViewStyle>;
};

export default function MapView({ state, onEvent, style }: MapViewProps) {
  const web = useRef<{ injectJavaScript: (js: string) => void } | null>(null);
  const ready = useRef(false);
  const latest = useRef(state);
  latest.current = state;
  const config = useServerConfig();
  const html = useMemo(() => mapHtml(config?.kakaoJs), [config?.kakaoJs]);

  useEffect(() => {
    if (ready.current) web.current?.injectJavaScript(`window.tago && window.tago.apply(${JSON.stringify(state)}); true;`);
  }, [state]);

  return (
    <WebView
      ref={web as never}
      style={style}
      // 카카오 JavaScript 키는 등록한 도메인에서만 동작: 카카오 개발자 사이트에 http://localhost 를 등록
      source={{ html, baseUrl: 'http://localhost' }}
      originWhitelist={['*']}
      javaScriptEnabled
      onMessage={(e: WebViewMessageEvent): void => {
        let ev: MapEvent;
        try { ev = JSON.parse(e.nativeEvent.data) as MapEvent; } catch { return; }
        if (ev.type === 'ready') {
          ready.current = true;
          web.current?.injectJavaScript(`window.tago.apply(${JSON.stringify(latest.current)}); true;`);
        }
        onEvent?.(ev);
      }}
      onLoadStart={() => { ready.current = false; }}
    />
  );
}
