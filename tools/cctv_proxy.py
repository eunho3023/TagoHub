#!/usr/bin/env python3
"""
CCTV 영상(HLS) 중계 서버 — 집 PC 에서 돌리고 Cloudflare Tunnel 등으로 https 로 연다.

앱 → https://내도메인/p?u=<원본 영상 주소>&k=<키>  →  이 서버가 원본(http://IP:포트/…)을 받아 그대로 전달.
.m3u8 목록 안의 조각(.ts)·하위 목록 주소는 다시 이 서버를 거치도록 고쳐 쓴다.

실행:  python tools/cctv_proxy.py            (키는 환경변수 CCTV_PROXY_KEY 또는 --key)
설정:  local.properties 에  CCTV_PROXY_URL=https://내도메인   CCTV_PROXY_KEY=같은키

안전장치: 키가 맞아야 하고, 집 안쪽 주소(사설 IP·localhost)로는 연결하지 않으며, 같은 주소에서 오는 요청 수를 제한한다.
"""
import argparse
import ipaddress
import os
import re
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
PLAYLIST_TYPES = ("mpegurl", "x-mpegurl")
URI_ATTR = re.compile(r'URI="([^"]+)"')
MAX_PLAYLIST_BYTES = 2_000_000
# 시험용: 1 이면 사설·localhost 주소도 허용 (실제 운영에서는 켜지 말 것)
ALLOW_PRIVATE = os.environ.get("CCTV_PROXY_ALLOW_PRIVATE") == "1"
UPSTREAM_TIMEOUT = 15


class RateLimiter:
    """같은 사용자(IP)당 1초에 [per_sec] 번까지 (HLS 는 조각을 자주 받아서 넉넉하게)"""

    def __init__(self, per_sec: int):
        self.per_sec = per_sec
        self.lock = threading.Lock()
        self.hits: dict[str, list[float]] = {}

    def allow(self, who: str) -> bool:
        now = time.monotonic()
        with self.lock:
            recent = [t for t in self.hits.get(who, []) if now - t < 1.0]
            if len(recent) >= self.per_sec:
                self.hits[who] = recent
                return False
            recent.append(now)
            self.hits[who] = recent
            if len(self.hits) > 5000:
                self.hits = {k: v for k, v in self.hits.items() if v and now - v[-1] < 1.0}
            return True


def is_public_host(host: str) -> bool:
    """사설·loopback·링크로컬 주소로 가는 요청(SSRF)을 막는다. 이름은 풀어서 확인"""
    if ALLOW_PRIVATE:
        return True
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror:
        return False
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_reserved or ip.is_unspecified:
            return False
    return bool(infos)


def proxy_url(target: str, key: str) -> str:
    return "/p?" + urllib.parse.urlencode({"u": target, "k": key})


def rewrite_playlist(text: str, base: str, key: str) -> str:
    out = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            out.append(line)
        elif stripped.startswith("#"):
            # #EXT-X-KEY / #EXT-X-MAP / #EXT-X-MEDIA 등의 URI="..." 도 중계 주소로
            out.append(URI_ATTR.sub(lambda m: 'URI="%s"' % proxy_url(urllib.parse.urljoin(base, m.group(1)), key), line))
        else:
            out.append(proxy_url(urllib.parse.urljoin(base, stripped), key))
    return "\n".join(out) + "\n"


def make_handler(key: str, limiter: RateLimiter):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "cctv-proxy"

        def log_message(self, fmt, *args):  # 조용히 (주소에 키가 들어 있어서 로그에 남기지 않음)
            pass

        def _send(self, code: int, body: bytes = b"", ctype: str = "text/plain; charset=utf-8"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path == "/health":
                return self._send(200, b"ok")
            if parsed.path != "/p":
                return self._send(404, b"not found")
            q = urllib.parse.parse_qs(parsed.query)
            if q.get("k", [""])[0] != key:
                return self._send(403, b"forbidden")
            who = self.headers.get("CF-Connecting-IP") or self.client_address[0]
            if not limiter.allow(who):
                return self._send(429, b"too many requests")
            target = q.get("u", [""])[0]
            t = urllib.parse.urlparse(target)
            if t.scheme not in ("http", "https") or not t.hostname:
                return self._send(400, b"bad url")
            if not is_public_host(t.hostname):
                return self._send(403, b"blocked host")
            req = urllib.request.Request(target, headers={"User-Agent": UA, "Accept": "*/*"})
            if self.headers.get("Range"):
                req.add_header("Range", self.headers["Range"])
            try:
                up = urllib.request.urlopen(req, timeout=UPSTREAM_TIMEOUT)
            except urllib.error.HTTPError as e:
                return self._send(e.code, b"upstream error")
            except Exception:
                return self._send(502, b"upstream unreachable")
            with up:
                ctype = up.headers.get("Content-Type", "application/octet-stream")
                is_list = target.split("?")[0].lower().endswith(".m3u8") or any(s in ctype.lower() for s in PLAYLIST_TYPES)
                if is_list:
                    data = up.read(MAX_PLAYLIST_BYTES)
                    text = data.decode("utf-8", errors="replace")
                    if text.lstrip().startswith("#EXTM3U"):
                        body = rewrite_playlist(text, target, key).encode("utf-8")
                        return self._send(200, body, "application/vnd.apple.mpegurl")
                    return self._send(200, data, ctype)
                # 조각·일반 파일: 받는 대로 흘려보냄 (길이를 모르면 연결을 닫아 끝을 알림)
                length = up.headers.get("Content-Length")
                self.send_response(up.status if up.status in (200, 206) else 200)
                self.send_header("Content-Type", ctype)
                self.send_header("Access-Control-Allow-Origin", "*")
                if length:
                    self.send_header("Content-Length", length)
                else:
                    self.send_header("Connection", "close")
                    self.close_connection = True
                if up.headers.get("Content-Range"):
                    self.send_header("Content-Range", up.headers["Content-Range"])
                self.end_headers()
                if self.command == "HEAD":
                    return
                try:
                    while True:
                        chunk = up.read(64 * 1024)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    pass

    return Handler


def main():
    ap = argparse.ArgumentParser(description="CCTV HLS 중계 서버")
    ap.add_argument("--host", default="127.0.0.1", help="기본은 이 PC 안에서만 (Cloudflare Tunnel 이 여기로 연결)")
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--key", default=os.environ.get("CCTV_PROXY_KEY", ""))
    ap.add_argument("--rate", type=int, default=20, help="사용자(IP)당 초당 최대 요청 수")
    args = ap.parse_args()
    if len(args.key) < 12:
        sys.exit("키가 없거나 너무 짧습니다 (12자 이상). 환경변수 CCTV_PROXY_KEY 또는 --key 로 넣으세요.")
    server = ThreadingHTTPServer((args.host, args.port), make_handler(args.key, RateLimiter(args.rate)))
    server.daemon_threads = True
    print(f"CCTV 중계 서버 시작: http://{args.host}:{args.port}  (상태 확인: /health)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
