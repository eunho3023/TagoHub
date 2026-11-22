#!/usr/bin/env bash
# 타볼까 국내 중계를 Ubuntu Server 노트북에 설치 (sudo 로 실행). 여러 번 실행해도 됨 (고친 relay.mjs 를 다시 올릴 때도)
#   sudo bash install.sh
# 같은 폴더에 relay.mjs, tagohub-relay.service, relay.env(RELAY_KEY=…, push-to-laptop.ps1 이 만들어 보냄)가 있어야 함
set -euo pipefail
cd "$(dirname "$0")"
if [ "$(id -u)" -ne 0 ]; then echo "sudo bash install.sh 로 실행해 주세요"; exit 1; fi
for f in relay.mjs tagohub-relay.service; do [ -f "$f" ] || { echo "$f 가 없어요"; exit 1; }; done

echo "1/6 프로그램 설치 (Node.js, cloudflared)"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates nodejs >/dev/null
if ! command -v cloudflared >/dev/null; then
  # Cloudflare 공식 저장소 (https://pkg.cloudflare.com)
  mkdir -p --mode=0755 /usr/share/keyrings
  curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg -o /usr/share/keyrings/cloudflare-main.gpg
  echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main" > /etc/apt/sources.list.d/cloudflared.list
  apt-get update -qq
  apt-get install -y -qq cloudflared >/dev/null
fi
echo "   node $(node --version), $(cloudflared --version 2>/dev/null | head -1)"

echo "2/6 중계 프로그램 복사 (/opt/tagohub-relay)"
id tagohub >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin tagohub
install -d -m 0755 /opt/tagohub-relay
install -m 0644 relay.mjs /opt/tagohub-relay/relay.mjs

echo "3/6 열쇠 넣기 (/etc/tagohub-relay.env, root 만 읽음)"
if [ -f relay.env ]; then
  install -m 0600 -o root -g root relay.env /etc/tagohub-relay.env
  rm -f relay.env   # 홈 폴더에는 남기지 않음
elif [ ! -f /etc/tagohub-relay.env ]; then
  read -r -s -p "   RELAY_KEY 를 붙여 넣고 Enter (화면에 안 보여요): " k; echo
  umask 077; printf 'RELAY_KEY=%s\n' "$k" > /etc/tagohub-relay.env
fi

echo "4/6 덮개를 닫아도·전원 버튼 말고는 잠들지 않게"
install -d /etc/systemd/logind.conf.d
cat > /etc/systemd/logind.conf.d/tagohub-lid.conf <<'EOF'
[Login]
HandleLidSwitch=ignore
HandleLidSwitchExternalPower=ignore
HandleLidSwitchDocked=ignore
EOF
systemctl mask -q sleep.target suspend.target hibernate.target hybrid-sleep.target
# 화면은 1분 뒤 꺼지게 (덮개를 닫아도 화면 불이 켜져 있지 않게)
if [ -f /etc/default/grub ] && ! grep -q 'consoleblank=60' /etc/default/grub; then
  sed -i 's/^GRUB_CMDLINE_LINUX_DEFAULT="\(.*\)"/GRUB_CMDLINE_LINUX_DEFAULT="\1 consoleblank=60"/' /etc/default/grub
  update-grub >/dev/null 2>&1 || true
fi

echo "5/6 서비스 등록 (부팅하면 켜지고, 멈추면 5초 뒤 다시)"
install -m 0644 tagohub-relay.service /etc/systemd/system/tagohub-relay.service
systemctl daemon-reload
systemctl enable -q tagohub-relay
systemctl restart tagohub-relay

echo "6/6 확인 (20초 기다림)"
sleep 20
if journalctl -u tagohub-relay --since "-1 min" --no-pager | grep -q "타볼까에 알림: 200"; then
  echo "   ✅ 중계가 켜지고 타볼까에 연결됐어요"
else
  echo "   ⚠️ 아직 연결 소식이 없어요. 아래 기록을 봐 주세요"
fi
journalctl -u tagohub-relay -n 8 --no-pager -o cat
echo
echo "덮개 닫기 설정은 노트북을 한 번 다시 켜면(sudo reboot) 확실히 적용돼요"
