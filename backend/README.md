# 엑시옴(Axiom) 백엔드

온톨로지 기반 Agent MAP MVP의 REST API 서버입니다. `docs/api-contract.md`(v1)의 모든 엔드포인트를 구현하고,
`data/`의 더미 데이터(어휘·정답 DB·부서 제출 원본)를 읽어 동작합니다.

| 항목 | 값 |
|---|---|
| 언어·런타임 | Java 21 |
| 프레임워크 | Spring Boot 4.1.2 (Spring Web MVC, Spring Data JPA, Jackson 3) |
| DB | H2 인메모리 (PostgreSQL 호환 모드) |
| 파서 | Jackson(JSON·YAML), Apache Commons CSV, Apache POI(xlsx·docx) |
| LLM | Anthropic 공식 Java SDK / OpenAI 호환 HTTP / 없음(규칙 기반) |

## 실행

```bash
cd backend
./mvnw spring-boot:run          # http://localhost:8080
curl localhost:8080/api/v1/health
curl localhost:8080/api/v1/graph    # Agent 53개 + 어휘 노드 65개, 관계 326개
```

- 시작하면 `full` 모드(정답 DB 상태)로 적재합니다. 일괄 등록 시연은 `POST /api/v1/admin/reset {"mode":"sample"}` →
  `POST /api/v1/imports/samples` → `POST /api/v1/imports/{batchId}/commit` 순서입니다.
- H2 콘솔: http://localhost:8080/h2-console (JDBC URL `jdbc:h2:mem:axiom`, 사용자 `sa`, 비밀번호 없음)
- 테스트: `./mvnw -q test` (LLM 없이 규칙 기반으로 실행, 외부 호출 없음)
- 실행 JAR: `./mvnw -q package -DskipTests && java -jar target/axiom-backend-0.1.0-SNAPSHOT.jar`
  (이때 데이터 폴더는 실행 위치 기준 `../data` 이므로 `backend/`에서 실행하거나 `AXIOM_DATA_DIR`을 지정하세요.)

## 설정·환경변수

| 설정 (application.yml) | 환경변수 | 기본값 | 설명 |
|---|---|---|---|
| `axiom.data-dir` | `AXIOM_DATA_DIR` | `../data` | 데이터 폴더. 상대 경로는 실행 위치 기준, 절대 경로도 됨 |
| `axiom.startup-mode` | – | `full` | 시작 시 적재 모드 (`full` \| `sample` \| `none`=적재 안 함) |
| `axiom.cors-origins` | – | `http://localhost:5173` | CORS 허용 출처 목록 |
| `axiom.timezone` | – | `Asia/Seoul` | 검토일(`reviewedAt`) 계산 기준 |
| `axiom.llm.provider` | `AXIOM_LLM_PROVIDER` | `auto` | `auto` \| `anthropic` \| `openai` \| `none` |
| `axiom.llm.anthropic.model` | `AXIOM_ANTHROPIC_MODEL` | `claude-opus-5-5` | Anthropic 모델 |
| `axiom.llm.anthropic.effort` | – | `low` | 응답 깊이(effort). 해석·추출은 분류 작업이라 low |
| `axiom.llm.anthropic.fallbacks` | – | `true` | 안전 분류기 거절 시 서버 측 대체 모델 사용 |
| `axiom.llm.openai.base-url` | `OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI 호환 서버 주소 |
| `axiom.llm.openai.model` | `OPENAI_MODEL` | `gpt-4o-mini` | OpenAI 호환 모델 |
| `axiom.llm.openai.json-schema` | – | `true` | `json_schema` 응답 형식을 못 쓰는 서버면 `false`(json_object) |
| – | `ANTHROPIC_API_KEY` | – | Anthropic 키 (코드·로그에 남기지 않음) |
| – | `OPENAI_API_KEY` | – | OpenAI 호환 키 |

## LLM 전환

```bash
# 1) 규칙 기반 (키 없이 기본)
AXIOM_LLM_PROVIDER=none ./mvnw spring-boot:run

# 2) Anthropic (ANTHROPIC_API_KEY가 있으면 auto로도 선택됨)
export ANTHROPIC_API_KEY=...        # 키는 셸에서만
./mvnw spring-boot:run

# 3) 과정에서 받은 OpenAI 호환 키
export OPENAI_API_KEY=...
AXIOM_LLM_PROVIDER=openai OPENAI_BASE_URL=https://<게이트웨이>/v1 OPENAI_MODEL=<모델> ./mvnw spring-boot:run
```

`GET /api/v1/health`의 `llm.enabled / provider / model`로 현재 상태를 확인합니다.

LLM이 맡는 일과 맡지 않는 일:

| 기능 | LLM이 하는 일 | 백엔드 규칙이 하는 일 |
|---|---|---|
| Word 서술형 일괄 등록 | 문장에서 **원문 표기 + 문장 번호** 추출 (구조화 출력) | 어휘로 ID·상태(CONFIRMED/CANDIDATE)·미등록 대상 결정 |
| AI 관계 추출 | 주어·대상 표기, 부정·미래 여부 | 표준화, 중복·자기 참조·모호·미등록 분류 |
| 자연어 질의 ② 해석 | intent + 기준 표기 (JSON Schema) | 표기 → ID, 그래프 탐색 실행 |
| ③ 답변 | 조회 결과 JSON만 보고 한국어 답 | 결과에 없는 Agent 이름이 나오면 버리고 규칙 답변 사용 |

LLM 호출이 실패·거절·형식 오류면 같은 요청을 규칙 기반으로 처리합니다(응답의 `engine`·`parser` 값이 `rule`).

## 패키지 구성

```
com.axiom.agentmap
├─ AxiomApplication
├─ config        AxiomProperties(axiom.*), WebConfig(CORS), DataPaths(데이터 폴더·오늘 날짜)
├─ domain        JPA 엔티티(agents·data_resources·platforms·departments·work_types·relationships
│                ·relationship_evidence·pending_entities) + RelType·RelStatus·NodeType·Scope
├─ repository    Spring Data JPA 저장소
├─ vocabulary    Vocabulary(vocabulary.json), Lexicon(표기→ID: 정확·별칭→검토 필요→미등록), TextScanner(문장 속 표기 찾기)
├─ text          Normalizers(parse_date·parse_usage·parse_owner와 같은 규칙), Cues(연동·참고·부정·예정 단서), Texts
├─ graph         GraphView(순수 BFS: 의존·영향·경로·순환·이웃), GraphStore(JPA→GraphView 변환), GraphService(응답 DTO)
├─ agents        AgentService (목록·상세)
├─ review        ReviewService(관계 확정·반려·대상 변경, 미등록 대상 처리), IdGenerator
├─ dashboard     DashboardService(지표·인사이트), SimilarityService(중복 개발 후보)
├─ importing     SubmissionParsers(5개 형식), FieldMap(필드 이름 매핑), NarrativeRuleExtractor / LlmNarrativeExtractor(Word),
│                ImportNormalizer(표준화), ImportService(미리보기·저장)
├─ extraction    ExtractionService (자유 입력 관계 추출·적용)
├─ ask           RuleQuestionInterpreter / LlmQuestionInterpreter(②), AskService(실행·③ 답변)
├─ llm           LlmClient + AnthropicLlmClient / OpenAiCompatibleLlmClient / DisabledLlmClient, LlmConfiguration
├─ seed          DatasetLoader(full·sample 초기화), StartupLoader
├─ api           계약 DTO(record)
└─ web           ApiController(/api/v1 전체), ApiException·ApiExceptionHandler({error, message})
```

그래프 알고리즘은 `GraphView`(저장소와 무관한 순수 객체)에만 있고, 저장소 접근은 `GraphStore` 한 곳입니다.
PostgreSQL이나 Neo4j로 바꿀 때 서비스 코드는 그대로 두고 `GraphStore`(필요하면 저장소 인터페이스)만 바꾸면 됩니다.

## 엔드포인트 (요약)

| 영역 | 경로 |
|---|---|
| 그래프 | `GET /graph`, `GET /nodes/{id}/neighborhood`, `GET /path`, `GET /cycles` |
| Agent | `GET /agents`, `GET /agents/{id}`, `GET /agents/{id}/dependencies`, `GET /agents/{id}/impact`, `GET /data-resources/{id}/impact`, `GET /platforms/{id}/impact` |
| 검토 | `GET /relationships`, `PATCH /relationships/{id}`, `GET /pending-entities`, `POST /pending-entities/{id}/resolve` |
| 대시보드 | `GET /dashboard` |
| 일괄 등록 | `POST /imports`(multipart `files[]`), `POST /imports/samples`, `POST /imports/{batchId}/commit` |
| AI 추출 | `POST /extractions`, `POST /extractions/apply` |
| 질의 | `POST /ask` |
| 기타 | `GET /health`, `GET /vocabulary`, `POST /admin/reset` |

모든 경로 앞에 `/api/v1`이 붙고, `scope=all|confirmed`(기본 all)를 받습니다. `REJECTED` 관계는 그래프·탐색·집계에서 항상 빠집니다.

## 계약을 해석해 정한 부분

- **소속·업무 유형**: Agent의 `departmentId`·`group`은 현재 scope의 소속 관계에서 읽습니다. 그래서 `confirmed`에서는 소속이 CANDIDATE인 Agent가 `null`(대시보드 "(미확정)")이 됩니다.
- **`relationshipsByStatus`**: scope와 무관하게 전체 관계(REJECTED 제외) 상태별 개수입니다.
- **`pendingEntities`(대시보드)**: 상태가 OPEN인 미등록 대상 수입니다.
- **중복 개발 후보**: 0.4×Jaccard(이름 글자 bigram, " Agent" 제외) + 0.6×Jaccard(설명 글자 bigram), 상위 6쌍 (프론트 엔진과 같은 규칙).
- **`/ask` 해석의 보조 필드**: `anchorType`, `relationTypes`, `groupBy`, `filter`(`dataSensitivity`·`platformCountGte`·`status`·`pending`), `metric`, `top`, `relation`을 추가로 돌려줍니다.
- **"왜 점검 대상인가"(path_explain)**: 질문에 기준 Agent가 없으면, 그 Agent가 결과사용으로 기대는 가장 먼 상류 Agent를 기준으로 삼습니다.
- **일괄 등록 확정 관계**: 별칭 사전으로 확정된 관계는 `reviewedBy = "표준 어휘 자동 확정"`, `reviewedAt = 저장일`입니다.
- **미등록 대상 처리**: `register`/`map` 하면 언급한 Agent → 대상 관계를 CONFIRMED로 만듭니다(사람이 처리한 결과).
- **AI 추출 적용**: 새 관계는 CANDIDATE, 중복은 근거만 덧붙이고, UNREGISTERED 항목은 미등록 대상으로 올립니다.

## H2 → PostgreSQL 전환

1. `pom.xml`에 드라이버 추가
   ```xml
   <dependency>
       <groupId>org.postgresql</groupId>
       <artifactId>postgresql</artifactId>
       <scope>runtime</scope>
   </dependency>
   ```
2. `application.yml`(또는 `application-postgres.yml` 프로필)의 데이터소스 변경
   ```yaml
   spring:
     datasource:
       url: jdbc:postgresql://localhost:5432/axiom
       username: axiom
       password: ${DB_PASSWORD}
     jpa:
       hibernate:
         ddl-auto: update     # 운영은 validate + Flyway/Liquibase 권장
   ```
3. 기본값은 시작할 때마다 `full`로 다시 적재하므로, 첫 적재 뒤에는 `axiom.startup-mode: none`으로 바꿔 데이터를 보존하세요.

엔티티는 표준 JPA 타입(VARCHAR·INTEGER)만 써서 H2(PostgreSQL 모드)와 PostgreSQL에서 같은 스키마가 만들어집니다.
Neo4j로 갈 때는 `GraphStore`가 Cypher 결과를 `GraphView`로 바꾸도록 구현하면 됩니다.

## 테스트

| 테스트 | 확인 내용 |
|---|---|
| `GoldenQueryTest` | 질의 25개 × 기준(정답에 두 기준이 있으면 둘 다) = 38건: 해석(params)·결과(expected) 일치 |
| `DashboardTest` | `dashboard_expected.json`의 모든 지표(두 기준) + 인사이트(고민감도·중복 3쌍·집중·검토 대기·순환) |
| `ImportReproducesAnswerKeyTest` | reset sample → imports/samples → commit 결과가 정답의 Agent 50개(표준값·검토 표시)와 관계 326개 (type, source, target, status) 집합과 정확히 같음 |
| `FreeTextExtractionTest` | 자유 입력 T01~T08의 expected_action과 중복 관계 |
| `ReviewApiTest` | PATCH 확정·반려·`targetId` 변경(근거 합치기), 미등록 대상 처리 |
| `ApiContractTest` | 계약 JSON 필드, multipart 등록, 탐색·경로·순환, ask·extractions |
| `LlmClientsTest`, `FakeLlmPipelineTest` | LLM 요청 모양(외부 호출 없음), 가짜 LLM으로 표기→어휘 표준화·답변 검증·규칙 폴백 |
