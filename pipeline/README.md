# 정류장·노선 데이터 자동 갱신 (GitHub Release)

매주 GitHub Actions(`.github/workflows/main.yml`)가 `pipeline/build_data.py` 를 실행해 앱의 정류장·노선 데이터를 새로 만들고,
GitHub Release `data-latest` 에 `bus_stops.db.gz` 와 `manifest.json` 을 덮어 올린다.
앱은 와이파이에서 하루 한 번 `https://github.com/eunho3023/TagoHub/releases/download/data-latest/manifest.json` 을 확인해 새 버전이면 받아 바꾼다
(주소는 app/build.gradle.kts 의 기본값, local.properties 의 DATA_MANIFEST_URL 로 바꿀 수 있음).

## 처음 한 번 설정

1. 이 프로젝트를 **공개(Public)** 저장소 eunho3023/TagoHub 에 올린다 (앱이 로그인 없이 Release 파일을 받으려면 공개여야 함).
   `local.properties`(인증키)는 .gitignore 로 올라가지 않는다. 63MB 데이터 파일 때문에 웹 업로드(25MB 제한) 대신 GitHub Desktop 이나 Android Studio 로 올린다.
2. 저장소 Settings → Secrets and variables → Actions → New repository secret: 이름 `TAGO_SERVICE_KEY`, 값은 공공데이터포털 일반 인증키.
3. Actions → '정류장·노선 데이터 갱신' → Run workflow. TAGO 하루 호출 한도에 걸리면 다음 날 다시 실행하면 이어 받는다.

## 공공데이터포털 접속이 막히면 (해외 서버 제한)

'공공데이터포털 접속 확인' 단계가 실패하면 GitHub 서버(해외)에서 막힌 것이다. 국내 PC 를 러너로 쓴다:
Settings → Actions → Runners → New self-hosted runner(Windows) 설치 후, main.yml 의 `runs-on: ubuntu-latest` 를 `runs-on: self-hosted` 로.

## 시험

    python pipeline/test_build_data.py     # 가짜 응답으로 해석·저장·압축까지 (인터넷 없이)
