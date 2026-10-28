import type { Evidence, GraphEdge, NodeType, RelStatus, Sensitivity } from '../api/types';

export const TYPE_LABEL: Record<NodeType, string> = {
  Agent: 'Agent', DataAsset: '데이터', Platform: '플랫폼', Department: '부서', WorkType: '업무 유형',
};
export const STATUS_LABEL: Record<RelStatus, string> = {
  CONFIRMED: '확정', CANDIDATE: '검토 대기', SAMPLE: '샘플', REJECTED: '반려',
};

const css = (name: string) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
const SENS_VAR: Record<Sensitivity, string> = { 상: '--sens-high', 중: '--sens-mid', 하: '--sens-low' };
const TYPE_VAR: Record<NodeType, string> = {
  Agent: '--agent', DataAsset: '--sens-low', Platform: '--platform', Department: '--department', WorkType: '--worktype',
};

export function nodeColor(type: NodeType, sensitivity?: Sensitivity | null): string {
  if (type === 'DataAsset' && sensitivity) return css(SENS_VAR[sensitivity]);
  return css(TYPE_VAR[type]);
}

export function TypeDot({ type, sensitivity }: { type: NodeType; sensitivity?: Sensitivity | null }) {
  return <span className="dot" style={{ background: nodeColor(type, sensitivity) }} />;
}

export function StatusBadge({ status }: { status: RelStatus }) {
  return <span className={`badge ${status}`}>{STATUS_LABEL[status]}</span>;
}

export function EvidenceView({ ev }: { ev: Evidence }) {
  const note = ev.directionNote ?? ev.direction_note;
  return (
    <div className="evidence">
      {ev.text ? <q>{ev.text}</q> : <span className="faint">(원문 없음)</span>}
      <div className="faint mono">{ev.file} · {ev.locator}</div>
      {ev.match && <div className="faint">{ev.match}</div>}
      {note && <div className="faint">{note}</div>}
    </div>
  );
}

export function RelationItem({
  edge, from, onOpen, showEvidence = true,
}: { edge: GraphEdge; from?: string; onOpen?: (id: string) => void; showEvidence?: boolean }) {
  const other = from === edge.target ? { id: edge.source, name: edge.sourceName, dir: '←' } : { id: edge.target, name: edge.targetName, dir: '→' };
  return (
    <div className="rel">
      <div className="head">
        <span className="badge neutral">{edge.typeLabel}</span>
        <span className="faint">{other.dir}</span>
        {onOpen ? <button className="link" onClick={() => onOpen(other.id)}>{other.name}</button> : <span>{other.name}</span>}
        <span style={{ flex: 1 }} />
        <StatusBadge status={edge.status} />
      </div>
      {edge.reviewReason && <div className="small" style={{ color: 'var(--candidate)', marginTop: 4 }}>{edge.reviewReason}</div>}
      {showEvidence && edge.evidence.slice(0, 2).map((ev, i) => <EvidenceView key={i} ev={ev} />)}
      {edge.reviewedBy && <div className="faint small">확인: {edge.reviewedBy} · {edge.reviewedAt}</div>}
    </div>
  );
}

export function Bars({ rows, color }: { rows: { label: string; value: number; onClick?: () => void }[]; color?: string }) {
  const max = Math.max(1, ...rows.map((r) => r.value));
  return (
    <div className="bars">
      {rows.map((r) => (
        <div className="bar" key={r.label} onClick={r.onClick} style={{ cursor: r.onClick ? 'pointer' : undefined }}>
          <span className="label" title={r.label}>{r.label}</span>
          <span className="track"><span className="fill" style={{ width: `${(r.value / max) * 100}%`, background: color }} /></span>
          <span className="num">{r.value}</span>
        </div>
      ))}
    </div>
  );
}

export function Loading() {
  return <div className="empty">불러오는 중…</div>;
}

export function ErrorView({ message }: { message: string }) {
  return <div className="empty error">{message}</div>;
}
