// Spring Boot 백엔드(/api/v1)에 붙는 API 구현.
import type {
  AgentDetail, AgentSummary, Api, AskResponse, CommitResult, Dashboard, ExtractionItem, ExtractionResult, GraphEdge,
  GraphResponse, Health, ImportPreview, NeighborhoodResponse, PathResponse, PendingEntity, RelStatus, Scope,
  TraversalResponse,
} from './types';

export class HttpApi implements Api {
  constructor(private readonly base: string) {}

  private async req<T>(path: string, init?: RequestInit): Promise<T> {
    const res = await fetch(`${this.base.replace(/\/$/, '')}/api/v1${path}`, {
      ...init,
      headers: init?.body instanceof FormData ? init.headers : { 'Content-Type': 'application/json', ...init?.headers },
    });
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new Error((body as { message?: string }).message ?? `서버 오류 ${res.status}`);
    }
    return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
  }

  private qs(params: Record<string, string | number | null | undefined>) {
    const p = Object.entries(params).filter(([, v]) => v !== null && v !== undefined && v !== '');
    return p.length ? `?${p.map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`).join('&')}` : '';
  }

  health() { return this.req<Health>('/health').then((h) => ({ ...h, mode: 'server' as const })); }
  graph(scope: Scope) { return this.req<GraphResponse>(`/graph${this.qs({ scope })}`); }
  neighborhood(id: string, depth: number, scope: Scope) { return this.req<NeighborhoodResponse>(`/nodes/${id}/neighborhood${this.qs({ depth, scope })}`); }
  path(from: string, to: string, scope: Scope) { return this.req<PathResponse>(`/path${this.qs({ from, to, scope })}`); }
  agents(scope: Scope, q?: string) { return this.req<AgentSummary[]>(`/agents${this.qs({ scope, q })}`); }
  agent(id: string, scope: Scope) { return this.req<AgentDetail>(`/agents/${id}${this.qs({ scope })}`); }
  dependencies(id: string, depth: number | null, scope: Scope) { return this.req<TraversalResponse>(`/agents/${id}/dependencies${this.qs({ depth, scope })}`); }
  impact(id: string, depth: number | null, scope: Scope) {
    const kind = id.startsWith('DEP') ? 'agents' : id.startsWith('A') ? 'agents' : id.startsWith('D') ? 'data-resources' : 'platforms';
    return this.req<TraversalResponse>(`/${kind}/${id}/impact${this.qs({ depth, scope })}`);
  }
  relationships(filter: { status?: RelStatus; nodeId?: string }) { return this.req<GraphEdge[]>(`/relationships${this.qs(filter)}`); }
  review(id: string, action: 'confirm' | 'reject', targetId?: string) {
    return this.req<GraphEdge>(`/relationships/${id}`, { method: 'PATCH', body: JSON.stringify({ action, targetId }) });
  }
  pendingEntities() { return this.req<PendingEntity[]>('/pending-entities'); }
  resolvePending(id: string, action: 'register' | 'map' | 'dismiss', mapToId?: string) {
    return this.req<PendingEntity>(`/pending-entities/${id}/resolve`, { method: 'POST', body: JSON.stringify({ action, mapToId }) });
  }
  dashboard(scope: Scope) { return this.req<Dashboard>(`/dashboard${this.qs({ scope })}`); }
  importSamples() { return this.req<ImportPreview>('/imports/samples', { method: 'POST' }); }
  importFiles(files: File[]) {
    const form = new FormData();
    files.forEach((f) => form.append('files', f));
    return this.req<ImportPreview>('/imports', { method: 'POST', body: form });
  }
  commitImport(batchId: string) { return this.req<CommitResult>(`/imports/${batchId}/commit`, { method: 'POST' }); }
  extract(text: string, contextAgentId?: string) {
    return this.req<ExtractionResult>('/extractions', { method: 'POST', body: JSON.stringify({ text, contextAgentId }) });
  }
  applyExtraction(items: ExtractionItem[]) {
    return this.req<GraphEdge[]>('/extractions/apply', { method: 'POST', body: JSON.stringify({ items }) });
  }
  ask(question: string, scope: Scope) { return this.req<AskResponse>('/ask', { method: 'POST', body: JSON.stringify({ question, scope }) }); }
  reset(mode: 'full' | 'sample') { return this.req<void>('/admin/reset', { method: 'POST', body: JSON.stringify({ mode }) }); }
}
