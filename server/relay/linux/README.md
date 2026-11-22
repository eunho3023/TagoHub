# 노트북을 타볼까 국내 중계 서버로 (Ubuntu Server)

이 PC 대신 남는 노트북이 24시간 국내 중계를 맡게 합니다. 화면 없는 리눅스(Ubuntu Server)라 가볍고, 켜지면 로그인 없이 중계가 돌고, 덮개를 닫아도 계속 돕니다.
대상 노트북: 셀러론 N3450 · 메모리 4GB · 저장 58GB (충분해요. 리눅스와 중계를 합쳐 메모리 0.5GB, 저장 5GB쯤)

## 0. 지우기 전에 (아직 Windows 일 때)

- **파일 옮기기**: 리눅스를 깔면 노트북 안의 Windows 와 파일이 **모두 지워져요**. 필요한 파일은 먼저 옮겨 두세요.
- **배터리 확인**
  1. 화면 오른쪽 아래 작업 표시줄에 배터리 모양이 있으면 배터리가 달린 거예요. 설정 → 시스템 → 전원 및 배터리에서 몇 %인지도 보여요.
  2. 전원 선을 뽑아 보세요. 꺼지지 않고 계속 켜져 있으면 배터리가 일하고 있는 거예요. 바로 꺼지면 배터리가 없거나 다 닳은 거예요(그래도 서버로는 쓸 수 있어요).
  3. 배터리 상태(얼마나 닳았는지): 시작 → `cmd` → `powercfg /batteryreport` 를 치면 `battery-report.html` 이 만들어져요. 그 안의 **DESIGN CAPACITY**(새것일 때)와 **FULL CHARGE CAPACITY**(지금)를 비교해서, 지금이 절반도 안 되면 많이 닳은 거예요.
  4. **부풀었는지 꼭 보세요**: 바닥이 불룩하거나, 터치패드·키보드가 들떠 있거나, 책상에 놓았을 때 흔들거리면 배터리가 부푼 거예요. 그러면 24시간 켜 두지 말고 배터리를 빼거나 수리점에 맡겨 주세요(불이 날 수 있어요).
  5. 서버로 쓸 때는 전원 선을 늘 꽂아 두고, 통풍이 되는 곳(이불·소파 위 말고)에 두세요.

## 1. 설치 USB 만들기 (이 PC 에서)

1. https://ubuntu.com/download/server 에서 **Ubuntu Server 24.04 LTS** 를 받아요 (약 3GB, `.iso` 파일).
2. https://rufus.ie 에서 Rufus 를 받아 실행해요.
3. 8GB 이상 USB 를 꽂고, Rufus 에서 그 USB 와 받은 `.iso` 를 고른 뒤 **시작**. (USB 안의 내용은 지워져요)

## 2. 노트북에 설치

1. USB 를 노트북에 꽂고 켜면서 **F2·F7·F12·Del·Esc** 중 하나를 계속 눌러 부팅 메뉴를 열어요 (노트북마다 달라요). USB 를 골라요.
   - USB 가 안 보이면 바이오스(설정)에서 **Secure Boot** 를 꺼 보세요.
2. 글자 메뉴가 나와요. **방향키로 고르고 Enter** 로 넘어가요.
   - 언어: English (한국어는 서버 화면에서 글자가 깨져요. 설치 뒤 관리는 이 PC 에서 하니 괜찮아요)
   - 키보드: Korean (또는 그대로)
   - 설치 종류: **Ubuntu Server** (minimized 아님)
   - 인터넷: 랜선이 있으면 저절로 잡혀요. 와이파이면 목록에서 집 와이파이를 고르고 비밀번호
   - 디스크: **Use an entire disk** (전체 사용) → Done → **Continue** (지운다고 한 번 더 물어요)
   - 이름·컴퓨터 이름·아이디·비밀번호: 아이디는 영어 소문자(예: `tabolkka`), 비밀번호는 꼭 기억해 두세요
   - **Install OpenSSH server 에 꼭 체크** (스페이스바) ← 이게 있어야 이 PC 에서 다룰 수 있어요
   - 추가 프로그램(snaps) 목록은 아무것도 고르지 않고 Done
3. 설치가 끝나면 **Reboot Now** → USB 를 빼고 Enter.
4. 검은 화면에 `login:` 이 나오면 아이디·비밀번호로 로그인하고 `ip -4 addr` 을 쳐요. `inet 192.168.x.x` 처럼 나오는 주소(127.0.0.1 말고)를 적어 두세요.

## 3. 중계 설치 (이 PC 에서 한 줄)

TagoHub 폴더의 PowerShell 에서 (주소·아이디는 위에서 적은 것으로):

```powershell
powershell -ExecutionPolicy Bypass -File server\relay\linux\push-to-laptop.ps1 -Addr 192.168.0.23 -User tabolkka
```

- 처음 접속하면 노트북을 믿을지 물어보지 않고 넘어가요. 비밀번호를 세 번 물어요 (파일 보내기 → 접속 → sudo, 모두 노트북 비밀번호).
- 노트북에 Node.js·cloudflared 를 깔고, 중계를 서비스로 등록하고, 덮개를 닫아도 잠들지 않게 하고, 20초 뒤 **✅ 중계가 켜지고 타볼까에 연결됐어요** 가 나오면 끝이에요.
- 마지막에 "이 PC 의 중계를 끌까요?" 에 `y` 를 누르면 이 PC 는 더 이상 중계하지 않아요.
- 노트북은 한 번 다시 켜 주세요: `ssh 아이디@주소` 로 접속해서 `sudo reboot` (덮개 설정이 확실히 적용돼요).

이 다음부터는 덮개를 닫고 공유기 근처에 두면 돼요.

## 평소 관리 (필요할 때만, 이 PC 에서)

```powershell
ssh tabolkka@192.168.0.23
```

| 하고 싶은 것 | 명령 |
|---|---|
| 중계가 돌고 있는지 | `systemctl status tagohub-relay` |
| 최근 기록 보기 | `journalctl -u tagohub-relay -n 30` |
| 중계 다시 켜기 | `sudo systemctl restart tagohub-relay` |
| 배터리 %·상태 | `cat /sys/class/power_supply/BAT*/capacity` · `cat /sys/class/power_supply/BAT*/status` |
| 노트북 다시 켜기 / 끄기 | `sudo reboot` / `sudo poweroff` |
| 접속 끝내기 | `exit` |

- 중계 프로그램(relay.mjs)을 고친 뒤에는 3번 명령을 다시 실행하면 새 것으로 바뀌어요 (열쇠는 그대로).
- 보안 업데이트는 Ubuntu 가 알아서 받아요(unattended-upgrades). 가끔 `sudo apt update && sudo apt upgrade -y` 를 해 주면 더 좋아요.
- 타볼까 쪽에서 중계가 붙어 있는지는 https://tabolkka.pages.dev/w/relay/status 에서 `"on":true` 로 볼 수 있어요.
