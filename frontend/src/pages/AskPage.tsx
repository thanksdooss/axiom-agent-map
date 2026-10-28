// 자연어 질의: 질문 → 해석(의도·기준 대상·방향·깊이) → 그래프 탐색 → 근거 있는 답.
import { useEffect, useRef, useState } from 'react';
import type { AskResponse, TraversalResponse } from '../api/types';
import { StatusBadge } from '../components/ui';
import { useApp } from '../context';
import golden from '../generated/query_golden_set.json';

const EXAMPLES = (golden as { queries: { id: string; persona: string; question: string }[] }).queries;
const PERSONAS = [...new Set(EXAMPLES.map((q) => q.persona))];
const INTENT_LABEL: Record<string, string> = {
  aggregate: '집계', dependencies: '의존 탐색', impact: '영향 탐색', path_explain: '경로 설명', evidence: '근거', status: '검토 상태',
  ranking: '순위', insight: '인사이트', similarity: '유사도', path: '경로', cycle: '순환', unknown_entity: '미등록 대상',
  ambiguous_anchor: '모호한 대상', pending: '미등록 목록', unsupported: '지원 안 함',
};

function isTraversal(r: unknown): r is TraversalResponse {
  return !!r && typeof r === 'object' && 'direct' in r && 'anchor' in r;
}

function Answer({ r, onAsk }: { r: AskResponse; onAsk: (q: string) => void }) {
  const { showOnMap } = useApp();
  const it = r.interpretation;
  return (
    <div className="bubble">
      <div style={{ whiteSpace: 'pre-wrap' }}>{r.answer}</div>
      <div className="chips" style={{ marginTop: 10 }}>
        <span className="chip static">의도: {INTENT_LABEL[it.intent] ?? it.intent}</span>
        {it.anchor && <span className="chip static">기준: {it.anchor.name}</span>}
        {it.target && <span className="chip static">대상: {it.target.name}</span>}
        {it.direction && <span className="chip static">방향: {it.direction === 'reverse' ? '역방향(영향)' : '정방향(의존)'}</span>}
        <span className="chip static">깊이: {it.depth ?? '제한 없음'}</span>
        <span className="chip static">{it.scope === 'confirmed' ? '확정 관계만' : '모든 관계'}</span>
        <span className="chip static">해석: {r.engine === 'llm' ? 'LLM' : '규칙'}</span>
      </div>
      {isTraversal(r.result) && r.result.count > 0 && (
        <div className="list" style={{ marginTop: 10 }}>
          {[...r.result.direct, ...r.result.indirect].map((x) => (
            <div key={x.id} className="row small">
              <span className="badge neutral">{x.depth === 1 ? '직접' : `간접 ${x.depth}`}</span>
              <span>{x.name}</span>
              {x.depth > 1 && <span className="faint">{x.pathNames.join(r.result && (r.result as TraversalResponse).direction === 'reverse' ? ' ← ' : ' → ')}</span>}
              <StatusBadge status={x.status} />
            </div>
          ))}
        </div>
      )}
      {r.clarification && (
        <div className="chips" style={{ marginTop: 10 }}>
          {r.clarification.options.map((o) => (
            <span key={o.id} className="chip" onClick={() => onAsk(r.question.replace(it.anchorRaw ?? '', o.name))}>{o.name}</span>
          ))}
        </div>
      )}
      {r.highlight.nodeIds.length > 0 && (
        <button className="btn small" style={{ marginTop: 10 }} onClick={() => showOnMap({ title: r.question, nodeIds: r.highlight.nodeIds, edgeIds: r.highlight.edgeIds, focusId: it.anchor?.id })}>
          지도에서 보기
        </button>
      )}
    </div>
  );
}

export function AskPage() {
  const { api, scope, health } = useApp();
  const [thread, setThread] = useState<({ q: string } | { r: AskResponse } | { err: string })[]>([]);
  const [input, setInput] = useState('');
  const [persona, setPersona] = useState(PERSONAS[0]);
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => {
    // 최신 브라우저는 scrollIntoView가 Promise를 돌려주므로 값을 반환하지 않습니다.
    void end.current?.scrollIntoView({ behavior: 'smooth' });
  }, [thread]);

  const ask = async (q: string) => {
    if (!q.trim()) return;
    setInput('');
    setThread((t) => [...t, { q }]);
    try {
      const r = await api.ask(q, scope);
      setThread((t) => [...t, { r }]);
    } catch (e) {
      setThread((t) => [...t, { err: e instanceof Error ? e.message : String(e) }]);
    }
  };

  return (
    <div className="page" style={{ maxWidth: 880 }}>
      <h1>질문하기</h1>
      <p className="lead">
        질문을 그래프 조회로 바꿔 실행하고, 조회 결과에만 근거해 답합니다. 결과가 없으면 "발견되지 않음"이라고 하고, 등록되지 않은 대상은 "0개"가 아니라 "미등록"이라고 답합니다.
        {health?.llm.enabled ? ` 질문 해석: LLM (${health.llm.model}).` : ' 질문 해석: 규칙 기반(서버에 LLM을 연결하면 LLM이 해석).'}
      </p>
      <div className="card" style={{ marginBottom: 16 }}>
        <div className="row" style={{ marginBottom: 8 }}>
          <span className="muted small">예시 질문</span>
          <div className="segmented">
            {PERSONAS.map((p) => <button key={p} className={p === persona ? 'on' : ''} onClick={() => setPersona(p)}>{p}</button>)}
          </div>
        </div>
        <div className="chips">
          {EXAMPLES.filter((q) => q.persona === persona).map((q) => <span key={q.id} className="chip" onClick={() => ask(q.question)}>{q.question}</span>)}
        </div>
      </div>
      <div className="chat">
        {thread.map((m, i) =>
          'q' in m ? <div key={i} className="bubble user">{m.q}</div>
            : 'r' in m ? <Answer key={i} r={m.r} onAsk={ask} />
              : <div key={i} className="bubble error">{m.err}</div>,
        )}
        <div ref={end} />
      </div>
      <form className="composer" onSubmit={(e) => { e.preventDefault(); ask(input); }}>
        <input className="input" placeholder="예: CRM 고객정보를 변경하면 어떤 Agent를 점검해야 하나?" value={input} onChange={(e) => setInput(e.target.value)} />
        <button className="btn primary" type="submit">묻기</button>
      </form>
    </div>
  );
}
