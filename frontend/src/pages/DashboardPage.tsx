// 경영진 대시보드: 부서·민감도·플랫폼 집중·핵심 Agent와 인사이트.
import { Bars, ErrorView, Loading, TypeDot } from '../components/ui';
import { useApp, useLoad } from '../context';

const INSIGHT_ICON: Record<string, string> = {
  HIGH_SENS_MULTI_PLATFORM: '🔐', PLATFORM_CONCENTRATION: '🧱', DUPLICATE_CANDIDATE: '👯', REVIEW_BACKLOG: '📝', CYCLE: '🔁',
};

export function DashboardPage() {
  const { scope, showOnMap, go } = useApp();
  const { data: d, error } = useLoad((api) => api.dashboard(scope), [scope]);
  if (error) return <ErrorView message={error} />;
  if (!d) return <Loading />;
  const statusText = Object.entries(d.agentsByStatus).map(([k, v]) => `${k} ${v}`).join(' · ');
  const candidate = d.relationshipsByStatus.CANDIDATE ?? 0;

  return (
    <div className="page">
      <h1>경영진 대시보드</h1>
      <p className="lead">{scope === 'confirmed' ? '확정된 관계만' : '샘플·검토 대기를 포함한 모든 관계'} 기준입니다. 막대나 카드를 누르면 지도에서 해당 노드를 보여줍니다.</p>

      <div className="grid cols-4">
        <div className="card kpi">
          <h2>등록된 Agent</h2>
          <div className="value">{d.totalAgents}</div>
          <div className="sub">{statusText}</div>
        </div>
        <div className="card kpi" style={{ cursor: 'pointer' }} onClick={() => showOnMap({ title: '민감도 상 데이터에 접근하는 Agent', nodeIds: d.highSensitivity.agents.map((a) => a.id), edgeIds: [] })}>
          <h2>고민감도 데이터 접근 Agent</h2>
          <div className="value" style={{ color: 'var(--sens-high)' }}>{d.highSensitivity.count}</div>
          <div className="sub">전체의 {(d.highSensitivity.share * 100).toFixed(1)}%</div>
        </div>
        <div className="card kpi" style={{ cursor: 'pointer' }} onClick={() => go('review')}>
          <h2>검토 대기 관계</h2>
          <div className="value" style={{ color: 'var(--candidate)' }}>{candidate}</div>
          <div className="sub">확정 {d.relationshipsByStatus.CONFIRMED ?? 0} · 샘플 {d.relationshipsByStatus.SAMPLE ?? 0}</div>
        </div>
        <div className="card kpi" style={{ cursor: 'pointer' }} onClick={() => go('review')}>
          <h2>미등록 대상</h2>
          <div className="value">{d.pendingEntities}</div>
          <div className="sub">표준 목록에 없어 확인이 필요한 표기</div>
        </div>
      </div>

      <div className="grid cols-2" style={{ marginTop: 16 }}>
        <div className="card">
          <h2>인사이트</h2>
          <div className="list">
            {d.insights.map((i, n) => (
              <div key={`${i.kind}-${n}`} className="rel" style={{ cursor: 'pointer' }} onClick={() => showOnMap({ title: i.title, nodeIds: i.nodeIds, edgeIds: [] })}>
                <div className="head"><span>{INSIGHT_ICON[i.kind] ?? '💡'}</span><strong>{i.title}</strong></div>
                <div className="small muted" style={{ marginTop: 4 }}>{i.description}</div>
              </div>
            ))}
            {!d.insights.length && <div className="empty">인사이트가 없습니다.</div>}
          </div>
        </div>
        <div className="card">
          <h2>여러 Agent가 의존하는 핵심 Agent</h2>
          <Bars
            color="var(--agent)"
            rows={d.topDepended.map((t) => ({
              label: t.name, value: t.dependents,
              onClick: () => showOnMap({ title: `${t.name}에 의존하는 Agent`, nodeIds: [t.id], edgeIds: [], focusId: t.id }),
            }))}
          />
          <p className="faint small" style={{ marginBottom: 0 }}>이 Agent를 바꾸면 점검해야 하는 Agent 수(직접 + 간접)입니다.</p>
        </div>
        <div className="card">
          <h2>부서별 Agent</h2>
          <Bars rows={d.agentsByDepartment.map((x) => ({ label: x.name, value: x.count, onClick: x.id ? () => showOnMap({ title: `${x.name} 소속 Agent`, nodeIds: [x.id!], edgeIds: [], focusId: x.id! }) : undefined }))} color="var(--department)" />
        </div>
        <div className="card">
          <h2>플랫폼 집중도 (연동 Agent 수)</h2>
          <Bars rows={d.platformConcentration.slice(0, 8).map((x) => ({ label: x.name, value: x.count, onClick: () => showOnMap({ title: `${x.name}에 연동된 Agent`, nodeIds: [x.id], edgeIds: [], focusId: x.id }) }))} color="var(--platform)" />
        </div>
        <div className="card">
          <h2>데이터 자산 민감도</h2>
          <div className="row" style={{ gap: 16 }}>
            {(['상', '중', '하'] as const).map((s) => (
              <div key={s} className="kpi">
                <div className="row"><TypeDot type="DataAsset" sensitivity={s} /><span className="muted small">민감도 {s}</span></div>
                <div className="value">{d.dataSensitivity[s]}</div>
              </div>
            ))}
          </div>
        </div>
        <div className="card">
          <h2>민감도 '상' 데이터에 접근하는 Agent</h2>
          <div className="chips">
            {d.highSensitivity.agents.map((a) => (
              <span key={a.id} className="chip" onClick={() => showOnMap({ title: a.name, nodeIds: [a.id], edgeIds: [], focusId: a.id })}>{a.name}</span>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
