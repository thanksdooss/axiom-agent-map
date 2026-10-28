# API 계약 (v1)

백엔드(Spring Boot)와 프론트엔드(React)가 함께 따르는 계약입니다. 모든 경로는 `/api/v1`으로 시작하고, 본문은 JSON(UTF-8)입니다. 필드 이름은 camelCase입니다.

## 공통 규칙

- **노드 ID 접두사**: Agent `A001`, 데이터 `D001`, 플랫폼 `P01`, 부서 `DEP01`, 업무 유형 `WT1`. 노드 종류는 접두사로 정합니다(`DEP`를 `D`보다 먼저 검사).
- **관계 방향**: 쓰는 쪽 → 제공하는 쪽. 의존(dependencies)은 정방향, 영향(impact)은 역방향 탐색.
- **관계 종류**: `USES_RESULT_OF`(결과사용), `ACCESSES_DATA`(데이터사용), `USES_PLATFORM`(사용 플랫폼), `BELONGS_TO`(소속), `HAS_WORK_TYPE`(업무 성격), `STORED_IN`(저장 위치).
- **관계 상태**: `SAMPLE`, `CANDIDATE`, `CONFIRMED`, `REJECTED`. `REJECTED`는 검토에서 반려된 관계로, 그래프·탐색·집계에서 항상 빠집니다.
- **scope 쿼리 파라미터**: `all`(SAMPLE+CANDIDATE+CONFIRMED, 기본값) 또는 `confirmed`(CONFIRMED만).
- **오류**: `{ "error": "NOT_FOUND" | "BAD_REQUEST" | ..., "message": "한국어 설명" }`와 알맞은 HTTP 상태.

## 공통 타입

```ts
type NodeType = 'Agent' | 'DataAsset' | 'Platform' | 'Department' | 'WorkType';
type RelType = 'USES_RESULT_OF' | 'ACCESSES_DATA' | 'USES_PLATFORM' | 'BELONGS_TO' | 'HAS_WORK_TYPE' | 'STORED_IN';
type RelStatus = 'SAMPLE' | 'CANDIDATE' | 'CONFIRMED' | 'REJECTED';
type Scope = 'all' | 'confirmed';

interface GraphNode {
  id: string; type: NodeType; label: string;
  group: string | null;        // Agent: 소속 부서 ID, DataAsset: 분류(고객·인사…), Platform: 분류(CRM·ERP…), 그 외 null
  sensitivity: '상' | '중' | '하' | null;   // DataAsset만
  agentStatus: '운영' | '파일럿' | '중단' | null; // Agent만
  degree: number;              // 현재 scope에서 연결된 관계 수
}
interface Evidence { file: string; locator: string; text: string | null; match?: string; directionNote?: string; }
interface GraphEdge {
  id: string; type: RelType; typeLabel: string;
  source: string; target: string; sourceName: string; targetName: string;
  status: RelStatus; reviewReason: string | null;
  evidence: Evidence[]; reviewedBy: string | null; reviewedAt: string | null;
  candidatesShown?: string[];  // 모호한 이름에서 보여준 후보 Agent ID
}
interface NodeRef { id: string; name: string; type: NodeType; }
```

## 그래프

| 메서드·경로 | 설명 | 응답 |
|---|---|---|
| `GET /graph?scope=` | 전체 노드·관계 | `{ scope, nodes: GraphNode[], edges: GraphEdge[] }` |
| `GET /nodes/{id}/neighborhood?depth=1&scope=` | 로컬 그래프(양방향, 깊이 1~3) | `{ scope, center: NodeRef, nodes, edges }` |
| `GET /path?from=&to=&scope=` | 결과사용 관계로 이어진 최단 경로(정방향) | `{ from: NodeRef, to: NodeRef, path: string[] \| null, pathNames: string[], edgeIds: string[] }` |
| `GET /cycles?scope=` | 결과사용 순환 | `{ cycles: string[][] }` (각 순환은 Agent ID 목록, 가장 작은 ID부터) |

## Agent

```ts
interface ReviewFlag { field: string; raw: string; candidate: string | number | null; reason: string; }
interface AgentSummary {
  id: string; name: string; departmentId: string | null; departmentName: string | null;
  workTypeId: string | null; workTypeName: string | null; status: string; owners: string[];
  platformIds: string[]; dataIds: string[]; highSensitivity: boolean; reviewFlagCount: number;
}
interface AgentDetail extends AgentSummary {
  description: string; startDate: string | null; monthlyCalls: number | null; sourceFile: string;
  reviewFlags: ReviewFlag[]; outgoing: GraphEdge[]; incoming: GraphEdge[];
}
```

| 메서드·경로 | 설명 | 응답 |
|---|---|---|
| `GET /agents?q=&department=&status=&scope=` | 목록 (q는 이름 부분 일치) | `AgentSummary[]` |
| `GET /agents/{id}?scope=` | 상세 | `AgentDetail` |
| `GET /agents/{id}/dependencies?depth=&scope=` | 정방향: 결과사용·데이터사용을 따라감. depth 생략 시 제한 없음 | `TraversalResponse` |
| `GET /agents/{id}/impact?depth=&scope=` | 역방향: 이 Agent의 결과를 쓰는 Agent | `TraversalResponse` |
| `GET /data-resources/{id}/impact?depth=&scope=` | 첫 단계 데이터사용, 이후 결과사용 역방향 | `TraversalResponse` |
| `GET /platforms/{id}/impact?depth=&scope=` | 첫 단계 사용 플랫폼, 이후 결과사용 역방향 | `TraversalResponse` |

```ts
interface TraversalItem {
  id: string; name: string; type: NodeType; depth: number;
  via: RelType; status: RelStatus;        // 이 노드에 닿은 마지막 관계
  path: string[]; pathNames: string[];    // 기준 노드부터 이 노드까지
}
interface TraversalResponse {
  anchor: NodeRef; direction: 'forward' | 'reverse'; depth: number | null; scope: Scope;
  direct: TraversalItem[]; indirect: TraversalItem[]; count: number;
  edgeIds: string[];                      // 하이라이트용
  message: string | null;                 // 결과 0건이면 "등록된 관계에서는 발견되지 않았습니다. 안전하다고 확정할 수는 없습니다."
}
```

탐색 규칙은 BFS이며 방문한 노드는 다시 넣지 않습니다(순환 안전). 같은 깊이에서는 ID 오름차순으로 넣습니다. 의존 탐색은 데이터 노드에서 멈춥니다.

## 검토

| 메서드·경로 | 설명 | 응답 |
|---|---|---|
| `GET /relationships?status=CANDIDATE&type=&nodeId=` | 관계 목록 필터 | `GraphEdge[]` |
| `PATCH /relationships/{id}` | 본문 `{ action: 'confirm' \| 'reject', reviewer?: string, targetId?: string }` — `targetId`가 있으면 대상을 바꾼 뒤 확정(모호한 이름에서 다른 후보 선택) | 바뀐 `GraphEdge` |
| `GET /pending-entities` | 미등록 대상 | `PendingEntity[]` |
| `POST /pending-entities/{id}/resolve` | 본문 `{ action: 'register' \| 'map' \| 'dismiss', mapToId?: string, name?: string }` | `PendingEntity` |

```ts
interface PendingEntity { id: string; rawLabel: string; kind: string; mentionedBy: string; mentionedByName: string;
  reason: string; evidence: Evidence; state: 'OPEN' | 'REGISTERED' | 'MAPPED' | 'DISMISSED'; }
```

확정하면 `reviewedBy`(기본 "검토자"), `reviewedAt`(오늘 날짜 `YYYY-MM-DD`)을 채웁니다.

## 대시보드

`GET /dashboard?scope=`

```ts
interface Dashboard {
  scope: Scope; totalAgents: number;
  agentsByStatus: Record<string, number>;
  agentsByDepartment: { id: string | null; name: string; count: number }[];   // 소속 미확정은 id null, name "(미확정)"
  highSensitivity: { count: number; share: number; agents: NodeRef[] };        // 민감도 '상' 데이터에 접근하는 Agent
  platformConcentration: { id: string; name: string; count: number }[];        // 연동 Agent 수 내림차순
  dataSensitivity: { 상: number; 중: number; 하: number };                      // 데이터 자산 수
  topDepended: { id: string; name: string; dependents: number }[];             // 결과사용 역방향 전체 깊이, 상위 6
  relationshipsByStatus: Record<string, number>;
  pendingEntities: number;
  insights: { kind: 'HIGH_SENS_MULTI_PLATFORM' | 'DUPLICATE_CANDIDATE' | 'PLATFORM_CONCENTRATION' | 'REVIEW_BACKLOG' | 'CYCLE';
              title: string; description: string; nodeIds: string[] }[];
}
```

인사이트 규칙: 고민감도 데이터 + 플랫폼 3개 이상 Agent / 업무 유형이 같고 설명 유사도가 높은 Agent 쌍 / 연동 Agent가 가장 많은 플랫폼 / CANDIDATE 대기 건수 / 결과사용 순환.

## 일괄 등록

| 메서드·경로 | 설명 | 응답 |
|---|---|---|
| `POST /imports` | multipart `files[]` (json·yaml·csv·xlsx·docx) | `ImportPreview` |
| `POST /imports/samples` | 저장소의 `data/raw` 5개 파일로 미리보기 | `ImportPreview` |
| `POST /imports/{batchId}/commit` | 미리보기 내용을 저장 | `{ addedAgents: number; addedRelationships: number; mergedEvidence: number; pending: number }` |

```ts
interface ImportPreview {
  batchId: string;
  files: { name: string; format: string; agents: number; parser: 'structured' | 'llm' | 'rule' }[];
  agents: (AgentSummary & { isNew: boolean; reviewFlags: ReviewFlag[]; sourceFile: string })[];
  relationships: (GraphEdge & { duplicateOf: string | null })[];   // 상태는 별칭 매칭이면 CONFIRMED, 검토 필요면 CANDIDATE
  pending: PendingEntity[];
  specialCases: { case: string; agentId: string; raw?: string; expected: string; evidence: Evidence }[];
}
```

표준화는 `data/vocabulary/vocabulary.json`의 prefLabel·altLabel(정확·별칭 일치 → CONFIRMED), `needs_review_labels`(→ CANDIDATE), `unregistered_labels`(→ 미등록 대상)를 씁니다. Word 서술형은 LLM이 있으면 LLM으로, 없으면 규칙 기반으로 추출합니다.

## AI 관계 추출

`POST /extractions` 본문 `{ text: string, contextAgentId?: string }`

```ts
interface ExtractionResult {
  engine: 'llm' | 'rule';
  subject: { raw: string | null; resolved: NodeRef | null };
  action: string;                 // 한국어 요약 예: "중복 표시", "기준 Agent 선택 요청", "관계 없음 (부정문)"
  items: {
    type: RelType; sourceId: string | null; targetId: string | null; targetRaw: string;
    outcome: 'NEW' | 'DUPLICATE' | 'AMBIGUOUS' | 'UNREGISTERED' | 'SELF_REFERENCE' | 'NEGATED' | 'FUTURE' | 'NO_SUBJECT';
    existingRelId: string | null; options: NodeRef[]; evidenceText: string; reason: string | null;
  }[];
}
```

`POST /extractions/apply` 본문 `{ items: [...선택한 items], reviewer?: string }` → 새 관계는 CANDIDATE로 저장하고 `GraphEdge[]` 반환. 중복은 근거만 덧붙입니다.

## 자연어 질의

`POST /ask` 본문 `{ question: string, scope?: Scope }`

```ts
interface AskResponse {
  question: string; engine: 'llm' | 'rule';
  interpretation: {
    // query_golden_set.json의 intent 이름과 같습니다.
    intent: 'aggregate' | 'dependencies' | 'impact' | 'path_explain' | 'evidence' | 'status' | 'ranking' | 'insight'
          | 'similarity' | 'path' | 'cycle' | 'unknown_entity' | 'ambiguous_anchor' | 'pending' | 'unsupported';
    anchor: NodeRef | null; anchorRaw: string | null; target: NodeRef | null;
    direction: 'forward' | 'reverse' | null; depth: number | null; scope: Scope;
  };
  clarification: { message: string; options: NodeRef[] } | null;
  answer: string;                 // 조회 결과에만 근거한 한국어 답
  result: unknown;                // intent별 원자료 (TraversalResponse, PathResponse 등)
  highlight: { nodeIds: string[]; edgeIds: string[] };
}
```

질문 해석(②)은 LLM이 위 interpretation을 만들고, 백엔드가 그래프 탐색을 실행하고, 답변 생성(③)은 탐색 결과만 근거로 씁니다. 결과가 없으면 "발견되지 않음"으로 답하고 안전하다고 단정하지 않습니다. 등록되지 않은 대상은 "0개"가 아니라 "등록되지 않은 대상"이라고 답합니다.

## 기타

| 메서드·경로 | 응답 |
|---|---|
| `GET /health` | `{ status: 'ok', llm: { enabled: boolean, provider: string \| null, model: string \| null } }` |
| `GET /vocabulary` | `data/vocabulary/vocabulary.json` 그대로 |
| `POST /admin/reset` 본문 `{ mode: 'full' \| 'sample' }` | `full`: 정답 DB 상태(seed), `sample`: 팀 설계안 샘플 3개 + 어휘 + 저장 위치만 (일괄 등록 시연용) |
