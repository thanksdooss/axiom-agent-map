// 일괄 등록과 문장 속 관계 추출.
import { useState } from 'react';
import type { ExtractionItem, ExtractionResult, ImportPreview } from '../api/types';
import { EvidenceView, StatusBadge } from '../components/ui';
import { useApp, useLoad } from '../context';
import { answerKey } from '../engine/store';

const OUTCOME_LABEL: Record<ExtractionItem['outcome'], string> = {
  NEW: '새 관계', DUPLICATE: '이미 있음', AMBIGUOUS: '후보 선택 필요', UNREGISTERED: '미등록 대상', SELF_REFERENCE: '자기 참조',
  NEGATED: '부정문', FUTURE: '미래 계획', NO_SUBJECT: '주어 없음',
};

function BulkImport() {
  const { api, refresh, health } = useApp();
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [over, setOver] = useState(false);

  const wrap = async (fn: () => Promise<void>) => {
    setBusy(true);
    setMsg(null);
    try {
      await fn();
    } catch (e) {
      setMsg(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  const load = (files: File[]) => wrap(async () => setPreview(await api.importFiles(files)));
  const counts = preview
    ? preview.relationships.reduce<Record<string, number>>((m, e) => {
        const k = e.duplicateOf ? '중복' : e.status;
        m[k] = (m[k] ?? 0) + 1;
        return m;
      }, {})
    : {};

  return (
    <>
      <div className="notice small" style={{ marginBottom: 16 }}>
        시연 순서: <b>① 처음 상태로 초기화</b>(팀 설계안 샘플 Agent 3개만 남김) → <b>② 샘플 파일 5개 불러오기</b> → 미리보기 확인 → <b>③ 등록</b> → 검토 화면에서 후보 확정.
        {health?.mode === 'browser' && ' 지금은 브라우저 엔진이라 Word 문장은 규칙 기반으로 추출합니다(서버에 LLM을 연결하면 LLM이 추출).'}
      </div>
      <div className="row" style={{ marginBottom: 16 }}>
        <button className="btn" disabled={busy} onClick={() => wrap(async () => { await api.reset('sample'); setPreview(null); refresh(); setMsg('처음 상태로 초기화했습니다. 샘플 Agent 3개만 있습니다.'); })}>① 처음 상태로 초기화</button>
        <button className="btn primary" disabled={busy} onClick={() => wrap(async () => setPreview(await api.importSamples()))}>② 샘플 파일 5개 불러오기</button>
        <button className="btn" disabled={busy} onClick={() => wrap(async () => { await api.reset('full'); setPreview(null); refresh(); setMsg('정답 DB 상태(53개 Agent)로 되돌렸습니다.'); })}>정답 DB 상태로 복원</button>
      </div>
      <label
        className={`dropzone ${over ? 'over' : ''}`}
        onDragOver={(e) => { e.preventDefault(); setOver(true); }}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => { e.preventDefault(); setOver(false); load([...e.dataTransfer.files]); }}
        style={{ display: 'block', cursor: 'pointer' }}
      >
        json · yaml · csv · xlsx · docx 파일을 끌어다 놓거나 눌러서 고르세요
        <input type="file" multiple accept=".json,.yaml,.yml,.csv,.xlsx,.docx" style={{ display: 'none' }} onChange={(e) => e.target.files && load([...e.target.files])} />
      </label>
      {msg && <div className="notice small" style={{ marginTop: 12 }}>{msg}</div>}

      {preview && (
        <div className="grid" style={{ marginTop: 16 }}>
          <div className="card">
            <div className="row">
              <h2 style={{ margin: 0 }}>미리보기</h2>
              <span style={{ flex: 1 }} />
              <button className="btn primary" disabled={busy} onClick={() => wrap(async () => {
                const r = await api.commitImport(preview.batchId);
                setPreview(null);
                refresh();
                setMsg(`등록 완료: Agent ${r.addedAgents}개, 관계 ${r.addedRelationships}개 추가, 기존 관계에 근거 ${r.mergedEvidence}건 추가, 미등록 대상 ${r.pending}건.`);
              })}>③ 등록</button>
            </div>
            <table className="t" style={{ marginTop: 12 }}>
              <thead><tr><th>파일</th><th>형식</th><th>Agent</th><th>추출 방식</th></tr></thead>
              <tbody>{preview.files.map((f) => <tr key={f.name}><td className="mono">{f.name}</td><td>{f.format}</td><td>{f.agents}</td><td>{f.parser === 'structured' ? '필드 매핑' : f.parser === 'llm' ? 'LLM' : '문장 규칙'}</td></tr>)}</tbody>
            </table>
            <div className="row" style={{ marginTop: 12 }}>
              <span className="badge neutral">새 Agent {preview.agents.length}</span>
              {Object.entries(counts).map(([k, v]) => <span key={k} className={`badge ${k === '중복' ? 'neutral' : k}`}>{k === 'CONFIRMED' ? '자동 확정' : k === 'CANDIDATE' ? '검토 필요' : k} {v}</span>)}
              <span className="badge neutral">미등록 대상 {preview.pending.length}</span>
            </div>
          </div>
          <div className="card">
            <h2>특수 사례 {preview.specialCases.length}건</h2>
            <div className="list">
              {preview.specialCases.map((c, i) => (
                <div key={i} className="rel">
                  <div className="head"><span className="badge CANDIDATE">{c.case}</span><span className="small">{c.expected}</span></div>
                  <EvidenceView ev={c.evidence} />
                </div>
              ))}
            </div>
          </div>
          <div className="card" style={{ overflowX: 'auto' }}>
            <h2>새 Agent {preview.agents.length}개</h2>
            <table className="t">
              <thead><tr><th>ID</th><th>이름</th><th>소속</th><th>업무 유형</th><th>상태</th><th>확인 필요</th></tr></thead>
              <tbody>
                {preview.agents.map((a) => (
                  <tr key={a.id}>
                    <td className="mono">{a.id}</td><td>{a.name}</td><td>{a.departmentName ?? <span className="faint">미확정</span>}</td>
                    <td>{a.workTypeName}</td><td>{a.status}</td>
                    <td className="small">{a.reviewFlags.map((f) => `${f.raw || '(빈 값)'}: ${f.reason}`).join(' / ')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  );
}

function TextExtraction() {
  const { api, refresh } = useApp();
  const { data: agents } = useLoad((a) => a.agents('all'), []);
  const [text, setText] = useState(answerKey.free_text_cases[0].input);
  const [ctx, setCtx] = useState('');
  const [res, setRes] = useState<ExtractionResult | null>(null);
  const [choice, setChoice] = useState<Record<number, string>>({});
  const [saved, setSaved] = useState<string | null>(null);

  const run = async () => {
    setSaved(null);
    setChoice({});
    setRes(await api.extract(text, ctx || undefined));
  };
  const save = async () => {
    if (!res) return;
    const items = res.items.map((it, i) => (choice[i] ? { ...it, targetId: choice[i] } : it));
    const out = await api.applyExtraction(items);
    setSaved(`${out.length}건을 저장했습니다(새 관계는 검토 대기, 중복은 근거만 추가).`);
    refresh();
  };

  return (
    <div className="grid cols-2">
      <div className="card">
        <h2>문장 입력</h2>
        <textarea className="input" rows={4} style={{ width: '100%' }} value={text} onChange={(e) => setText(e.target.value)} />
        <div className="row" style={{ marginTop: 8 }}>
          <select className="input" value={ctx} onChange={(e) => setCtx(e.target.value)}>
            <option value="">기준 Agent (문장에 주어가 없을 때)</option>
            {agents?.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
          <button className="btn primary" onClick={run}>관계 찾기</button>
        </div>
        <h2 style={{ marginTop: 16 }}>시험 문장</h2>
        <div className="chips">
          {answerKey.free_text_cases.map((c) => (
            <span key={c.id} className="chip" onClick={() => { setText(c.input); setCtx(c.context_agent ?? ''); setRes(null); }}>{c.id} {c.input.slice(0, 22)}…</span>
          ))}
        </div>
      </div>
      <div className="card">
        <h2>결과</h2>
        {!res && <div className="empty">문장을 넣고 '관계 찾기'를 누르세요.</div>}
        {res && (
          <>
            <div className="row" style={{ marginBottom: 8 }}>
              <strong>{res.action}</strong>
              <span className="faint small">주어: {res.subject.resolved?.name ?? '없음'} · 엔진: {res.engine === 'llm' ? 'LLM' : '규칙'}</span>
            </div>
            <div className="list">
              {res.items.map((it, i) => (
                <div key={i} className="rel">
                  <div className="head">
                    <span className={`badge ${it.outcome === 'NEW' ? 'CANDIDATE' : it.outcome === 'DUPLICATE' ? 'CONFIRMED' : 'REJECTED'}`}>{OUTCOME_LABEL[it.outcome]}</span>
                    <span className="badge neutral">{it.type}</span>
                    <span>{it.targetRaw}</span>
                    {it.existingRelId && <span className="faint mono">{it.existingRelId}</span>}
                  </div>
                  {it.reason && <div className="small muted">{it.reason}</div>}
                  {it.outcome === 'AMBIGUOUS' && (
                    <select className="input" style={{ marginTop: 6 }} value={choice[i] ?? ''} onChange={(e) => setChoice({ ...choice, [i]: e.target.value })}>
                      <option value="">후보 고르기…</option>
                      {it.options.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
                    </select>
                  )}
                </div>
              ))}
            </div>
            <div className="row" style={{ marginTop: 10 }}>
              <button className="btn" disabled={!res.items.some((it, i) => it.outcome === 'NEW' || it.outcome === 'DUPLICATE' || (it.outcome === 'AMBIGUOUS' && choice[i]))} onClick={save}>저장 (새 관계는 검토 대기로)</button>
              {saved && <span className="small muted">{saved}</span>}
            </div>
          </>
        )}
        <p className="faint small">AI는 원문 표기만 뽑고, ID와 상태는 별칭 사전과 검증 규칙이 정합니다. 그래서 AI가 없는 Agent를 지어내도 그래프에 들어가지 않습니다. <StatusBadge status="CANDIDATE" /></p>
      </div>
    </div>
  );
}

export function ImportPage() {
  const [tab, setTab] = useState<'bulk' | 'text'>('bulk');
  return (
    <div className="page">
      <h1>등록</h1>
      <p className="lead">부서가 제각각 낸 파일을 표준 어휘(별칭 사전)로 맞춰 Agent와 관계를 만듭니다.</p>
      <div className="tabs">
        <button className={tab === 'bulk' ? 'on' : ''} onClick={() => setTab('bulk')}>파일 일괄 등록</button>
        <button className={tab === 'text' ? 'on' : ''} onClick={() => setTab('text')}>문장에서 관계 찾기</button>
      </div>
      {tab === 'bulk' ? <BulkImport /> : <TextExtraction />}
    </div>
  );
}
