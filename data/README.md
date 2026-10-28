# 엑시옴 더미 데이터

온톨로지 기반 Agent MAP(엑시옴)의 MVP를 개발·시연·평가하기 위한 가짜 데이터입니다. 팀 설계안(시스템 구성·질문 표·구조 정리안)을 기준으로 만들었습니다. 실제 개인정보는 없고, 사람 이름은 모두 가상입니다.



## 한눈에 보기

| 항목 | 값 |
|---|---|
| Agent | 53개 = 부서 제출 파일 50개 + 팀 설계안 예시 샘플 3개(A001 고객분석, A002 상품추천, A003 상담지원) |
| 표준 어휘 | 부서 10 · 업무 유형 8 · 플랫폼 17 · 데이터 30(민감도 상 9 / 중 10 / 하 11) |
| 관계 | 326개 — CONFIRMED 302 · CANDIDATE 10 · SAMPLE 14 |
| 관계 종류 | 데이터사용 85 · 사용 플랫폼 84 · 소속 53 · 업무 성격 53 · 저장 위치 28 · 결과사용 23 |
| 검토 필요 표시 | 42건(23개 Agent) |
| 미등록 대상 | 3건 (소송 관련 문서, 구매 관련 자료 일체, 마켓인텔 API) |
| AI 추출 특수 사례 | 원본 파일 속 6건 + 자유 입력 문장 8건 |
| 자연어 질의 정답 | 25개 (경영진·현업·개발·운영·데이터·보안) |

## 폴더 구성

```
v2/
├─ raw/                       일괄 등록 입력 — 부서가 제출한 원본 (형식·필드명·표기가 제각각)
│  ├─ 01_영업_마케팅.json
│  ├─ 02_개발_IT운영.yaml
│  ├─ 03_고객지원_구매.csv
│  ├─ 04_인사_재무.xlsx
│  └─ 05_법무_경영기획.docx    서술형 문장 — AI 관계 추출의 핵심 대상
├─ vocabulary/                표준 어휘 (SKOS: prefLabel·altLabel·broader·definition)
│  ├─ vocabulary.json         별칭 사전, 허용 관계 코드, 민감도 정의, 검토·미등록 표기 목록
│  └─ axiom_vocabulary.ttl    같은 내용을 온톨로지(Turtle)로
├─ seed/                      정답 DB 상태 — 팀 설계안의 DB 테이블(agents·data_resources·relationships) 그대로 적재 가능
│  ├─ agents.csv, data_resources.csv, departments.csv, platforms.csv, work_types.csv
│  ├─ relationships.csv       관계마다 상태·근거 문구·출처 파일·출처 위치·검토자
│  └─ pending_entities.csv    미등록 대상 검토 항목
├─ answer_key/                평가용 정답
│  ├─ extraction_answer_key.json   일괄 등록·AI 추출 정답 (필드별 표준값, 검토 사유, 관계, 특수 사례)
│  ├─ query_golden_set.json        자연어 질의 25개 — 질문 해석 결과(params)와 답(expected)
│  └─ dashboard_expected.json      대시보드 지표 기댓값
└─ tools/
   ├─ build_dataset.py        이 데이터를 만드는 스크립트 (표준 라이브러리만 사용)
   └─ verify_dataset.py       원본을 다시 읽어 정답과 맞춰 보는 검증 스크립트
```

## 데이터 규칙

**관계 방향은 "쓰는 쪽 → 제공하는 쪽"입니다.**
- 의존(dependencies)은 화살표 방향으로, 영향(impact)은 화살표 반대로 탐색합니다.
- 예: 상품추천 Agent → 고객분석 Agent (상품추천이 고객분석의 결과를 씀)

**허용 관계 코드 6개** (`vocabulary.json`의 `relation_types`)

| 코드 | 이름 | 방향 |
|---|---|---|
| USES_RESULT_OF | 결과사용 | Agent → Agent |
| ACCESSES_DATA | 데이터사용 | Agent → 데이터 |
| USES_PLATFORM | 사용 플랫폼 | Agent → 플랫폼 |
| BELONGS_TO | 소속 | Agent → 부서 |
| HAS_WORK_TYPE | 업무 성격 | Agent → 업무 유형 |
| STORED_IN | 저장 위치 | 데이터 → 플랫폼 |

**관계 상태 3가지**
- `SAMPLE`: 팀 설계안 예시의 첫 샘플(A001~A003과 그 관계). 검증 전입니다.
- `CANDIDATE`: 추출은 됐지만 표기가 모호해 사람 검토를 기다리는 관계. 사유가 `review_reason`에 있습니다.
- `CONFIRMED`: 근거가 분명해 검토를 마친 관계. 검토자·검토 일시가 있습니다.

**질의 정답은 두 기준으로 나뉩니다** (`status_scope`). 영향 조회에 미확정 관계를 넣을지는 팀이 정할 일이라 둘 다 제공합니다.
- `all_statuses`: SAMPLE·CANDIDATE·CONFIRMED 모두
- `confirmed_only`: CONFIRMED만. 이 기준에서는 소속이 확정되지 않은 Agent가 "(미확정)"으로 묶입니다. 설계안 샘플 3개도 여기에 포함됩니다.

## 기능별로 쓰는 방법

| 기능 (구조 정리안) | 입력 | 정답 |
|---|---|---|
| 일괄 등록 + 표준화 | `raw/` 5개 파일 | `extraction_answer_key.json`의 `agents` (필드별 표준값과 `review` 목록) |
| ① AI 관계 추출 | `raw/05_…docx` 문장, `free_text_cases` 8개 | `relationships`, `special_cases_in_files`, `free_text_cases` |
| 검토 처리 (CANDIDATE → CONFIRMED) | CANDIDATE 10건 | `relationships`의 `review_reason` |
| 의존·영향 탐색 (BFS) | `seed/relationships.csv` | `query_golden_set.json`의 Q02~Q06, Q15~Q19 |
| ② 질문 해석 | 질의 25개의 `question` | 각 질의의 `params` (기준 대상·방향·깊이·관계 종류) |
| ③ 답변 생성 | 탐색 결과 | 각 질의의 `expected`와 `answer_rule` |
| 경영진 대시보드 | `seed/` 전체 | `dashboard_expected.json`, Q10~Q14·Q20 |
| 별칭 사전 (SKOS) | `vocabulary.json`의 `altLabel` | 예: HR팀·인사 팀 → 인사팀, 세일즈포스·SFDC → Salesforce |

## 일부러 넣어 둔 시험 사례

**표기 흔들림 (표준화 시험)**
- 필드명이 파일마다 다릅니다(`agentName` / `name` / `agent_nm` / "Agent 이름" / 서술형).
- 날짜 형식은 "2026.03", "26/6/10", "Jan 2026", "작년 12월", "2025/Q4", "2026 상반기" 등이 섞여 있습니다.
- 사용량은 "1,200", "약 3천", "2.5k", "약 10만", "500~800", "많음", "매일" 등이 섞여 있습니다.
- 담당자는 직함이 붙어 있거나("김민준 책임"), 두 명이거나("박소영 매니저 / 이준호 선임"), 이름이 없습니다("법무팀 담당자").

**검토가 필요한 표기 (CANDIDATE)**
- "ERP", "ERP 구매모듈": 어떤 ERP인지 불명확 (SAP로 추정)
- "영업 데이터 전반", "경영 데이터 전반", "VOC 데이터", "인건비 데이터", "각 부서 고객 데이터": 어떤 데이터인지 확인 필요
- 소속 부서 누락(장비 재고 관리 Agent), "기획팀"(경영기획팀과 같은지 확인)
- "분석 Agent": 이름이 여러 Agent와 겹침 → 후보를 보여주고 고르게 해야 함

**AI 추출이 어려운 사례** (`special_cases_in_files`)

| 사례 | 위치 | 기대 동작 |
|---|---|---|
| 자기 참조 | YAML 테스트 코드 생성 Agent | 저장 거부 |
| 같은 관계를 두 파일이 진술, 그중 하나는 제공하는 쪽이 주어 | CSV 구매 계약 검토 + Word 계약서 검토 | 관계 하나로 합치고 근거 2건 유지 |
| 모호한 이름 | Word 소송 자료 요약 Agent | 후보 제시 후 선택 |
| 미등록 외부 서비스 | Word 시장 동향 리서치 Agent | 미등록 대상 검토 항목 |
| 부정문 | Word 경영 현황 요약 Agent ("사용하지 않습니다") | 관계 만들지 않음 |
| 미래 계획 | Excel 월 결산 리포트 Agent 비고 ("연동 예정") | 관계 만들지 않음 |

**그래프 시연용 구조**
- 최장 연결 4단계: 이사회 자료 작성 → 경영 현황 요약 → 영업 주간 리포트 → 견적서 자동작성 → 단가 조회
- 부서를 넘는 연결이 여러 개 있습니다(구매팀 발주서 생성 → 영업팀 단가 조회 등).
- 순환 1개: 상담 응대 챗봇 ↔ 상담 내용 요약. 탐색이 무한 반복하지 않는지 확인하는 용도입니다.
- 인사이트: 고민감도 데이터 + 플랫폼 3개 이상인 Agent 3개, 중복 개발 후보 3쌍, SAP·SharePoint 집중

## 평가에 쓰는 지표

| 대상 | 지표 |
|---|---|
| 일괄 등록 | 필드별 표준화 정확도, 검토 필요 표시 재현율(42건을 모두 잡는지) |
| AI 관계 추출 | (출발·관계·도착) 기준 정밀도·재현율·F1, 근거 위치 일치율, 특수 사례 통과율(6 + 8건) |
| 질문 해석 | `params` 일치율 (Parameter Accuracy) |
| 답변 | 기준별 정답 집합 일치율, "발견되지 않음"·미등록·모호한 질문 처리 |

## 다시 만들기·검증

```
python3 data/tools/build_dataset.py
python3 data/tools/verify_dataset.py
```

`build_dataset.py` 위쪽의 어휘·Agent 목록을 고치고 다시 실행하면 원본 5개 파일과 정답이 함께 갱신됩니다. 고친 뒤에는 `verify_dataset.py`로 원본·정답·질의가 서로 맞는지 확인하세요.
