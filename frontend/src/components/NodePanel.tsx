// 지도에서 고른 노드의 상세 패널: 속성, 검토 필요 표시, 의존·영향 탐색, 관계와 근거.
import { useEffect, useState } from 'react';
import type { AgentDetail, GraphEdge, GraphResponse, NodeType, TraversalResponse } from '../api/types';
import { useApp, type Highlight } from '../context';
import { RelationItem, StatusBadge, TYPE_LABEL, TypeDot } from './ui';

const typeOf = (id: string): NodeType =>
  id.startsWith('DEP') ? 'Department' : id.startsWith('WT') ? 'WorkType' : id.startsWith('A') ? 'Agent' : id.startsWith('D') ? 'DataAsset' : 'Platform';

const FIELD_LABEL: Record<string, string> = {
  department: '소속', platforms: '플랫폼', data: '데이터', calls: '결과사용', owners: '담당자', start_date: '시작일',
  monthly_calls: '월 사용량', work_type: '업무 유형',
};

export function NodePanel({
  id, graph, onOpen, onTrace, onClose, onLocal,
}: {
  id: string;
  graph: GraphResponse;
  onOpen: (id: string) => void;
  onTrace: (h: Highlight | null) => void;
  onClose: () => void;
  onLocal: () => void;
}) {
  const { api, scope, version } = useApp();
  const node = graph.nodes.find((n) => n.id === id);
  const type = node?.type ?? typeOf(id);
  const [detail, setDetail] = useState<AgentDetail | null>(null);
  const [trav, setTrav] = useState<TraversalResponse | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    setTrav(null);
    setErr(null);
    setDetail(null);
    if (type === 'Agent') api.agent(id, scope).then(setDetail).catch((e) => setErr(String(e.message ?? e)));
  }, [api, id, scope, type, version]);

  const run = async (kind: 'dependencies' | 'impact') => {
    try {
      const t = kind === 'impact' ? await api.impact(id, null, scope) : await api.dependencies(id, null, scope);
      setTrav(t);
      onTrace({
        title: `${t.anchor.name} — ${kind === 'impact' ? '바꾸면 점검할 대상' : '쓰는 대상'} ${t.count}개`,
        nodeIds: [id, ...t.direct.map((x) => x.id), ...t.indirect.map((x) => x.id)],
        edgeIds: t.edgeIds,
      });
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };

  const edges: { out: GraphEdge[]; inc: GraphEdge[] } = detail
    ? { out: detail.outgoing, inc: detail.incoming }
    : { out: graph.edges.filter((e) => e.source === id), inc: graph.edges.filter((e) => e.target === id) };
  const groups = new Map<string, GraphEdge[]>();
  for (const e of [...edges.out, ...edges.inc]) {
    const k = e.target === id ? `${e.typeLabel} (들어옴)` : e.typeLabel;
    groups.set(k, [...(groups.get(k) ?? []), e]);
  }

  return (
    <div className="overlay panel">
      <div className="row">
        <TypeDot type={type} sensitivity={node?.sensitivity} />
        <span className="muted small">{TYPE_LABEL[type]}{node?.sensitivity ? ` · 민감도 ${node.sensitivity}` : ''}</span>
        <span style={{ flex: 1 }} />
        <button className="btn small" onClick={onClose} aria-label="닫기">닫기</button>
      </div>
      <h2>{node?.label ?? id}</h2>
      <div className="faint mono">{id}</div>

      {detail && (
        <section>
          <dl className="kv">
            <dt>소속</dt><dd>{detail.departmentName ?? <span className="faint">미확정</span>}</dd>
            <dt>업무 유형</dt><dd>{detail.workTypeName ?? '-'}</dd>
            <dt>담당자</dt><dd>{detail.owners.length ? detail.owners.join(', ') : <span className="faint">없음</span>}</dd>
            <dt>상태</dt><dd>{detail.status}</dd>
            <dt>시작일</dt><dd>{detail.startDate ?? <span className="faint">미상</span>}</dd>
            <dt>월 사용량</dt><dd>{detail.monthlyCalls?.toLocaleString() ?? <span className="faint">미상</span>}</dd>
            <dt>설명</dt><dd>{detail.description}</dd>
            <dt>출처</dt><dd className="mono">{detail.sourceFile}</dd>
          </dl>
        </section>
      )}
      {detail && detail.reviewFlags.length > 0 && (
        <section>
          <h3>등록 시 확인이 필요했던 값 {detail.reviewFlags.length}건</h3>
          <div className="list">
            {detail.reviewFlags.map((f, i) => (
              <div key={i} className="small">
                <span className="badge CANDIDATE">{FIELD_LABEL[f.field] ?? f.field}</span>{' '}
                <span className="mono">{f.raw || '(빈 값)'}</span> — <span className="muted">{f.reason}</span>
              </div>
            ))}
          </div>
        </section>
      )}

      <section>
        <div className="row">
          {type === 'Agent' && <button className="btn small" onClick={() => run('dependencies')}>쓰는 대상 보기</button>}
          {(type === 'Agent' || type === 'DataAsset' || type === 'Platform') && (
            <button className="btn small primary" onClick={() => run('impact')}>바꾸면 영향받는 곳</button>
          )}
          <button className="btn small" onClick={onLocal}>주변만 보기</button>
        </div>
        {err && <div className="error small">{err}</div>}
      </section>

      {trav && (
        <section>
          <h3>{trav.direction === 'reverse' ? '점검 대상' : '쓰는 대상'} · {scope === 'confirmed' ? '확정 관계만' : '모든 관계'}</h3>
          {trav.message && <div className="notice small">{trav.message}</div>}
          <div className="list">
            {[...trav.direct, ...trav.indirect].map((x) => (
              <div key={x.id} className="rel">
                <div className="head">
                  <span className="badge neutral">{x.depth === 1 ? '직접' : `간접 ${x.depth}단계`}</span>
                  <button className="link" onClick={() => onOpen(x.id)}>{x.name}</button>
                  <span style={{ flex: 1 }} />
                  <StatusBadge status={x.status} />
                </div>
                {x.depth > 1 && <div className="faint small">{x.pathNames.join(trav.direction === 'reverse' ? ' ← ' : ' → ')}</div>}
              </div>
            ))}
          </div>
        </section>
      )}

      {[...groups.entries()].map(([k, list]) => (
        <section key={k}>
          <h3>{k} {list.length}</h3>
          <div className="list">
            {list.map((e) => <RelationItem key={e.id} edge={e} from={id} onOpen={onOpen} showEvidence={list.length <= 6} />)}
          </div>
        </section>
      ))}
      {type !== 'Agent' && groups.size === 0 && <div className="empty">연결된 관계가 없습니다.</div>}
      {detail === null && type === 'Agent' && !err && <div className="faint small">불러오는 중…</div>}
    </div>
  );
}
