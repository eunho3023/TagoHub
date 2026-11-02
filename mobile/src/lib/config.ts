// 서버 설정(/api/config)을 한 번 받아 두고 여러 화면이 같이 씀. 서버 주소를 바꾸면 다시 받음.
import { useEffect, useState } from 'react';

import { Api, ServerConfig } from './api';

let current: ServerConfig | null = null;
let inflight: Promise<ServerConfig | null> | null = null;
const listeners = new Set<(c: ServerConfig | null) => void>();

export function reloadServerConfig(): Promise<ServerConfig | null> {
  inflight = Api.config()
    .then((c) => c)
    .catch(() => null)
    .then((c) => {
      current = c;
      listeners.forEach((l) => l(c));
      return c;
    });
  return inflight;
}

export function useServerConfig(): ServerConfig | null {
  const [c, setC] = useState(current);
  useEffect(() => {
    listeners.add(setC);
    if (!inflight) void reloadServerConfig();
    return () => { listeners.delete(setC); };
  }, []);
  return c;
}
