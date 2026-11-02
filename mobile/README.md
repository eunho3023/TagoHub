# TagoHub 모바일 (Expo)

안드로이드·아이폰·웹을 한 코드로 만드는 TagoHub 새 앱입니다. **키는 앱에 없습니다.** 앱은 TagoHub 서버(`web/server.py`)만 부르고, 서버가 키를 붙여 공공 API·카카오를 대신 부릅니다.

> 지금은 첫 단계(지도·주변 정류장, 버스 정류장 도착·노선, 지하철 도착, 설정)입니다. 나머지 기능은 기존 안드로이드 앱(`app/`)에서 하나씩 옮깁니다.

## 실행 (PC 에서)

1. 서버 켜기 (저장소 루트에서): `py web/server.py --host 0.0.0.0` — 같은 와이파이의 폰이 PC 로 접속할 수 있게 함
2. 앱 켜기: `cd mobile` → `npm install`(처음 한 번) → `npx expo start`
3. 보기
   - **안드로이드·아이폰**: 폰에 **Expo Go** 앱을 깔고, 터미널에 뜬 QR 코드를 찍습니다 (아이폰은 카메라 앱으로). Mac 이 없어도 아이폰에서 바로 볼 수 있습니다.
   - **웹**: 터미널에서 `w` (또는 `npx expo start --web`) → `http://localhost:8081`
4. 폰에서는 **설정 탭 → 서버 주소**에 PC 주소를 넣습니다 (예: `192.168.0.5:8000`. PC 주소는 `ipconfig` 의 IPv4 주소). 웹은 `http://localhost:8000` 이 기본입니다.

## 시험

```bash
npm run typecheck   # 타입 검사
npm test            # 순수 로직 (src/lib/*.test.ts, Node 내장 시험기)
```

## 구조

| 위치 | 내용 |
|---|---|
| `src/app/` | 화면 (Expo Router: 파일 하나가 화면 하나). `(tabs)/` 는 아래 탭 4개, `stop`·`route`·`station` 은 상세 화면 |
| `src/lib/api.ts` | 서버 부르기와 응답 모양. 서버 주소는 설정 탭에서 바꾸거나 빌드 때 `EXPO_PUBLIC_API_BASE` 로 정함 |
| `src/lib/format.ts` | 화면에 보여 줄 글·색 (시험 있음) |
| `src/lib/mapHtml.ts`, `src/components/MapView*.tsx` | 지도. 폰은 WebView, 웹은 iframe 안에 같은 지도 HTML 을 띄움 |

## 지도

기본은 OpenStreetMap 입니다. 서버의 `local.properties` 에 `KAKAO_JS_KEY` 가 있으면 카카오맵을 먼저 시도하고, 못 불러오면 OpenStreetMap 으로 엽니다.
카카오맵을 쓰려면 카카오 개발자 사이트 → 앱 → 플랫폼 → Web 에 **`http://localhost`**(폰 앱) 와 **`http://localhost:8081`**(웹 개발) 을 등록하세요.

## 알려진 한계

- 버스 도착은 서버가 국토교통부(TAGO) API 만 씁니다. 서울·경기 등 지역 전용 API 는 아직 서버에 없습니다.
- 지금 서버의 TAGO 키는 정류소 정보 서비스(`BusSttnInfoInqireService`)가 승인되지 않아 도착 정보가 "키가 등록되지 않았어요"로 나옵니다 (data.go.kr 에서 활용 신청 필요).
- 앱을 실제로 배포하려면 서버를 인터넷에 올려야 합니다 (`web/DEPLOY.md`, Cloud Run). 그 뒤 `EXPO_PUBLIC_API_BASE` 를 그 주소로 두고 EAS 로 빌드합니다.
