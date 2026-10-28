// docs/api-contract.md 와 같은 타입입니다. 백엔드(Spring Boot)와 브라우저 엔진이 모두 이 모양으로 응답합니다.

export type NodeType = 'Agent' | 'DataAsset' | 'Platform' | 'Department' | 'WorkType';
export type RelType = 'USES_RESULT_OF' | 'ACCESSES_DATA' | 'USES_PLATFORM' | 'BELONGS_TO' | 'HAS_WORK_TYPE' | 'STORED_IN';
export type RelStatus = 'SAMPLE' | 'CANDIDATE' | 'CONFIRMED' | 'REJECTED';
export type Scope = 'all' | 'confirmed';
export type Sensitivity = '상' | '중' | '하';

export interface GraphNode {
  id: string;
  type: NodeType;
  label: string;
  group: string | null;
  sensitivity: Sensitivity | null;
  agentStatus: string | null;
  degree: number;
}

export interface Evidence {
  file: string;
  locator: string;
  text: string | null;
  match?: string;
  directionNote?: string;
  direction_note?: string;
}

export interface GraphEdge {
  id: string;
  type: RelType;
  typeLabel: string;
  source: string;
  target: string;
  sourceName: string;
  targetName: string;
  status: RelStatus;
  reviewReason: string | null;
  evidence: Evidence[];
  reviewedBy: string | null;
  reviewedAt: string | null;
  candidatesShown?: string[];
}

export interface NodeRef {
  id: string;
  name: string;
  type: NodeType;
}

export interface GraphResponse {
  scope: Scope;
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface NeighborhoodResponse extends GraphResponse {
  center: NodeRef;
}

export interface PathResponse {
  from: NodeRef;
  to: NodeRef;
  path: string[] | null;
  pathNames: string[];
  edgeIds: string[];
}

export interface ReviewFlag {
  field: string;
  raw: string;
  candidate: string | number | null;
  reason: string;
}

export interface AgentSummary {
  id: string;
  name: string;
  departmentId: string | null;
  departmentName: string | null;
  workTypeId: string | null;
  workTypeName: string | null;
  status: string;
  owners: string[];
  platformIds: string[];
  dataIds: string[];
  highSensitivity: boolean;
  reviewFlagCount: number;
}

export interface AgentDetail extends AgentSummary {
  description: string;
  startDate: string | null;
  monthlyCalls: number | null;
  sourceFile: string;
  reviewFlags: ReviewFlag[];
  outgoing: GraphEdge[];
  incoming: GraphEdge[];
}

export interface TraversalItem {
  id: string;
  name: string;
  type: NodeType;
  depth: number;
  via: RelType;
  status: RelStatus;
  path: string[];
  pathNames: string[];
}

export interface TraversalResponse {
  anchor: NodeRef;
  direction: 'forward' | 'reverse';
  depth: number | null;
  scope: Scope;
  direct: TraversalItem[];
  indirect: TraversalItem[];
  count: number;
  edgeIds: string[];
  message: string | null;
}

export interface PendingEntity {
  id: string;
  rawLabel: string;
  kind: string;
  mentionedBy: string;
  mentionedByName: string;
  reason: string;
  evidence: Evidence;
  state: 'OPEN' | 'REGISTERED' | 'MAPPED' | 'DISMISSED';
}

export interface Insight {
  kind: 'HIGH_SENS_MULTI_PLATFORM' | 'DUPLICATE_CANDIDATE' | 'PLATFORM_CONCENTRATION' | 'REVIEW_BACKLOG' | 'CYCLE';
  title: string;
  description: string;
  nodeIds: string[];
}

export interface Dashboard {
  scope: Scope;
  totalAgents: number;
  agentsByStatus: Record<string, number>;
  agentsByDepartment: { id: string | null; name: string; count: number }[];
  highSensitivity: { count: number; share: number; agents: NodeRef[] };
  platformConcentration: { id: string; name: string; count: number }[];
  dataSensitivity: Record<Sensitivity, number>;
  topDepended: { id: string; name: string; dependents: number }[];
  relationshipsByStatus: Record<string, number>;
  pendingEntities: number;
  insights: Insight[];
}

export interface SpecialCase {
  case: string;
  agentId: string;
  raw?: string;
  expected: string;
  evidence: Evidence;
}

export interface ImportPreview {
  batchId: string;
  files: { name: string; format: string; agents: number; parser: 'structured' | 'llm' | 'rule' }[];
  agents: (AgentSummary & { isNew: boolean; reviewFlags: ReviewFlag[]; sourceFile: string })[];
  relationships: (GraphEdge & { duplicateOf: string | null })[];
  pending: PendingEntity[];
  specialCases: SpecialCase[];
}

export interface CommitResult {
  addedAgents: number;
  addedRelationships: number;
  mergedEvidence: number;
  pending: number;
}

export type ExtractionOutcome =
  | 'NEW' | 'DUPLICATE' | 'AMBIGUOUS' | 'UNREGISTERED' | 'SELF_REFERENCE' | 'NEGATED' | 'FUTURE' | 'NO_SUBJECT';

export interface ExtractionItem {
  type: RelType;
  sourceId: string | null;
  targetId: string | null;
  targetRaw: string;
  outcome: ExtractionOutcome;
  existingRelId: string | null;
  options: NodeRef[];
  evidenceText: string;
  reason: string | null;
}

export interface ExtractionResult {
  engine: 'llm' | 'rule';
  subject: { raw: string | null; resolved: NodeRef | null };
  action: string;
  items: ExtractionItem[];
}

export type Intent =
  | 'aggregate' | 'dependencies' | 'impact' | 'path_explain' | 'evidence' | 'status' | 'ranking' | 'insight'
  | 'similarity' | 'path' | 'cycle' | 'unknown_entity' | 'ambiguous_anchor' | 'pending' | 'unsupported';

export interface AskResponse {
  question: string;
  engine: 'llm' | 'rule';
  interpretation: {
    intent: Intent | string;
    anchor: NodeRef | null;
    anchorRaw: string | null;
    target: NodeRef | null;
    direction: 'forward' | 'reverse' | null;
    depth: number | null;
    scope: Scope;
  };
  clarification: { message: string; options: NodeRef[] } | null;
  answer: string;
  result: unknown;
  highlight: { nodeIds: string[]; edgeIds: string[] };
}

export interface Health {
  status: string;
  llm: { enabled: boolean; provider: string | null; model: string | null };
  mode?: 'browser' | 'server';
}

export interface Api {
  health(): Promise<Health>;
  graph(scope: Scope): Promise<GraphResponse>;
  neighborhood(id: string, depth: number, scope: Scope): Promise<NeighborhoodResponse>;
  path(from: string, to: string, scope: Scope): Promise<PathResponse>;
  agents(scope: Scope, q?: string): Promise<AgentSummary[]>;
  agent(id: string, scope: Scope): Promise<AgentDetail>;
  dependencies(id: string, depth: number | null, scope: Scope): Promise<TraversalResponse>;
  impact(id: string, depth: number | null, scope: Scope): Promise<TraversalResponse>;
  relationships(filter: { status?: RelStatus; nodeId?: string }): Promise<GraphEdge[]>;
  review(id: string, action: 'confirm' | 'reject', targetId?: string): Promise<GraphEdge>;
  pendingEntities(): Promise<PendingEntity[]>;
  resolvePending(id: string, action: 'register' | 'map' | 'dismiss', mapToId?: string): Promise<PendingEntity>;
  dashboard(scope: Scope): Promise<Dashboard>;
  importSamples(): Promise<ImportPreview>;
  importFiles(files: File[]): Promise<ImportPreview>;
  commitImport(batchId: string): Promise<CommitResult>;
  extract(text: string, contextAgentId?: string): Promise<ExtractionResult>;
  applyExtraction(items: ExtractionItem[]): Promise<GraphEdge[]>;
  ask(question: string, scope: Scope): Promise<AskResponse>;
  reset(mode: 'full' | 'sample'): Promise<void>;
}
