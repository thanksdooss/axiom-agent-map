// 검토 화면: 추정한 관계(CANDIDATE)를 사람이 확정·반려하고, 미등록 대상을 처리합니다.
import { useState } from 'react';
import type { GraphEdge, PendingEntity } from '../api/types';
import { ErrorView, EvidenceView, Loading, StatusBadge } from '../components/ui';
import { useApp, useLoad } from '../context';

function CandidateCard({ edge, names, onDone }: { edge: GraphEdge; names: Map<string, string>; onDone: () => void }) {
  const { api, showOnMap } = useApp();
  const [target, setTarget] = useState(edge.target);
  const [busy, setBusy] = useState(false);
  const act = async (action: 'confirm' | 'reject') => {
    setBusy(true);
    try {
      await api.review(edge.id, action, target !== edge.target ? target : undefined);
      onDone();
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="card">
      <div className="row">
        <span className="badge neutral">{edge.typeLabel}</span>
        <strong>{edge.sourceName}</strong>
        <span className="faint">→</span>
        {edge.candidatesShown?.length ? (
          <select className="input" value={target} onChange={(e) => setTarget(e.target.value)}>
            {edge.candidatesShown.map((id) => <option key={id} value={id}>{names.get(id) ?? id}</option>)}
          </select>
        ) : (
          <strong>{edge.targetName}</strong>
        )}
        <span style={{ flex: 1 }} />
        <StatusBadge status={edge.status} />
      </div>
      <div className="small" style={{ color: 'var(--candidate)', margin: '8px 0 4px' }}>{edge.reviewReason}</div>
      {edge.evidence.map((ev, i) => <EvidenceView key={i} ev={ev} />)}
      <div className="row" style={{ marginTop: 8 }}>
        <button className="btn small primary" disabled={busy} onClick={() => act('confirm')}>확정</button>
        <button className="btn small danger" disabled={busy} onClick={() => act('reject')}>반려</button>
        <button className="btn small" onClick={() => showOnMap({ title: `${edge.sourceName} → ${edge.targetName}`, nodeIds: [edge.source, edge.target], edgeIds: [edge.id], focusId: edge.source })}>지도에서 보기</button>
      </div>
    </div>
  );
}

function PendingCard({ p, options, onDone }: { p: PendingEntity; options: { id: string; name: string }[]; onDone: () => void }) {
  const { api } = useApp();
  const [mapTo, setMapTo] = useState('');
  const act = async (action: 'register' | 'map' | 'dismiss') => {
    await api.resolvePending(p.id, action, action === 'map' ? mapTo : undefined);
    onDone();
  };
  return (
    <div className="card">
      <div className="row">
        <strong>{p.rawLabel}</strong>
        <span className="badge neutral">{p.kind}</span>
        <span className="muted small">{p.mentionedByName}에서 언급</span>
        <span style={{ flex: 1 }} />
        <span className={`badge ${p.state === 'OPEN' ? 'CANDIDATE' : 'neutral'}`}>{p.state === 'OPEN' ? '처리 대기' : p.state}</span>
      </div>
      <div className="small muted" style={{ margin: '6px 0' }}>{p.reason}</div>
      <EvidenceView ev={p.evidence} />
      {p.state === 'OPEN' && (
        <div className="row" style={{ marginTop: 8 }}>
          <select className="input" value={mapTo} onChange={(e) => setMapTo(e.target.value)}>
            <option value="">기존 대상에 연결…</option>
            {options.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
          </select>
          <button className="btn small" disabled={!mapTo} onClick={() => act('map')}>연결</button>
          <button className="btn small" onClick={() => act('register')}>신규 등록 요청</button>
          <button className="btn small danger" onClick={() => act('dismiss')}>무시</button>
        </div>
      )}
    </div>
  );
}

export function ReviewPage() {
  const { refresh } = useApp();
  const { data, error } = useLoad(
    async (api) => {
      const [cands, pending, graph] = await Promise.all([api.relationships({ status: 'CANDIDATE' }), api.pendingEntities(), api.graph('all')]);
      return { cands, pending, graph };
    },
    [],
  );
  if (error) return <ErrorView message={error} />;
  if (!data) return <Loading />;
  const names = new Map(data.graph.nodes.map((n) => [n.id, n.label]));
  const options = data.graph.nodes.filter((n) => n.type !== 'Department' && n.type !== 'WorkType').map((n) => ({ id: n.id, name: `${n.label} (${n.id})` }));
  return (
    <div className="page">
      <h1>검토</h1>
      <p className="lead">AI·별칭 사전이 추정만 한 관계는 사람이 근거를 보고 확정해야 그래프에 '확정'으로 올라갑니다.</p>
      <h2 style={{ fontSize: 15 }}>검토 대기 관계 {data.cands.length}건</h2>
      <div className="list">
        {data.cands.map((e) => <CandidateCard key={e.id} edge={e} names={names} onDone={refresh} />)}
        {!data.cands.length && <div className="empty">검토 대기 관계가 없습니다.</div>}
      </div>
      <h2 style={{ fontSize: 15, marginTop: 28 }}>미등록 대상 {data.pending.filter((p) => p.state === 'OPEN').length}건</h2>
      <div className="list">
        {data.pending.map((p) => <PendingCard key={p.id} p={p} options={options} onDone={refresh} />)}
        {!data.pending.length && <div className="empty">미등록 대상이 없습니다.</div>}
      </div>
    </div>
  );
}
