// Obsidian 스타일 관계 지도. 노드에 마우스를 올리면 이웃만 밝아지고, 확대할수록 이름이 보입니다.
import { useCallback, useEffect, useMemo, useRef, useState, type ComponentType } from 'react';
import ForceGraph2DImport from 'react-force-graph-2d';
import type { GraphEdge, GraphNode, NodeType, RelStatus } from '../api/types';
import { NodePanel } from '../components/NodePanel';
import { ErrorView, Loading, TYPE_LABEL, TypeDot, nodeColor } from '../components/ui';
import { useApp, useLoad, type Highlight } from '../context';

// 라이브러리 제네릭 타입이 복잡해 화면 쪽에서는 느슨하게 씁니다.
const ForceGraph2D = ForceGraph2DImport as unknown as ComponentType<Record<string, unknown> & { ref?: unknown }>;

type FGNode = GraphNode & { x?: number; y?: number; r: number };
type FGLink = { id: string; type: GraphEdge['type']; status: RelStatus; source: string | FGNode; target: string | FGNode };
const idOf = (v: string | FGNode) => (typeof v === 'string' ? v : v.id);

const ALL_TYPES: NodeType[] = ['Agent', 'DataAsset', 'Platform', 'Department', 'WorkType'];
const STATUS_COLOR: Record<RelStatus, string> = { CONFIRMED: '#cbd5e1', CANDIDATE: '#fbbf24', SAMPLE: '#22d3ee', REJECTED: '#f87171' };

/** 요소가 화면에 붙는 순간부터 크기를 잽니다(로딩 화면 뒤에 늦게 붙어도 동작). */
function useSize() {
  const [el, setEl] = useState<HTMLDivElement | null>(null);
  const [size, setSize] = useState({ width: 800, height: 600 });
  useEffect(() => {
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setSize({ width: e.contentRect.width, height: e.contentRect.height }));
    ro.observe(el);
    return () => ro.disconnect();
  }, [el]);
  return { size, setEl };
}

export function GraphPage() {
  const { scope, highlight, setHighlight } = useApp();
  const { data, error, loading } = useLoad((api) => api.graph(scope), [scope]);
  const wrap = useRef<HTMLDivElement | null>(null);
  const fg = useRef<any>(null);
  const { size, setEl } = useSize();

  const [types, setTypes] = useState<Set<NodeType>>(new Set(['Agent', 'DataAsset', 'Platform', 'Department']));
  const [hideOrphans, setHideOrphans] = useState(true);
  const [alwaysLabels, setAlwaysLabels] = useState(false);
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<string | null>(highlight?.focusId ?? null);
  const [hover, setHover] = useState<string | null>(null);
  const [local, setLocal] = useState(false);
  const [depth, setDepth] = useState(1);
  const [trace, setTrace] = useState<Highlight | null>(null);
  const [showFilters, setShowFilters] = useState(() => globalThis.innerWidth >= 1100);
  const positions = useRef(new Map<string, { x: number; y: number }>());

  const emphasis = trace ?? highlight;

  // 화면에 그릴 노드·관계 (필터 → 로컬 그래프 순서)
  const graphData = useMemo(() => {
    if (!data) return { nodes: [] as FGNode[], links: [] as FGLink[] };
    let nodes = data.nodes.filter((n) => types.has(n.type));
    const visible = new Set(nodes.map((n) => n.id));
    let edges = data.edges.filter((e) => visible.has(e.source) && visible.has(e.target));
    if (local && selected && visible.has(selected)) {
      const keep = new Set([selected]);
      let frontier = [selected];
      for (let d = 0; d < depth; d++) {
        const next: string[] = [];
        for (const e of edges) {
          for (const [a, b] of [[e.source, e.target], [e.target, e.source]]) {
            if (frontier.includes(a) && !keep.has(b)) {
              keep.add(b);
              next.push(b);
            }
          }
        }
        frontier = next;
      }
      nodes = nodes.filter((n) => keep.has(n.id));
      edges = edges.filter((e) => keep.has(e.source) && keep.has(e.target));
    }
    const degree = new Map<string, number>();
    edges.forEach((e) => {
      degree.set(e.source, (degree.get(e.source) ?? 0) + 1);
      degree.set(e.target, (degree.get(e.target) ?? 0) + 1);
    });
    if (hideOrphans) nodes = nodes.filter((n) => (degree.get(n.id) ?? 0) > 0 || n.id === selected);
    return {
      nodes: nodes.map((n) => {
        const d = degree.get(n.id) ?? 0;
        const r = (n.type === 'Agent' ? 4.5 : n.type === 'Department' || n.type === 'WorkType' ? 5 : 3.2) + Math.sqrt(d) * 0.9;
        return { ...n, r, ...positions.current.get(n.id) };
      }),
      links: edges.map((e) => ({ id: e.id, type: e.type, status: e.status, source: e.source, target: e.target })),
    };
  }, [data, types, hideOrphans, local, selected, depth]);

  const neighbors = useMemo(() => {
    const m = new Map<string, Set<string>>();
    for (const l of graphData.links) {
      const s = idOf(l.source);
      const t = idOf(l.target);
      if (!m.has(s)) m.set(s, new Set());
      if (!m.has(t)) m.set(t, new Set());
      m.get(s)!.add(t);
      m.get(t)!.add(s);
    }
    return m;
  }, [graphData]);

  const emNodes = useMemo(() => new Set(emphasis?.nodeIds ?? []), [emphasis]);
  const emEdges = useMemo(() => new Set(emphasis?.edgeIds ?? []), [emphasis]);

  const nodeAlpha = useCallback(
    (id: string) => {
      if (hover) return id === hover || neighbors.get(hover)?.has(id) ? 1 : 0.12;
      if (emphasis) return emNodes.has(id) ? 1 : 0.1;
      return 1;
    },
    [hover, neighbors, emphasis, emNodes],
  );

  useEffect(() => {
    const g = fg.current;
    if (!g) return;
    g.d3Force('charge')?.strength(-55);
    g.d3Force('link')?.distance((l: FGLink) => (l.type === 'BELONGS_TO' || l.type === 'HAS_WORK_TYPE' ? 46 : 34));
  }, [graphData]);

  // 강조할 노드들로 화면을 맞춥니다. 오른쪽 상세 패널이 열려 있으면 그만큼 왼쪽으로 비켜 둡니다.
  const fitTo = useCallback((ids: string[], panelOpen: boolean) => {
    const set = new Set(ids);
    const g = fg.current;
    if (!g || !set.size) return;
    g.zoomToFit(600, 90, (n: FGNode) => set.has(n.id));
    if (panelOpen) {
      setTimeout(() => {
        const c = g.centerAt();
        if (c) g.centerAt(c.x + 200 / g.zoom(), c.y, 300);
      }, 650);
    }
  }, []);

  // 전체 보기: 왼쪽 필터 패널이 열려 있으면 그만큼 오른쪽으로 비켜 맞춥니다.
  const fitAll = useCallback(() => {
    const g = fg.current;
    if (!g) return;
    g.zoomToFit(500, 60);
    if (showFilters) {
      setTimeout(() => {
        const c = g.centerAt();
        if (c) g.centerAt(c.x - 130 / g.zoom(), c.y, 250);
      }, 520);
    }
  }, [showFilters]);

  // 처음 불러온 뒤 배치가 어느 정도 잡히면 전체가 보이게 맞춥니다(시뮬레이션이 끝날 때 한 번 더 맞춤).
  const firstFit = useRef(false);
  useEffect(() => {
    if (firstFit.current || !graphData.nodes.length || highlight) return;
    firstFit.current = true;
    const t = setTimeout(fitAll, 1200);
    return () => clearTimeout(t);
  }, [graphData, highlight, fitAll]);

  // 다른 화면에서 '지도에서 보기'로 넘어오면 해당 노드들로 화면을 맞춥니다.
  useEffect(() => {
    if (!highlight || !fg.current) return;
    if (highlight.focusId) setSelected(highlight.focusId);
    const t = setTimeout(() => fitTo(highlight.nodeIds, !!highlight.focusId), 400);
    return () => clearTimeout(t);
  }, [highlight, fitTo]);

  // 상세 패널에서 의존·영향을 탐색하면 결과로 화면을 맞춥니다.
  useEffect(() => {
    if (trace) fitTo(trace.nodeIds, true);
  }, [trace, fitTo]);

  const focusNode = useCallback((id: string) => {
    setSelected(id);
    const n = graphData.nodes.find((x) => x.id === id) as FGNode | undefined;
    if (n?.x !== undefined && n.y !== undefined) {
      fg.current?.centerAt(n.x, n.y, 500);
      fg.current?.zoom(Math.max(fg.current.zoom(), 2.2), 500);
    }
  }, [graphData]);

  const drawNode = useCallback(
    (node: FGNode, ctx: CanvasRenderingContext2D, scale: number) => {
      if (node.x === undefined || node.y === undefined) return;
      positions.current.set(node.id, { x: node.x, y: node.y });
      const a = nodeAlpha(node.id);
      ctx.globalAlpha = a;
      ctx.beginPath();
      ctx.arc(node.x, node.y, node.r, 0, 2 * Math.PI);
      ctx.fillStyle = nodeColor(node.type, node.sensitivity);
      ctx.fill();
      if (node.type === 'Department' || node.type === 'WorkType') {
        ctx.lineWidth = 1.2 / scale;
        ctx.strokeStyle = '#0e1014';
        ctx.stroke();
      }
      if (node.id === selected || (emphasis && emNodes.has(node.id))) {
        ctx.beginPath();
        ctx.arc(node.x, node.y, node.r + 2.5 / scale + 1, 0, 2 * Math.PI);
        ctx.lineWidth = 1.6 / scale;
        ctx.strokeStyle = node.id === selected ? '#ffffff' : '#c4b5fd';
        ctx.stroke();
      }
      // 허브(부서·플랫폼)는 멀리서도, Agent는 조금 확대하면, 데이터는 더 확대해야 이름이 보입니다.
      const zoomFor = node.type === 'Department' || node.type === 'Platform' || node.type === 'WorkType' ? 0.8 : node.type === 'Agent' ? 2.1 : 2.6;
      const show =
        alwaysLabels || scale > zoomFor || node.id === hover || node.id === selected ||
        (hover && neighbors.get(hover)?.has(node.id)) || (emphasis && emNodes.has(node.id));
      if (show && a > 0.5) {
        const fontSize = Math.max(10 / scale, 1.8);
        ctx.font = `${node.type === 'Agent' ? 500 : 400} ${fontSize}px Pretendard, 'Apple SD Gothic Neo', sans-serif`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'top';
        ctx.fillStyle = node.type === 'Agent' ? '#e7e9ee' : '#aab2c0';
        ctx.fillText(node.label, node.x, node.y + node.r + 2 / scale);
      }
      ctx.globalAlpha = 1;
    },
    [nodeAlpha, selected, emphasis, emNodes, alwaysLabels, hover, neighbors],
  );

  const paintArea = useCallback((node: FGNode, color: string, ctx: CanvasRenderingContext2D) => {
    if (node.x === undefined || node.y === undefined) return;
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(node.x, node.y, node.r + 2, 0, 2 * Math.PI);
    ctx.fill();
  }, []);

  const linkVisible = useCallback(
    (l: FGLink) => {
      const s = idOf(l.source);
      const t = idOf(l.target);
      if (hover) return s === hover || t === hover;
      if (emphasis) return emEdges.has(l.id) || (emNodes.has(s) && emNodes.has(t) && emEdges.size === 0);
      return true;
    },
    [hover, emphasis, emEdges, emNodes],
  );

  const results = useMemo(() => {
    const q = query.replace(/\s/g, '');
    if (!q || !data) return [];
    return data.nodes.filter((n) => n.label.replace(/\s/g, '').includes(q)).slice(0, 8);
  }, [query, data]);

  if (loading && !data) return <Loading />;
  if (error) return <ErrorView message={error} />;

  const counts = new Map<NodeType, number>();
  data?.nodes.forEach((n) => counts.set(n.type, (counts.get(n.type) ?? 0) + 1));

  return (
    <div className="graph-wrap" ref={(el) => { wrap.current = el; setEl(el); }}>
      <ForceGraph2D
        ref={fg}
        width={size.width}
        height={size.height}
        graphData={graphData}
        backgroundColor="rgba(0,0,0,0)"
        nodeId="id"
        nodeRelSize={1}
        nodeVal={(n: FGNode) => n.r * n.r}
        nodeLabel={(n: FGNode) => `${TYPE_LABEL[n.type]} · ${n.label}${n.sensitivity ? ` (민감도 ${n.sensitivity})` : ''}`}
        nodeCanvasObject={drawNode}
        nodePointerAreaPaint={paintArea}
        linkColor={(l: FGLink) => {
          if (!linkVisible(l)) return 'rgba(148,163,184,0.05)';
          const em = hover || emphasis;
          return em ? STATUS_COLOR[l.status] : l.status === 'CONFIRMED' ? 'rgba(148,163,184,0.28)' : `${STATUS_COLOR[l.status]}aa`;
        }}
        linkWidth={(l: FGLink) => (linkVisible(l) && (hover || emphasis) ? 1.6 : 0.7)}
        linkLineDash={(l: FGLink) => (l.status === 'CANDIDATE' ? [3, 2] : l.status === 'SAMPLE' ? [1, 2] : null)}
        linkDirectionalArrowLength={(l: FGLink) => (l.type === 'USES_RESULT_OF' ? 3.5 : 2)}
        linkDirectionalArrowRelPos={1}
        linkDirectionalArrowColor={(l: FGLink) => (linkVisible(l) ? STATUS_COLOR[l.status] : 'rgba(148,163,184,0.05)')}
        linkDirectionalParticles={(l: FGLink) => (emphasis && emEdges.has(l.id) ? 2 : 0)}
        linkDirectionalParticleWidth={2}
        linkDirectionalParticleColor={() => '#c4b5fd'}
        onNodeHover={(n: FGNode | null) => {
          setHover(n?.id ?? null);
          if (wrap.current) wrap.current.style.cursor = n ? 'pointer' : 'default';
        }}
        onNodeClick={(n: FGNode) => {
          setSelected(n.id);
          setTrace(null);
        }}
        onBackgroundClick={() => {
          setSelected(null);
          setTrace(null);
          setLocal(false);
        }}
        warmupTicks={90}
        cooldownTicks={120}
        onEngineStop={() => {
          if (!fg.current || (fg.current as { __fitted?: boolean }).__fitted) return;
          (fg.current as { __fitted?: boolean }).__fitted = true;
          if (!highlight) fitAll();
        }}
      />

      {!showFilters && (
        <button className="overlay btn" style={{ top: 12, left: 12 }} onClick={() => setShowFilters(true)}>필터·검색</button>
      )}
      {showFilters && <div className="overlay filters">
        <div className="row"><strong className="small">필터·검색</strong><span style={{ flex: 1 }} /><button className="btn small" onClick={() => setShowFilters(false)}>접기</button></div>
        <input className="input" placeholder="노드 검색 (예: 고객분석)" value={query} onChange={(e) => setQuery(e.target.value)} />
        {results.length > 0 && (
          <div className="search-results">
            {results.map((n) => (
              <button key={n.id} onClick={() => { focusNode(n.id); setQuery(''); }}>
                <TypeDot type={n.type} sensitivity={n.sensitivity} /> {n.label}
              </button>
            ))}
          </div>
        )}
        <h3>노드 종류</h3>
        {ALL_TYPES.map((t) => (
          <label className="toggle" key={t}>
            <input
              type="checkbox"
              checked={types.has(t)}
              onChange={() => setTypes((s) => { const n = new Set(s); if (n.has(t)) n.delete(t); else n.add(t); return n; })}
            />
            <TypeDot type={t} sensitivity={t === 'DataAsset' ? '중' : null} /> {TYPE_LABEL[t]}
            <span className="faint small" style={{ marginLeft: 'auto' }}>{counts.get(t) ?? 0}</span>
          </label>
        ))}
        <h3>보기</h3>
        <label className="toggle"><input type="checkbox" checked={hideOrphans} onChange={(e) => setHideOrphans(e.target.checked)} /> 연결 없는 노드 숨기기</label>
        <label className="toggle"><input type="checkbox" checked={alwaysLabels} onChange={(e) => setAlwaysLabels(e.target.checked)} /> 이름 항상 보기</label>
        <h3>로컬 그래프</h3>
        <label className="toggle" title={selected ? '' : '먼저 노드를 하나 고르세요'}>
          <input type="checkbox" disabled={!selected} checked={local && !!selected} onChange={(e) => setLocal(e.target.checked)} />
          고른 노드 주변만 보기
        </label>
        <div className={local && selected ? '' : 'faint'}>
          <div className="row small"><span>깊이</span><span style={{ marginLeft: 'auto' }}>{depth}</span></div>
          <input type="range" min={1} max={3} value={depth} disabled={!local || !selected} onChange={(e) => setDepth(Number(e.target.value))} />
        </div>
        <button className="btn small" onClick={fitAll}>전체 보기</button>
      </div>}

      {emphasis && (
        <div className="overlay highlight-banner">
          <span className="dot" style={{ background: 'var(--accent)' }} />
          <span>{emphasis.title}</span>
          <span className="faint">노드 {emphasis.nodeIds.length}개</span>
          <button className="btn small" onClick={() => { setTrace(null); setHighlight(null); }}>지우기</button>
        </div>
      )}

      <div className="overlay legend">
        <span><TypeDot type="Agent" /> Agent</span>
        <span><TypeDot type="DataAsset" sensitivity="상" /> 데이터(상)</span>
        <span><TypeDot type="DataAsset" sensitivity="중" /> 데이터(중)</span>
        <span><TypeDot type="DataAsset" sensitivity="하" /> 데이터(하)</span>
        <span><TypeDot type="Platform" /> 플랫폼</span>
        <span><TypeDot type="Department" /> 부서</span>
        <span><span className="line" style={{ borderColor: '#94a3b8' }} /> 확정</span>
        <span><span className="line" style={{ borderColor: '#fbbf24', borderTopStyle: 'dashed' }} /> 검토 대기</span>
        <span><span className="line" style={{ borderColor: '#22d3ee', borderTopStyle: 'dotted' }} /> 샘플</span>
      </div>

      {selected && data && (
        <NodePanel
          id={selected}
          graph={data}
          onOpen={focusNode}
          onTrace={setTrace}
          onClose={() => { setSelected(null); setTrace(null); }}
          onLocal={() => setLocal(true)}
        />
      )}
    </div>
  );
}
