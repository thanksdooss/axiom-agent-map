import { useState, type ReactElement } from 'react';
import { AppProvider, useApp, type Route } from './context';
import { AskPage } from './pages/AskPage';
import { DashboardPage } from './pages/DashboardPage';
import { GraphPage } from './pages/GraphPage';
import { ImportPage } from './pages/ImportPage';
import { ReviewPage } from './pages/ReviewPage';

const ICONS: Record<Route, ReactElement> = {
  graph: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><circle cx="6" cy="7" r="2.5" /><circle cx="18" cy="6" r="2" /><circle cx="12" cy="17" r="3" /><path d="M8 8l8-1.5M7.5 9l3.5 6M16.8 7.8l-3.6 7" /></svg>,
  dashboard: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="M4 20V10M10 20V4M16 20v-7M22 20H2" /></svg>,
  review: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="M9 11l3 3 8-8" /><path d="M20 12v7a2 2 0 01-2 2H6a2 2 0 01-2-2V5a2 2 0 012-2h9" /></svg>,
  import: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="M12 3v12M7 10l5 5 5-5M4 21h16" /></svg>,
  ask: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="M21 12a8 8 0 01-11.6 7.1L4 20l1-4.6A8 8 0 1121 12z" /></svg>,
};
const NAV: [Route, string][] = [['graph', '지도'], ['dashboard', '대시보드'], ['review', '검토'], ['import', '등록'], ['ask', '질문']];

function Settings({ onClose }: { onClose: () => void }) {
  const { apiBase, setApiBase, health } = useApp();
  const [value, setValue] = useState(apiBase);
  return (
    <div className="overlay" style={{ position: 'fixed', top: 56, right: 16, width: 340, padding: 16, zIndex: 20 }}>
      <strong>데이터 소스</strong>
      <p className="small muted">비워 두면 브라우저 안의 엔진(가짜 데이터)으로 동작합니다. Spring Boot 백엔드를 띄웠다면 주소를 넣으세요.</p>
      <input className="input" style={{ width: '100%' }} placeholder="http://localhost:8080" value={value} onChange={(e) => setValue(e.target.value)} />
      <div className="row" style={{ marginTop: 10 }}>
        <button className="btn small primary" onClick={() => { setApiBase(value.trim()); onClose(); }}>적용</button>
        <button className="btn small" onClick={() => { setApiBase(''); onClose(); }}>브라우저 엔진 사용</button>
        <span style={{ flex: 1 }} />
        <button className="btn small" onClick={onClose}>닫기</button>
      </div>
      {health && (
        <p className="small faint" style={{ marginBottom: 0 }}>
          현재: {health.mode === 'server' ? `서버 (${health.status})` : '브라우저 엔진'} · LLM {health.llm.enabled ? `${health.llm.provider} / ${health.llm.model}` : '사용 안 함'}
        </p>
      )}
    </div>
  );
}

function Shell() {
  const { route, scope, setScope, health } = useApp();
  const [settings, setSettings] = useState(false);
  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <svg width="22" height="22" viewBox="0 0 32 32"><circle cx="9" cy="10" r="4" fill="#a78bfa" /><circle cx="23" cy="9" r="3" fill="#60a5fa" /><circle cx="17" cy="23" r="5" fill="#34d399" /><path d="M9 10L23 9M9 10L17 23M23 9L17 23" stroke="#cbd5e1" strokeWidth="1.5" /></svg>
          엑시옴 <small>온톨로지 기반 Agent MAP</small>
        </div>
        <span className="spacer" />
        <div className="segmented" title="그래프·집계에 넣을 관계 상태">
          <button className={scope === 'all' ? 'on' : ''} onClick={() => setScope('all')}>모든 관계</button>
          <button className={scope === 'confirmed' ? 'on' : ''} onClick={() => setScope('confirmed')}>확정만</button>
        </div>
        <button className="btn small" onClick={() => setSettings((s) => !s)}>
          <span className="dot" style={{ background: health?.status === 'error' ? 'var(--sens-high)' : health?.mode === 'server' ? 'var(--sens-low)' : 'var(--platform)' }} />
          {health?.mode === 'server' ? '서버 연결' : '브라우저 데모'}
        </button>
      </header>
      <nav className="nav">
        {NAV.map(([r, label]) => (
          <a key={r} href={`#/${r}`} className={route === r ? 'active' : ''}>{ICONS[r]}{label}</a>
        ))}
      </nav>
      <main className="main">
        {route === 'graph' && <GraphPage />}
        {route === 'dashboard' && <DashboardPage />}
        {route === 'review' && <ReviewPage />}
        {route === 'import' && <ImportPage />}
        {route === 'ask' && <AskPage />}
      </main>
      {settings && <Settings onClose={() => setSettings(false)} />}
    </div>
  );
}

export default function App() {
  return (
    <AppProvider>
      <Shell />
    </AppProvider>
  );
}
