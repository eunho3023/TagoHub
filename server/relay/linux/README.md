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

1. https://ubuntu.com/download/server 에서 **Ubuntu Server LTS** 를 받아요 (약 3GB, `.iso` 파일). 24.04·26.04 모두 돼요 (2026-11-23 실제 노트북은 26.04.1).
2. https://rufus.ie 에서 Rufus 를 받아 실행해요.
3. 8GB 이상 USB 를 꽂고, Rufus 에서 그 USB 와 받은 `.iso` 를 고른 뒤 **시작**. (USB 안의 내용은 지워져요)

## 2. 노트북에 설치

1. USB 를 노트북에 꽂고 켜면서 **F2·F7·F12·Del·Esc** 중 하나를 계속 눌러 부팅 메뉴를 열어요 (노트북마다 달라요). USB 를 골라요.
   - USB 가 안 보이면 바이오스(설정)에서 **Secure Boot** 를 꺼 보세요.
2. 글자 메뉴가 나와요. **방향키로 고르고 Enter** 로 넘어가요.
   - 언어: English (한국어는 서버 화면에서 글자가 깨져요. 설치 뒤 관리는 이 PC 에서 하니 괜찮아요)
   - 키보드: Korean (또는 그대로)
   - 설치 종류: **Ubuntu Server** (minimized 아님)
   - 설치 종류 화면의 **Search for third-party drivers** 는 비워 둬요
   - **인터넷 (Network configuration) — 여기서 꼭 인터넷을 잡아요.** `Continue without network` 로 넘기면 설치 뒤 손이 많이 가요.
     - 랜선이 있으면 저절로 잡혀요.
     - 와이파이(`wl...` 줄 → Edit Wifi)는 설치 화면에서 잘 안 붙을 때가 많아요(목록 찾기 `scan failed -100`, 5GHz 에서 계속 도는 등).
     - 그럴 땐 **휴대폰 USB 테더링**이 제일 확실해요: 휴대폰을 USB 로 꽂고 테더링을 켜면 `usb0`(또는 `enx...`) 줄이 생겨요 → 그 줄 Enter → **Edit IPv4 → Automatic (DHCP) → Save**. 붙지 않는 와이파이 줄은 **Edit IPv4 → Disabled** 로 꺼 둬요. 주소가 바로 안 보여도 넘어가면 설치 뒤엔 붙는 경우가 많아요.
     - 집 와이파이는 설치가 끝난 뒤 넣어요 (아래 2-1).
   - 프록시(Proxy)는 비워 두고 Done
   - 디스크: **Use an entire disk** (전체 사용), LVM 그대로, **Encrypt(LUKS) 는 비워 둬요**(켜면 켤 때마다 암호를 쳐야 해서 혼자 다시 켜지는 서버가 안 돼요) → Done → **Continue** (지운다고 한 번 더 물어요)
   - 이름·컴퓨터 이름·아이디·비밀번호: **아이디·비밀번호 칸은 직접 채워야** 넘어가요(Enter 만 누르면 `Username missing`). 아이디는 영어 소문자(예: `tabolkka`), 비밀번호는 꼭 기억해 두세요
   - Ubuntu Pro: `Skip for now`
   - **Install OpenSSH server 에 꼭 체크** (스페이스바로 [X]. 기본은 비어 있어서 Enter 만 누르면 빠져요) ← 이게 있어야 이 PC 에서 다룰 수 있어요. `Allow password authentication over SSH` 도 [X] 그대로
   - 추가 프로그램(snaps) 목록은 아무것도 고르지 않고 Done
3. 설치가 끝나면 **Reboot Now** → USB 를 빼고 Enter.
4. 처음 켜면 SSH 열쇠 그림 같은 글자가 쏟아져 로그인 줄이 가려질 수 있어요. **Enter** 를 한 번 누르면 `login:` 이 다시 나와요. 아이디·비밀번호로 로그인해요 (비밀번호는 화면에 안 보여요).
5. 집 와이파이를 넣고(아래 2-1) `ip -4 addr show wlp1s0` 를 쳐요. `inet 192.168.x.x` 처럼 나오는 주소를 적어 두세요. (테더링 주소 `10.x.x.x` 는 휴대폰 안쪽이라 이 PC 에서 접속할 수 없어요)

### 2-1. 설치 뒤 집 와이파이 넣기 (노트북에서)

와이파이 장치 이름은 `networkctl` 로 봐요(보통 `wlp1s0`). 와이파이 이름은 **휴대폰에 보이는 전체 이름을 대소문자까지 똑같이** 써요.

```
sudo netplan set 'wifis.wlp1s0.access-points.와이파이이름.password=와이파이비번'
sudo netplan set wifis.wlp1s0.dhcp4=true
sudo netplan apply
networkctl
```

- 30초 뒤 `wlp1s0` 가 초록색 `routable` / `configured` 면 성공. 그 다음 테더링 선은 빼도 돼요.
- 틀리게 넣었으면 `sudo netplan set 'wifis.wlp1s0=null'` 로 통째로 지우고 다시 넣어요.
- 자주 하는 실수: `access-points` 철자(s 두 번), `sudo`→`sudu`, `wlp1s0` 의 1·0.
- **와이파이 이름에 점(.)이 있으면**(예: `..._2.4G`) `netplan set` 이 점을 칸 구분으로 읽어 안 돼요. 점 없는 5G 이름을 쓰거나, 설정 파일로 넣어요 (띄어쓰기는 스페이스로, Tab 말고):

```
sudo tee /etc/netplan/60-wifi.yaml <<'EOF'
network:
  version: 2
  wifis:
    wlp1s0:
      dhcp4: true
      access-points:
        "와이파이.이름":
          password: "와이파이비번"
EOF
sudo chmod 600 /etc/netplan/60-wifi.yaml
sudo netplan apply
```

## 3. 중계 설치 (이 PC 에서 한 줄)

TagoHub 폴더의 PowerShell 에서 (주소·아이디는 위에서 적은 것으로):

```powershell
powershell -ExecutionPolicy Bypass -File server\relay\linux\push-to-laptop.ps1 -Addr 192.168.0.23 -User tabolkka
```

- 맨 앞 `powershell -` 까지 통째로 붙여 넣어야 해요 (빠지면 `ExecutionPolicy ... Scope` 오류).
- 처음 접속하면 노트북을 믿을지 물어보지 않고 넘어가요. 비밀번호를 세 번 물어요 (파일 보내기 → 접속 → sudo, 모두 노트북 비밀번호).
- **비밀번호를 치기 전에 한/영 키로 영어 모드인지 확인**해요. 한글 모드로 치면 `Permission denied` 가 나요. 헷갈리면 먼저 `ssh 아이디@주소` 로 접속만 해 보고 `exit`.
- 설치 글자는 **이 PC 의 PowerShell 창에만** 나와요. 노트북 화면은 그대로예요.
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
- **예약 작업도 노트북이 불러요**: `local.properties` 에 `CRON_KEY` 가 있으면 3번 명령이 같이 보내고, 중계가 타볼까 예약 작업(`/w/cron`: 30분마다 자료 고장 확인, 그 밖엔 정류장 시간표용 버스 위치 모으기·노선 모양 만들기)을 1분마다 불러요. GitHub 예약(5분마다)은 실제로 몇 시간에 한 번꼴로만 돌아서 예비로만 남겨 뒀어요. 기록은 `journalctl -u tagohub-relay | grep 예약` (바뀔 때와 한 시간에 한 번만 남김).
- 보안 업데이트는 Ubuntu 가 알아서 받아요(unattended-upgrades). 가끔 `sudo apt update && sudo apt upgrade -y` 를 해 주면 더 좋아요.
- 타볼까 쪽에서 중계가 붙어 있는지는 https://tabolkka.pages.dev/w/relay/status 에서 `"on":true` 로 볼 수 있어요. `at` 은 마지막 안부 시각(5분마다, 터널 주소가 바뀌면 바로)이고, 15분 동안 안 오면 타볼까는 꺼진 것으로 보고 예전처럼 바로 물어요(느림).
- 중계를 다른 기계로 바꿔 끼울 때(예: 이 PC → 노트북), 꺼진 쪽이 마지막으로 알린 주소가 남아 **최대 5분** 실시간이 느릴 수 있어요. 새 쪽이 안부를 보내면 풀려요.
- 노트북 주소(192.168.x.x)는 공유기가 가끔 바꿔요. 중계는 상관없이 돌고, 이 PC 에서 접속할 때만 노트북에서 `ip -4 addr` 로 다시 보면 돼요.
