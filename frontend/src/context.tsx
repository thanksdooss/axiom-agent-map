import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { HttpApi } from './api/httpApi';
import { LocalApi } from './api/localApi';
import type { Api, Health, Scope } from './api/types';

export interface Highlight {
  title: string;
  nodeIds: string[];
  edgeIds: string[];
  focusId?: string;
}

export type Route = 'graph' | 'dashboard' | 'review' | 'import' | 'ask';

interface AppState {
  api: Api;
  health: Health | null;
  scope: Scope;
  setScope: (s: Scope) => void;
  highlight: Highlight | null;
  setHighlight: (h: Highlight | null) => void;
  version: number;
  refresh: () => void;
  route: Route;
  go: (r: Route) => void;
  showOnMap: (h: Highlight) => void;
  apiBase: string;
  setApiBase: (b: string) => void;
}

const Ctx = createContext<AppState | null>(null);
const API_KEY = 'axiom-api-base';

function readBase(): string {
  try {
    return globalThis.localStorage?.getItem(API_KEY) ?? import.meta.env.VITE_API_BASE ?? '';
  } catch {
    return import.meta.env.VITE_API_BASE ?? '';
  }
}

const routeFromHash = (): Route => {
  const r = location.hash.replace(/^#\/?/, '').split('?')[0];
  return (['graph', 'dashboard', 'review', 'import', 'ask'] as Route[]).includes(r as Route) ? (r as Route) : 'graph';
};

export function AppProvider({ children }: { children: ReactNode }) {
  const [apiBase, setApiBaseState] = useState(readBase);
  const api = useMemo<Api>(() => (apiBase ? new HttpApi(apiBase) : new LocalApi()), [apiBase]);
  const [health, setHealth] = useState<Health | null>(null);
  const [scope, setScope] = useState<Scope>('all');
  const [highlight, setHighlight] = useState<Highlight | null>(null);
  const [version, setVersion] = useState(0);
  const [route, setRoute] = useState<Route>(routeFromHash);

  useEffect(() => {
    const on = () => setRoute(routeFromHash());
    addEventListener('hashchange', on);
    return () => removeEventListener('hashchange', on);
  }, []);
  useEffect(() => {
    setHealth(null);
    api.health().then(setHealth).catch(() => setHealth({ status: 'error', llm: { enabled: false, provider: null, model: null }, mode: 'server' }));
  }, [api]);

  const go = useCallback((r: Route) => {
    location.hash = `/${r}`;
  }, []);
  const showOnMap = useCallback((h: Highlight) => {
    setHighlight(h);
    location.hash = '/graph';
  }, []);
  const setApiBase = useCallback((b: string) => {
    try {
      if (b) globalThis.localStorage?.setItem(API_KEY, b);
      else globalThis.localStorage?.removeItem(API_KEY);
    } catch {
      /* 저장 못 해도 이번 세션에서는 적용 */
    }
    setApiBaseState(b);
    setVersion((v) => v + 1);
  }, []);
  const refresh = useCallback(() => setVersion((v) => v + 1), []);

  const value: AppState = {
    api, health, scope, setScope, highlight, setHighlight, version, refresh, route, go, showOnMap, apiBase, setApiBase,
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useApp(): AppState {
  const v = useContext(Ctx);
  if (!v) throw new Error('AppProvider 밖에서 useApp을 썼습니다');
  return v;
}

/** api 호출을 감싸 로딩·오류 상태를 돌려줍니다. deps가 바뀌거나 refresh()가 불리면 다시 부릅니다. */
export function useLoad<T>(fn: (api: Api) => Promise<T>, deps: unknown[]): { data: T | null; error: string | null; loading: boolean } {
  const { api, version } = useApp();
  const [state, setState] = useState<{ data: T | null; error: string | null; loading: boolean }>({ data: null, error: null, loading: true });
  useEffect(() => {
    let alive = true;
    setState((s) => ({ ...s, loading: true }));
    fn(api)
      .then((data) => alive && setState({ data, error: null, loading: false }))
      .catch((e: unknown) => alive && setState({ data: null, error: e instanceof Error ? e.message : String(e), loading: false }));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, version, ...deps]);
  return state;
}
