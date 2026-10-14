# 정류장·노선 데이터 자동 갱신 (집 PC 러너 → GitHub Release)

깃허브 Actions 에서 '정류장·노선 데이터 갱신'을 실행하면, **집 PC(윈도우)에 설치한 깃허브 러너**가 `pipeline/build_data.py` 를 돌려
앱의 정류장·노선 데이터를 새로 만들고 Release `data-latest` 에 `bus_stops.db.gz` + `manifest.json` 을 올린다.
앱은 와이파이에서 하루 한 번 `https://github.com/eunho3023/TagoHub/releases/download/data-latest/manifest.json` 을 확인해 새 버전이면 받는다.
(공공데이터포털이 해외 서버인 깃허브 기본 러너를 막을 때가 있어서 한국에 있는 집 PC 에서 돈다)

## PC 에 한 번 설치

1. **Python 3.12** (python.org) — 설치 첫 화면에서 **Add python.exe to PATH** 체크 → **Customize installation** → **Install Python for all users** 체크
   (러너가 윈도우 서비스(NETWORK SERVICE 계정)로 돌아서, '나만 설치'로 깔면 Python 을 못 찾음)
2. **Git for Windows** (git-scm.com, 기본값으로 설치) — 러너가 저장소를 내려받을 때 씀
3. **GitHub CLI** (cli.github.com, Windows MSI) — Release 에 파일 올릴 때 씀
4. **깃허브 러너**: 저장소 Settings → Actions → Runners → **New self-hosted runner** → **Windows / x64**
   - **PowerShell 을 관리자 권한으로** 열고 화면의 명령을 차례로 복사·붙여넣기 (Download → Configure)
   - `config.cmd` 질문은 Enter(기본값)로 넘기다가 **Would you like to run the runner as service? → Y**
   - 끝나면 Settings → Actions → Runners 에 러너가 **Idle**(초록)로 보임. PC 를 켜면 자동으로 대기 상태가 됨
5. PC 설정 → 시스템 → 전원: 전원 연결 시 **절전 안 함** (수집 중 잠들면 멈춤 — 멈춰도 받은 것은 저장되고 다음 실행이 이어 받음)

## 실행

- Actions → '정류장·노선 데이터 갱신' → **Run workflow** (휴대폰으로 눌러도 됨, PC 만 켜져 있으면)
- 자동: 매주 **일요일 20:00**. PC 가 꺼져 있으면 켜질 때까지 최대 24시간 기다림. 시간은 `.github/workflows/main.yml` 의 `cron` 에서 바꿈
  (UTC 기준 — 한국 시간에서 9시간을 뺌. 예: 월 07:00 → `"0 22 * * 0"`)
- 받은 응답은 러너 작업 폴더 바깥 `tagohub-cache` 에 계속 쌓여 다음 실행이 이어 받음 (노선 목록 1일, 정류장·정보 7일)

## 시험

    python pipeline/test_build_data.py     # 가짜·실제 응답으로 해석·저장·압축까지 (인터넷 없이)
