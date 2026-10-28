// 그래프 탐색. data/tools/build_dataset.py 의 Graph 클래스와 같은 규칙(BFS, 방문 노드 재방문 없음, 같은 깊이에서는 ID 오름차순)입니다.
import type {
  GraphEdge, GraphNode, GraphResponse, NeighborhoodResponse, NodeRef, PathResponse, RelType, Scope,
  TraversalItem, TraversalResponse,
} from '../api/types';
import type { State } from './store';
import { nameOf } from './store';
import { nodeType, vocabulary } from './vocab';

export const NOT_FOUND_MESSAGE = '등록된 관계에서는 발견되지 않았습니다. 안전하다고 확정할 수는 없습니다.';
const byId = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

export class Graph {
  readonly edges: GraphEdge[];
  private out = new Map<string, GraphEdge[]>();
  private inc = new Map<string, GraphEdge[]>();

  constructor(readonly state: State, readonly scope: Scope) {
    const ok = scope === 'confirmed' ? new Set(['CONFIRMED']) : new Set(['SAMPLE', 'CANDIDATE', 'CONFIRMED']);
    this.edges = state.edges.filter((e) => ok.has(e.status));
    for (const e of this.edges) {
      if (!this.out.has(e.source)) this.out.set(e.source, []);
      if (!this.inc.has(e.target)) this.inc.set(e.target, []);
      this.out.get(e.source)!.push(e);
      this.inc.get(e.target)!.push(e);
    }
  }

  ref(id: string): NodeRef {
    return { id, name: nameOf(this.state, id), type: nodeType(id) };
  }

  exists(id: string): boolean {
    if (nodeType(id) === 'Agent') return this.state.agents.some((a) => a.id === id);
    return vocabularyIds.has(id);
  }

  targets(src: string, type: RelType): string[] {
    return (this.out.get(src) ?? []).filter((e) => e.type === type).map((e) => e.target).sort(byId);
  }

  sources(tgt: string, type: RelType): string[] {
    return (this.inc.get(tgt) ?? []).filter((e) => e.type === type).map((e) => e.source).sort(byId);
  }

  private traverse(anchor: string, depthLimit: number | null, forward: boolean): TraversalResponse {
    const limit = depthLimit ?? 99;
    const first: RelType = { Agent: 'USES_RESULT_OF', DataAsset: 'ACCESSES_DATA', Platform: 'USES_PLATFORM' }[
      nodeType(anchor) as 'Agent' | 'DataAsset' | 'Platform'
    ] as RelType;
    const seen = new Set([anchor]);
    const items: TraversalItem[] = [];
    const edgeIds: string[] = [];
    const queue: [string, number, string[]][] = [[anchor, 0, [anchor]]];
    while (queue.length) {
      const [n, d, path] = queue.shift()!;
      if (d === limit) continue;
      const list = forward
        ? [...(this.out.get(n) ?? [])].sort((a, b) => byId(a.target, b.target))
        : [...(this.inc.get(n) ?? [])].sort((a, b) => byId(a.source, b.source));
      for (const e of list) {
        const next = forward ? e.target : e.source;
        if (forward) {
          if ((e.type !== 'USES_RESULT_OF' && e.type !== 'ACCESSES_DATA') || seen.has(next)) continue;
        } else {
          const want: RelType = d === 0 ? first : 'USES_RESULT_OF';
          if (e.type !== want || seen.has(next)) continue;
        }
        seen.add(next);
        edgeIds.push(e.id);
        const p = [...path, next];
        items.push({
          id: next, name: nameOf(this.state, next), type: nodeType(next), depth: d + 1, via: e.type,
          status: e.status, path: p, pathNames: p.map((x) => nameOf(this.state, x)),
        });
        // 의존 탐색은 데이터 노드에서 멈춥니다.
        if (forward && e.type !== 'USES_RESULT_OF') continue;
        queue.push([next, d + 1, p]);
      }
    }
    const direct = items.filter((x) => x.depth === 1);
    const indirect = items.filter((x) => x.depth > 1);
    return {
      anchor: this.ref(anchor), direction: forward ? 'forward' : 'reverse', depth: depthLimit, scope: this.scope,
      direct, indirect, count: items.length, edgeIds, message: items.length ? null : NOT_FOUND_MESSAGE,
    };
  }

  dependencies(anchor: string, depth: number | null): TraversalResponse {
    return this.traverse(anchor, depth, true);
  }

  impact(anchor: string, depth: number | null): TraversalResponse {
    return this.traverse(anchor, depth, false);
  }

  path(src: string, dst: string): PathResponse {
    const prev = new Map<string, GraphEdge | null>([[src, null]]);
    const queue = [src];
    while (queue.length) {
      const n = queue.shift()!;
      if (n === dst) break;
      for (const e of [...(this.out.get(n) ?? [])].sort((a, b) => byId(a.target, b.target))) {
        if (e.type === 'USES_RESULT_OF' && !prev.has(e.target)) {
          prev.set(e.target, e);
          queue.push(e.target);
        }
      }
    }
    if (!prev.has(dst)) return { from: this.ref(src), to: this.ref(dst), path: null, pathNames: [], edgeIds: [] };
    const path: string[] = [];
    const edgeIds: string[] = [];
    for (let cur: string | undefined = dst; cur; ) {
      path.unshift(cur);
      const e = prev.get(cur);
      if (!e) break;
      edgeIds.unshift(e.id);
      cur = e.source;
    }
    return { from: this.ref(src), to: this.ref(dst), path, pathNames: path.map((x) => nameOf(this.state, x)), edgeIds };
  }

  cycles(): string[][] {
    const found = new Map<string, string[]>();
    const agents = [...new Set(this.edges.filter((e) => e.type === 'USES_RESULT_OF').map((e) => e.source))].sort(byId);
    for (const s of agents) {
      const stack: [string, string[]][] = [[s, [s]]];
      while (stack.length) {
        const [n, p] = stack.pop()!;
        for (const e of this.out.get(n) ?? []) {
          if (e.type !== 'USES_RESULT_OF') continue;
          if (e.target === s) {
            const k = p.indexOf([...p].sort(byId)[0]);
            const cyc = [...p.slice(k), ...p.slice(0, k)];
            found.set(cyc.join('>'), cyc);
          } else if (!p.includes(e.target) && p.length < 10) {
            stack.push([e.target, [...p, e.target]]);
          }
        }
      }
    }
    return [...found.keys()].sort().map((k) => found.get(k)!);
  }

  nodes(): GraphNode[] {
    const degree = new Map<string, number>();
    for (const e of this.edges) {
      degree.set(e.source, (degree.get(e.source) ?? 0) + 1);
      degree.set(e.target, (degree.get(e.target) ?? 0) + 1);
    }
    const base = (id: string, type: GraphNode['type'], label: string): GraphNode => ({
      id, type, label, group: null, sensitivity: null, agentStatus: null, degree: degree.get(id) ?? 0,
    });
    return [
      ...this.state.agents.map((a) => ({ ...base(a.id, 'Agent', a.name), group: a.department, agentStatus: a.status })),
      ...vocabulary.departments.map((c) => base(c.id, 'Department', c.prefLabel)),
      ...vocabulary.work_types.map((c) => base(c.id, 'WorkType', c.prefLabel)),
      ...vocabulary.platforms.map((c) => ({ ...base(c.id, 'Platform', c.prefLabel), group: c.broader ?? null })),
      ...vocabulary.data_assets.map((c) => ({
        ...base(c.id, 'DataAsset', c.prefLabel), group: c.broader ?? null, sensitivity: c.sensitivity ?? null,
      })),
    ];
  }

  full(): GraphResponse {
    return { scope: this.scope, nodes: this.nodes(), edges: this.edges };
  }

  neighborhood(center: string, depth: number): NeighborhoodResponse {
    const seen = new Set([center]);
    let frontier = [center];
    for (let d = 0; d < depth; d++) {
      const next: string[] = [];
      for (const n of frontier) {
        for (const e of [...(this.out.get(n) ?? []), ...(this.inc.get(n) ?? [])]) {
          const m = e.source === n ? e.target : e.source;
          if (!seen.has(m)) {
            seen.add(m);
            next.push(m);
          }
        }
      }
      frontier = next;
    }
    return {
      scope: this.scope,
      center: this.ref(center),
      nodes: this.nodes().filter((n) => seen.has(n.id)),
      edges: this.edges.filter((e) => seen.has(e.source) && seen.has(e.target)),
    };
  }
}

const vocabularyIds = new Set([
  ...vocabulary.departments, ...vocabulary.work_types, ...vocabulary.platforms, ...vocabulary.data_assets,
].map((c) => c.id));
