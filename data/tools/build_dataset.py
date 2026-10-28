#!/usr/bin/env python3
"""엑시옴(온톨로지 기반 Agent MAP) 더미 데이터 v2 생성기.

표준 라이브러리만 씁니다. 실행: python3 Data/v2/tools/build_dataset.py
결과물은 이 스크립트의 상위 폴더(Data/v2)에 다시 씁니다.

설계 기준
- 관계 방향: 쓰는 쪽 → 제공하는 쪽
- 관계 상태: SAMPLE(팀 설계안의 첫 샘플) / CANDIDATE(검토 대기) / CONFIRMED(검토 완료)
- 모든 관계에 근거 문구와 출처 위치(파일·위치)를 붙입니다.
"""
import csv
import io
import json
import re
import zipfile
from collections import Counter, defaultdict, deque
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "raw"
VOCAB = ROOT / "vocabulary"
SEED = ROOT / "seed"
KEY = ROOT / "answer_key"

REVIEWERS = ["주관팀 검토자 A", "주관팀 검토자 B"]
REVIEW_DATES = ["2026-10-23", "2026-10-24"]

# ---------------------------------------------------------------- vocabulary
# SKOS 형태: prefLabel(표준 이름) / altLabel(별칭) / broader(상위 분류) / definition(설명)
DEPARTMENTS = [
    ("DEP01", "영업팀", "영업본부", ["영업본부/기업영업팀", "기업영업팀"], "고객 영업과 견적·계약 갱신을 맡는 팀"),
    ("DEP02", "마케팅팀", "마케팅본부", ["마케팅"], "캠페인 기획과 고객 세그먼트 분석을 맡는 팀"),
    ("DEP03", "고객지원팀", "고객본부", ["CS팀", "고객센터"], "고객 문의 응대와 VOC 관리를 맡는 팀"),
    ("DEP04", "개발팀", "개발본부", ["개발본부 플랫폼개발팀", "플랫폼개발팀"], "사내 서비스 개발과 코드 품질을 맡는 팀"),
    ("DEP05", "IT운영팀", "개발본부", ["IT운영"], "계정·장비·클라우드·보안 운영을 맡는 팀"),
    ("DEP06", "인사팀", "경영지원본부", ["HR팀", "인사 팀"], "채용·교육·인사 정보를 맡는 팀"),
    ("DEP07", "재무팀", "경영지원본부", ["재무"], "결산·경비·급여·세무를 맡는 팀"),
    ("DEP08", "구매팀", "경영지원본부", ["구매"], "발주·공급업체·재고 보충을 맡는 팀"),
    ("DEP09", "법무팀", "경영지원본부", ["법무"], "계약 검토·법령 대응·개인정보 점검을 맡는 팀"),
    ("DEP10", "경영기획팀", "전략본부", ["경영기획"], "경영 현황 보고와 예산 편성을 맡는 팀"),
]
WORK_TYPES = [
    ("WT1", "문서 작성", ["문서 생성", "보고서 작성"], "정해진 양식의 업무 문서(견적서·보고서·계약서 초안 등)를 만든다"),
    ("WT2", "콘텐츠 생성", ["콘텐츠 제작", "문구 생성"], "독자에게 보낼 글(캠페인 문구·공지·보도자료)을 만든다"),
    ("WT3", "데이터 분석", ["분석"], "데이터를 집계·분석해 점수·분류·예측·인사이트를 낸다"),
    ("WT4", "검토·심사", ["검토", "심사"], "문서나 요청이 기준에 맞는지 검토하고 판단을 제안한다"),
    ("WT5", "고객 응대", ["상담"], "고객과 직접 대화하며 문의에 답한다"),
    ("WT6", "모니터링", ["알림", "감시"], "상태를 계속 지켜보다가 이상이나 기한을 알린다"),
    ("WT7", "검색·요약", ["요약", "Q&A"], "문서나 기록을 찾아 요약하거나 질문에 답한다"),
    ("WT8", "자동화 처리", ["업무 자동화", "자동화"], "반복 업무를 규칙에 따라 자동으로 처리한다"),
]
PLATFORMS = [
    ("P01", "Salesforce", "CRM", ["SFDC", "세일즈포스"]),
    ("P02", "HubSpot", "마케팅 자동화", ["허브스팟"]),
    ("P03", "SAP", "ERP", ["SAP ERP", "SAP HR", "에스에이피"]),
    ("P04", "Workday", "HR 시스템", ["워크데이", "워크데이 채용"]),
    ("P05", "Slack", "협업 메신저", ["슬랙"]),
    ("P06", "Microsoft Teams", "협업 메신저", ["MS Teams", "Teams", "팀즈"]),
    ("P07", "SharePoint", "문서 관리", ["쉐어포인트"]),
    ("P08", "Jira", "개발 협업", ["JIRA", "지라", "JIRA Service Management"]),
    ("P09", "Confluence", "문서 관리", ["컨플루언스"]),
    ("P10", "GitHub", "개발 협업", ["깃헙"]),
    ("P11", "Zendesk", "고객지원", ["젠데스크"]),
    ("P12", "사내 메일", "메일", ["사내메일", "그룹웨어 메일", "아웃룩", "메일"]),
    ("P13", "Google Analytics", "웹 분석", ["GA4"]),
    ("P14", "DocuSign", "전자서명", ["도큐사인"]),
    ("P15", "AWS", "클라우드", ["Amazon Web Services"]),
    ("P16", "Splunk", "보안 관제", ["스플렁크"]),
    ("P17", "Active Directory", "계정 관리", ["AD"]),
]
SENSITIVITY = {
    "상": "개인정보·급여·계약 원문·보안 정보처럼 유출되면 법적·재무적 피해가 큰 데이터",
    "중": "사내 업무 데이터로 외부에 공개하면 안 되는 데이터",
    "하": "공개됐거나 사내 누구나 볼 수 있는 자료",
}
# (id, 표준 이름, 민감도, 분류, 저장 플랫폼, 별칭)
DATA = [
    ("D001", "CRM 고객정보", "상", "고객", "P01", ["고객정보 DB", "고객DB", "고객 DB", "고객 데이터베이스", "세일즈포스 고객 데이터", "CRM 고객테이블"]),
    ("D002", "상품 정보", "중", "영업", "P03", ["상품정보 DB", "상품 마스터", "단가표", "단가표 엑셀"]),
    ("D003", "과거 견적 이력", "중", "영업", "P01", ["견적 이력"]),
    ("D004", "재고 현황", "하", "구매", "P03", ["재고 데이터", "장비 재고"]),
    ("D005", "매출 실적", "중", "재무", "P03", ["매출데이터", "매출 실적표"]),
    ("D006", "계약서 원문", "상", "법무", "P07", ["계약서", "계약 문서", "구매 계약서"]),
    ("D007", "표준 계약서 양식", "하", "법무", "P07", ["표준 계약 템플릿", "계약 템플릿"]),
    ("D008", "캠페인 성과 데이터", "하", "마케팅", "P02", ["캠페인 결과"]),
    ("D009", "웹 방문 로그", "중", "마케팅", "P13", ["방문 로그"]),
    ("D010", "브랜드 가이드라인", "하", "마케팅", "P07", ["브랜드 가이드"]),
    ("D011", "소스코드 저장소", "중", "개발·IT", "P10", ["소스 레포", "코드베이스", "커밋 이력"]),
    ("D012", "장애 로그", "중", "개발·IT", "P16", ["에러 로그"]),
    ("D013", "사내 기술문서", "하", "개발·IT", "P09", ["위키 문서"]),
    ("D014", "임직원 인사정보", "상", "인사", "P04", ["HR 마스터", "인사DB", "인사 정보", "임직원 정보"]),
    ("D015", "시스템 접근 권한 목록", "상", "보안", "P17", ["권한 목록", "접근 권한 목록"]),
    ("D016", "IT 문의 티켓", "중", "개발·IT", "P08", ["헬프데스크 티켓"]),
    ("D017", "클라우드 비용 데이터", "하", "개발·IT", "P15", ["AWS 빌링 데이터"]),
    ("D018", "보안 로그", "상", "보안", "P16", ["보안 이벤트 로그"]),
    ("D019", "지원자 정보", "상", "인사", "P04", ["이력서", "지원자 이력서", "지원자 연락처"]),
    ("D020", "직무기술서", "하", "인사", "P04", ["JD"]),
    ("D021", "사내 규정집", "하", "인사", "P07", ["사내 규정", "취업규칙 등 규정", "경비 규정"]),
    ("D022", "급여 정보", "상", "인사", "P03", ["급여 DB", "급여데이터"]),
    ("D023", "회계 전표", "중", "재무", "P03", ["전표 데이터", "재무 전표"]),
    ("D024", "거래처 정보", "중", "구매", "P03", ["거래처 DB", "공급업체 목록", "협력사 목록"]),
    ("D025", "법령 자료", "하", "외부", None, ["관련 법령", "법제처 법령 데이터", "세법 자료"]),
    ("D026", "FAQ 문서", "하", "고객", "P11", ["FAQ"]),
    ("D027", "고객 상담 이력", "상", "고객", "P11", ["상담 내역", "상담 로그", "상담 이력"]),
    ("D028", "구매 발주 이력", "중", "구매", "P03", ["PO 이력", "발주 이력"]),
    ("D029", "외부 시장 자료", "하", "외부", None, ["외부 리서치 자료"]),
    ("D030", "주문·결제 내역", "상", "고객", "P03", ["주문/결제 데이터", "주문 내역"]),
]
STATUS_VOCAB = {"운영": ["운영 중", "운영중"], "파일럿": ["Pilot", "PoC"], "중단": ["중지"]}
RELATION_TYPES = [
    ("USES_RESULT_OF", "결과사용", "Agent", "Agent", "주어 Agent가 대상 Agent의 출력 결과를 입력으로 쓴다"),
    ("ACCESSES_DATA", "데이터사용", "Agent", "DataAsset", "주어 Agent가 대상 데이터를 읽거나 쓴다"),
    ("USES_PLATFORM", "사용 플랫폼", "Agent", "Platform", "주어 Agent가 대상 플랫폼에 연동되어 동작한다"),
    ("BELONGS_TO", "소속", "Agent", "Department", "주어 Agent를 운영·책임지는 부서"),
    ("HAS_WORK_TYPE", "업무 성격", "Agent", "WorkType", "주어 Agent의 주된 업무 유형"),
    ("STORED_IN", "저장 위치", "DataAsset", "Platform", "주어 데이터가 저장·관리되는 플랫폼"),
]
REL_LABEL = {r[0]: r[1] for r in RELATION_TYPES}
REL_ORDER = {r[0]: i for i, r in enumerate(RELATION_TYPES)}

# 별칭으로 바로 정해지지 않아 사람이 확인해야 하는 표기 → (추정 대상, 사유)
REVIEW = {
    "platform": {
        "ERP": ("P03", "어떤 ERP인지 불명확 (SAP로 추정)"),
        "ERP 구매모듈": ("P03", "어떤 ERP인지 불명확 (SAP 구매 모듈로 추정)"),
    },
    "data": {
        "영업 데이터 전반": ("D005", "범위가 넓어 어떤 데이터인지 불명확 (매출 실적으로 추정)"),
        "경영 데이터 전반": ("D005", "범위가 넓어 어떤 데이터인지 불명확 (매출 실적으로 추정)"),
        "VOC 데이터": ("D027", "VOC 분류 결과가 고객 상담 이력과 같은 데이터인지 확인 필요"),
        "인건비 데이터": ("D022", "급여 정보에서 나온 집계인지 확인 필요 (급여 정보로 추정)"),
        "각 부서 고객 데이터": ("D001", "범위가 넓어 CRM 고객정보만 뜻하는지 확인 필요"),
    },
    "department": {
        "기획팀": ("DEP10", "경영기획팀과 같은 부서인지 확인 필요"),
    },
}
# 표준 목록에 없어 '미등록 대상 검토 항목'으로 보내야 하는 표기
UNREGISTERED = {
    "data": {
        "소송 관련 문서": "표준 데이터 목록에 없음 — 신규 데이터로 등록할지 확인",
        "구매 관련 자료 일체": "어떤 데이터인지 특정할 수 없음 — 제출 부서에 확인",
    },
    "agent": {
        "마켓인텔 API": "등록되지 않은 외부 서비스 — 외부 대상으로 등록할지 확인",
    },
}
# 이름이 여러 Agent와 부분 일치하는 표기 → 후보 제시 후 사용자가 고른 정답
AMBIGUOUS_AGENT = {
    "분석 Agent": {"resolved": "A042", "reason": "'분석 Agent'와 부분 일치하는 Agent가 여러 개 — 후보를 보여주고 사용자 선택 필요 (문맥상 '거래처'라 거래처 신용 분석 Agent가 유력)"},
}

# ---------------------------------------------------------------- agents
# 팀 설계안 예시(저장되는 Agent 관계) — 일괄 등록 전에 이미 들어 있는 첫 샘플(SAMPLE)
SAMPLE_AGENTS = [
    dict(id="A001", name="고객분석 Agent", dept="DEP02", owners=["최유나"], wt="WT3",
         desc="구매 이력과 행동 데이터를 분석해 고객 세그먼트를 만든다", platforms=["P01", "P02"],
         data=["D001"], calls=[], start="2025-12", usage=900, status="운영"),
    dict(id="A002", name="상품추천 Agent", dept="DEP01", owners=["김민준"], wt="WT3",
         desc="고객 분석 결과와 상품 정보를 이용해 고객별 추천 상품 목록을 만든다", platforms=["P01"],
         data=["D002"], calls=["A001"], start="2026-01", usage=1500, status="운영"),
    dict(id="A003", name="상담지원 Agent", dept="DEP03", owners=["노지민"], wt="WT5",
         desc="상담 중 고객에게 맞는 상품을 안내하도록 상담원에게 추천 문구를 준다", platforms=["P11"],
         data=[], calls=["A002"], start="2026-02", usage=2500, status="운영"),
]

# 부서별 제출 파일 원본 값(일부러 표기가 흔들림)
JSON_AGENTS = [
    dict(id="A004", name="견적서 자동작성 Agent", dept="영업본부/기업영업팀", owner="김민준 책임", type="문서 작성",
         desc="고객 요청 메일을 받아 표준 견적서 초안을 생성", platforms=["세일즈포스", "사내메일"],
         data=["세일즈포스 고객 데이터", "단가표 엑셀", "과거 견적 이력"], calls=["단가 조회 Agent"],
         date="2026.03", usage="1,200", status="운영"),
    dict(id="A005", name="단가 조회 Agent", dept="영업팀", owner="김민준", type="검색·요약",
         desc="제품 코드로 최신 단가와 재고를 조회해 응답", platforms=["ERP"], data=["단가표", "재고 데이터"],
         calls=[], date="2026-02-15", usage="약 3천", status="운영"),
    dict(id="A006", name="영업 주간 리포트 Agent", dept="영업팀", owner=None, type="데이터 분석",
         desc="주간 영업 실적을 집계해 보고서 초안을 작성하고 팀 채널에 공유", platforms=["SFDC", "슬랙"], data=["영업 데이터 전반"],
         calls=["견적서 자동작성 Agent"], date="4월 초", usage="약 50", status="운영"),
    dict(id="A007", name="계약 갱신 알림 Agent", dept="영업팀", owner="박지훈", type="모니터링",
         desc="계약 만료 60일 전 담당 영업에게 알림", platforms=["Salesforce", "사내 메일"],
         data=["CRM 고객테이블", "계약 문서"], calls=[], date="26/6/10", usage="300", status="운영"),
    dict(id="A008", name="리드 스코어링 Agent", dept="영업팀", owner="이서연 매니저", type="데이터 분석",
         desc="잠재 고객의 구매 가능성을 점수화", platforms=["Salesforce", "허브스팟"], data=["고객DB", "웹 방문 로그"],
         calls=[], date="2025/11", usage="500~800", status="파일럿"),
    dict(id="A009", name="캠페인 문구 생성 Agent", dept="마케팅팀", owner="최유나", type="콘텐츠 생성",
         desc="고객 세그먼트별 이메일 캠페인 문구 생성", platforms=["HubSpot"],
         data=["고객 데이터베이스", "캠페인 성과 데이터"], calls=["고객 분석 Agent"], date="Jan 2026", usage="2000",
         status="운영"),
    dict(id="A010", name="타깃 고객 추출 Agent", dept="마케팅팀", owner="최유나", type="데이터 분석",
         desc="고객 세그먼트와 구매 이력으로 캠페인 대상 고객 목록 추출", platforms=["세일즈포스", "HubSpot"],
         data=["고객DB", "매출데이터"], calls=["고객분석 Agent"], date="2026-07-01", usage="400", status="운영"),
    dict(id="A011", name="SNS 반응 분석 Agent", dept="마케팅팀", owner="정하늘", type="데이터 분석",
         desc="캠페인 이후 SNS와 웹 반응을 분석해 요약", platforms=["GA4", "슬랙"], data=["웹 방문 로그", "캠페인 결과"],
         calls=[], date="2026년 5월", usage="1.5천", status="운영"),
    dict(id="A012", name="마케팅 성과 리포트 Agent", dept="마케팅", owner=None, type="문서 작성",
         desc="월간 마케팅 성과를 집계해 보고서 초안 작성", platforms=["Google Analytics", "쉐어포인트"],
         data=["캠페인 성과 데이터", "웹 방문 로그"], calls=["SNS 반응 분석 Agent"], date=None, usage="많음",
         status="파일럿"),
    dict(id="A013", name="보도자료 초안 Agent", dept="마케팅팀", owner="정하늘", type="콘텐츠 생성",
         desc="신제품 정보를 받아 보도자료 초안 작성", platforms=["SharePoint", "팀즈"], data=["브랜드 가이드"],
         calls=[], date="작년 12월", usage="30", status="중지"),
]
YAML_AGENTS = [
    dict(id="A014", name="코드 리뷰 Agent", dept="개발팀", owner="한도윤", type="검토·심사",
         desc="Pull Request의 변경 사항을 검토하고 코멘트 작성", platforms=["깃헙"], data=["소스 레포"], calls=[],
         date="2026-02", usage="'5000'", status="운영"),
    dict(id="A015", name="장애 원인 분석 Agent", dept="개발본부 플랫폼개발팀", owner="오세훈", type="데이터 분석",
         desc="장애 발생 시 에러 로그와 최근 배포 이력을 분석해 원인 후보 제시", platforms=["지라", "Slack"],
         data=["에러 로그", "소스코드 저장소"], calls=["사내 위키 검색 Agent"], date="2026/03/10", usage="600",
         status="운영 중"),
    dict(id="A016", name="사내 위키 검색 Agent", dept="개발팀", owner="한도윤", type="검색·요약",
         desc="사내 기술 문서를 검색해 직원 질문에 답변", platforms=["컨플루언스"], data=["위키 문서"], calls=[],
         date="'2025.09'", usage="8,000", status="운영"),
    dict(id="A017", name="테스트 코드 생성 Agent", dept="개발팀", owner=None, type="자동화 처리",
         desc="변경된 코드에 대한 단위 테스트 초안 생성", platforms=["GitHub"], data=["코드베이스"],
         calls=["테스트 코드 생성 Agent"], date="2026 상반기", usage=None, status="파일럿"),
    dict(id="A018", name="릴리스 노트 작성 Agent", dept="개발팀", owner="오세훈", type="문서 작성",
         desc="배포 단위로 변경 이력을 정리해 릴리스 노트 작성", platforms=["JIRA", "Confluence"], data=["커밋 이력"],
         calls=["코드 리뷰 Agent"], date="Aug 2026", usage="'40'", status="운영"),
    dict(id="A019", name="계정 권한 점검 Agent", dept="IT운영팀", owner="윤지호", type="모니터링",
         desc="퇴사자와 부서 이동자의 시스템 권한 잔존 여부 점검", platforms=["AD", "워크데이"],
         data=["HR 마스터", "권한 목록"], calls=[], date="26/4/15", usage="'200'", status="운영"),
    dict(id="A020", name="헬프데스크 티켓 분류 Agent", dept="IT운영팀", owner="윤지호", type="자동화 처리",
         desc="IT 문의 티켓을 유형별로 분류하고 담당자 배정", platforms=["JIRA Service Management", "MS Teams"],
         data=["헬프데스크 티켓"], calls=["사내 위키 검색 Agent"], date="'2026-01-05'", usage="2.5k", status="운영"),
    dict(id="A021", name="클라우드 비용 모니터링 Agent", dept="IT운영팀", owner="강민서", type="모니터링",
         desc="클라우드 사용량과 비용 이상 증가를 감지해 알림", platforms=["Amazon Web Services"],
         data=["AWS 빌링 데이터"], calls=[], date="'2026.6'", usage="'720'", status="운영"),
    dict(id="A022", name="보안 로그 탐지 Agent", dept="IT운영팀", owner="강민서", type="모니터링",
         desc="보안 로그에서 비정상 접근 패턴 탐지", platforms=["스플렁크", "AD"],
         data=["보안 이벤트 로그", "접근 권한 목록"], calls=[], date="2025/Q4", usage="약 10만", status="운영"),
    dict(id="A023", name="장비 재고 관리 Agent", dept="", owner="박서준", type="자동화 처리",
         desc="노트북 등 IT 장비 재고를 확인하고 부족 시 구매 요청 생성", platforms=["SAP ERP"],
         data=["장비 재고", "구매 발주 이력"], calls=[], date="'2026-09-01'", usage="'100'", status="파일럿",
         dept_hint=("DEP05", "소속 부서 누락 — 제출 부서(개발본부·IT운영팀)와 업무로 보아 IT운영팀으로 추정")),
]
CSV_AGENTS = [
    dict(id="A024", name="상담 응대 챗봇 Agent", dept="고객지원팀", owner="노지민", type="고객 응대",
         desc="고객 문의에 1차 응대하고 해결 못한 건은 상담원에게 이관", platforms=["젠데스크"], data=["FAQ", "상담 내역"],
         calls=["VOC 분류 Agent", "상담 내용 요약 Agent"], date="2025.08", usage="12,000", status="운영"),
    dict(id="A025", name="상담 내용 요약 Agent", dept="CS팀", owner="노지민", type="검색·요약",
         desc="상담 종료 후 대화 내용을 요약해 상담 이력에 기록하고 다음 상담에 맥락 제공",
         platforms=["Zendesk", "Salesforce", "슬랙"], data=["상담 로그", "고객 DB"], calls=["상담 응대 챗봇 Agent"],
         date="2026/02/10", usage="3500", status="운영"),
    dict(id="A026", name="VOC 분류 Agent", dept="고객지원팀", owner="서예린", type="데이터 분석",
         desc="고객 불만과 요청을 주제별로 분류해 주간 집계", platforms=["Zendesk", "슬랙"], data=["VOC 데이터"],
         calls=[], date="2026-03-02", usage="약 2천", status="운영"),
    dict(id="A027", name="환불 심사 Agent", dept="고객지원팀", owner="", type="검토·심사",
         desc="환불 요청 건의 주문 내역과 정책을 대조해 승인 여부 제안", platforms=["Zendesk", "SAP"],
         data=["상담 이력", "주문/결제 데이터"], calls=[], date="26/9/1", usage="400", status="Pilot"),
    dict(id="A028", name="FAQ 자동 갱신 Agent", dept="고객지원팀", owner="서예린", type="콘텐츠 생성",
         desc="자주 들어오는 문의를 바탕으로 FAQ 문서 갱신안 작성", platforms=["Confluence", "Zendesk"],
         data=["FAQ", "상담 내역"], calls=["VOC 분류 Agent"], date="2026.05", usage="30", status="운영"),
    dict(id="A029", name="발주서 생성 Agent", dept="구매팀", owner="문성호", type="문서 작성",
         desc="구매 요청을 받아 발주서를 생성하고 승인 요청", platforms=["ERP 구매모듈"], data=["PO 이력", "거래처 DB"],
         calls=["단가 조회 Agent"], date="2026-01-15", usage="900", status="운영"),
    dict(id="A030", name="견적 비교 Agent", dept="구매팀", owner="문성호", type="데이터 분석",
         desc="여러 공급업체 견적을 비교해 추천안 작성", platforms=["SAP", "메일"], data=["공급업체 목록", "구매 발주 이력"],
         calls=[], date="Feb 2026", usage="250", status="운영"),
    dict(id="A031", name="구매 계약 검토 Agent", dept="구매팀", owner="배수아", type="검토·심사",
         desc="구매 계약서에서 납기·대금 조건 등 불리한 조항을 찾아 표시하고 법무 검토 요청", platforms=["도큐사인"],
         data=["구매 계약서", "거래처 정보"], calls=["계약서 검토 Agent"], date="2026.06.15", usage="80", status="운영"),
    dict(id="A032", name="재고 보충 알림 Agent", dept="구매", owner="배수아", type="모니터링",
         desc="안전 재고 이하 품목을 감지해 구매 담당자에게 알림", platforms=["SAP"], data=["재고 데이터", "발주 이력"],
         calls=[], date="2025/Q3", usage="매일", status="운영"),
    dict(id="A033", name="협력사 평가 Agent", dept="구매팀", owner="", type="데이터 분석",
         desc="납기 준수율과 품질 이슈로 협력사 분기 평가표 작성", platforms=["SharePoint"],
         data=["협력사 목록", "구매 관련 자료 일체"], calls=[], date="", usage="", status="파일럿"),
]
XLSX_AGENTS = [
    dict(id="A034", name="채용 서류 1차 검토 Agent", dept="HR팀", owner="박소영 매니저 / 이준호 선임", type="검토·심사",
         desc="지원서를 직무 요건과 비교해 요약과 적합도 점수 제공", platforms=["워크데이 채용"], data=["이력서", "JD"],
         calls=[], date="2026.05", usage="300", status="파일럿", note=""),
    dict(id="A035", name="면접 일정 조율 Agent", dept="인사 팀", owner="이준호", type="자동화 처리",
         desc="면접관 일정과 지원자 가능 시간을 맞춰 일정 확정 메일 발송", platforms=["MS Teams", "아웃룩"],
         data=["지원자 연락처"], calls=[], date="26/5/20", usage="150", status="파일럿", note=""),
    dict(id="A036", name="사내 규정 Q&A Agent", dept="인사팀", owner="김태희", type="검색·요약",
         desc="사내 규정 문서를 검색해 직원 질문에 답변", platforms=["쉐어포인트", "Teams"], data=["취업규칙 등 규정"],
         calls=[], date="2025-11-03", usage="4,000", status="운영", note=""),
    dict(id="A037", name="퇴사 예측 분석 Agent", dept="인사팀", owner="", type="데이터 분석",
         desc="근태, 평가, 급여 변화를 바탕으로 퇴사 위험 직원 예측", platforms=["Workday"], data=["인사DB", "급여데이터"],
         calls=[], date="2026년 2월", usage="", status="파일럿", note=""),
    dict(id="A038", name="교육 이수 관리 Agent", dept="인사팀", owner="김태희", type="모니터링",
         desc="필수 교육 미이수자를 찾아 안내 메일 발송", platforms=["Workday", "사내 메일"], data=["HR 마스터"],
         calls=[], date="2026/01", usage="약 600", status="운영", note=""),
    dict(id="A039", name="경비 정산 검토 Agent", dept="재무팀", owner="송재원", type="검토·심사",
         desc="경비 청구 내역이 규정에 맞는지 검토하고 이상 건 표시", platforms=["에스에이피"], data=["전표 데이터", "경비 규정"],
         calls=[], date="2026.04.01", usage="2,200", status="운영", note=""),
    dict(id="A040", name="월 결산 리포트 Agent", dept="재무팀", owner="송재원", type="문서 작성",
         desc="월말 결산 수치를 정리해 보고서 초안 작성", platforms=["SAP", "SharePoint"], data=["회계 전표", "매출 실적표"],
         calls=["경비 정산 검토 Agent"], date="Mar 2026", usage="20", status="운영",
         note="향후 예산 편성 보조 Agent와 연동 예정(미구현)"),
    dict(id="A041", name="급여 이상 탐지 Agent", dept="재무팀", owner="임하은", type="모니터링",
         desc="급여 지급 데이터에서 이상 금액과 중복 지급 탐지", platforms=["SAP HR", "Workday", "사내 메일"],
         data=["급여 DB", "인사 정보"], calls=[], date="2026-06-30", usage="1200", status="운영", note=""),
    dict(id="A042", name="거래처 신용 분석 Agent", dept="재무팀", owner="임하은", type="데이터 분석",
         desc="거래처의 거래 실적과 결제 이력으로 신용 등급 산정", platforms=["SAP"], data=["협력사 목록", "매출 실적"],
         calls=[], date="작년 말", usage="100~150", status="파일럿", note=""),
    dict(id="A043", name="세무 신고 보조 Agent", dept="재무", owner="", type="문서 작성",
         desc="부가세 신고에 필요한 자료를 취합하고 신고서 초안 작성", platforms=["SharePoint"], data=["회계 전표", "세법 자료"],
         calls=["월 결산 리포트 Agent"], date="2026.07", usage="10", status="운영", note=""),
]
# Word 서술형: 문장 목록 + 각 필드 원문이 들어 있는 문장 번호(1부터)
DOCX_AGENTS = [
    dict(id="A044", name="계약서 검토 Agent", sentences=[
        "법무팀 조현우 변호사가 관리하는 Agent입니다.",
        "계약서를 올리면 독소 조항과 표준 대비 바뀐 부분을 찾아 표시해 주며, 업무 성격은 검토·심사입니다.",
        "도큐사인과 쉐어포인트에 연동되어 있고, 계약서와 관련 법령을 참고합니다.",
        "참고로 구매팀의 구매 계약 검토 Agent가 이 Agent의 검토 결과를 받아 씁니다.",
        "2025년 12월부터 운영 중이며 한 달에 150건 정도 처리합니다."],
        dept=("법무팀", 1), owner=("조현우 변호사", 1), type=("검토·심사", 2),
        desc="계약서의 독소 조항과 표준 대비 바뀐 부분을 찾아 표시",
        platforms=[("도큐사인", 3), ("쉐어포인트", 3)], data=[("계약서", 3), ("관련 법령", 3)], calls=[],
        provided_to=[("구매 계약 검토 Agent", 4)], date=("2025년 12월", 5), usage=("150건 정도", 5), status=("운영 중", 5)),
    dict(id="A045", name="법령 개정 모니터링 Agent", sentences=[
        "같은 법무팀에서 조현우 변호사가 함께 맡고 있습니다.",
        "업무 관련 법령의 개정 사항을 매일 확인해 요약 메일을 보내는 모니터링 Agent입니다.",
        "사내 메일로 결과를 보내며, 법제처 법령 데이터를 참고합니다.",
        "2026년 3월 중순에 도입했고, 하루 한 번 실행됩니다.",
        "현재 운영 중입니다."],
        dept=("법무팀", 1), owner=("조현우 변호사", 1), type=("모니터링", 2),
        desc="업무 관련 법령의 개정 사항을 매일 확인해 요약 메일 발송",
        platforms=[("사내 메일", 3)], data=[("법제처 법령 데이터", 3)], calls=[],
        date=("2026년 3월 중순", 4), usage=("하루 한 번", 4), status=("운영 중", 5)),
    dict(id="A046", name="개인정보 처리 점검 Agent", sentences=[
        "법무팀 신다은 님이 담당합니다.",
        "각 부서의 개인정보 처리 현황이 내부 지침에 맞는지 점검하는 검토·심사 Agent입니다.",
        "SharePoint, Workday, Salesforce에 연결되어 각 부서 고객 데이터와 임직원 정보를 확인합니다.",
        "2026.08부터 파일럿으로 쓰고 있으며, 사용량은 아직 집계하지 않았습니다."],
        dept=("법무팀", 1), owner=("신다은", 1), type=("검토·심사", 2),
        desc="각 부서의 개인정보 처리 현황이 내부 지침에 맞는지 점검",
        platforms=[("SharePoint", 3), ("Workday", 3), ("Salesforce", 3)],
        data=[("각 부서 고객 데이터", 3), ("임직원 정보", 3)], calls=[],
        date=("2026.08", 4), usage=("", 4), status=("파일럿", 4)),
    dict(id="A047", name="소송 자료 요약 Agent", sentences=[
        "법무팀 담당자가 관리합니다.",
        "소송 관련 문서와 계약서를 검색해 쟁점을 요약하는 검색·요약 Agent입니다.",
        "쉐어포인트에 연동되어 있습니다.",
        "거래처 분쟁 건은 분석 Agent가 정리한 거래처 결과를 함께 참고합니다.",
        "월 50건 내외로 쓰이며 운영 중입니다."],
        dept=("법무팀", 1), owner=("법무팀 담당자", 1), type=("검색·요약", 2),
        desc="소송 관련 문서와 계약서를 검색해 쟁점 요약",
        platforms=[("쉐어포인트", 3)], data=[("소송 관련 문서", 2), ("계약서", 2)], calls=[("분석 Agent", 4)],
        date=("", None), usage=("50건 내외", 5), status=("운영 중", 5)),
    dict(id="A048", name="NDA 초안 작성 Agent", sentences=[
        "신다은 님이 관리하는 법무팀 Agent입니다.",
        "거래 상대방 정보를 받아 비밀유지계약서 초안을 만드는 문서 작성 Agent입니다.",
        "DocuSign과 연동되며 표준 계약 템플릿을 바탕으로 작성합니다.",
        "초안이 나오면 계약서 검토 Agent를 호출해 검토 결과를 함께 붙입니다.",
        "2026-04-01부터 운영 중이고 월 60건 정도입니다."],
        dept=("법무팀", 1), owner=("신다은", 1), type=("문서 작성", 2),
        desc="거래 상대방 정보를 받아 비밀유지계약서 초안 작성",
        platforms=[("DocuSign", 3)], data=[("표준 계약 템플릿", 3)], calls=[("계약서 검토 Agent", 4)],
        date=("2026-04-01", 5), usage=("60건 정도", 5), status=("운영 중", 5)),
    dict(id="A049", name="경영 현황 요약 Agent", sentences=[
        "경영기획팀 한지수 님이 관리합니다.",
        "각 부서 리포트를 모아 경영진용 주간 요약을 만드는 검색·요약 Agent입니다.",
        "SharePoint와 팀즈에 연동되어 있고, 매출 실적과 재무 전표를 참고합니다.",
        "월 결산 리포트 Agent와 영업 주간 리포트 Agent의 결과를 가져와 요약에 넣습니다.",
        "고객 개인정보가 담긴 CRM 고객정보는 사용하지 않습니다.",
        "2026.05.01부터 운영 중이며 월 100건 정도 사용합니다."],
        dept=("경영기획팀", 1), owner=("한지수", 1), type=("검색·요약", 2),
        desc="각 부서 리포트를 모아 경영진용 주간 요약 작성",
        platforms=[("SharePoint", 3), ("팀즈", 3)], data=[("매출 실적", 3), ("재무 전표", 3)],
        calls=[("월 결산 리포트 Agent", 4), ("영업 주간 리포트 Agent", 4)],
        negatives=[(5, "ACCESSES_DATA", "D001", "부정문 — '사용하지 않습니다'라서 관계를 만들면 안 됨")],
        date=("2026.05.01", 6), usage=("100건 정도", 6), status=("운영 중", 6)),
    dict(id="A050", name="시장 동향 리서치 Agent", sentences=[
        "경영기획팀 한지수 님이 함께 관리합니다.",
        "외부 산업 리포트와 뉴스를 모아 시장 동향을 정리하는 검색·요약 Agent입니다.",
        "Teams로 결과를 공유하고, 외부 리서치 자료를 참고합니다.",
        "경쟁사 수치는 외부 시장 분석 서비스인 마켓인텔 API의 결과를 받아 씁니다.",
        "Apr 2026부터 운영 중이고 월 200건 정도입니다."],
        dept=("경영기획팀", 1), owner=("한지수", 1), type=("검색·요약", 2),
        desc="외부 산업 리포트와 뉴스를 모아 시장 동향 정리",
        platforms=[("Teams", 3)], data=[("외부 리서치 자료", 3)], calls=[("마켓인텔 API", 4)],
        date=("Apr 2026", 5), usage=("200건 정도", 5), status=("운영 중", 5)),
    dict(id="A051", name="이사회 자료 작성 Agent", sentences=[
        "경영기획팀 오태경 님이 관리합니다.",
        "분기 이사회 보고 자료 초안을 쓰는 문서 작성 Agent입니다.",
        "SharePoint에 연동되어 있고, 경영 데이터 전반을 참고합니다.",
        "경영 현황 요약 Agent를 호출해 그 결과를 바탕으로 작성합니다.",
        "2026년 6월부터 파일럿으로 쓰고 있으며 월 4건 정도입니다."],
        dept=("경영기획팀", 1), owner=("오태경", 1), type=("문서 작성", 2),
        desc="분기 이사회 보고 자료 초안 작성",
        platforms=[("SharePoint", 3)], data=[("경영 데이터 전반", 3)], calls=[("경영 현황 요약 Agent", 4)],
        date=("2026년 6월", 5), usage=("4건 정도", 5), status=("파일럿", 5)),
    dict(id="A052", name="예산 편성 보조 Agent", sentences=[
        "경영기획팀 오태경 님이 함께 맡고 있습니다.",
        "부서별 지출 실적으로 차년도 예산 초안을 산출하는 데이터 분석 Agent입니다.",
        "SAP와 연동되어 회계 전표와 인건비 데이터를 씁니다.",
        "2025.10부터 운영 중이며 월 15건 정도입니다."],
        dept=("경영기획팀", 1), owner=("오태경", 1), type=("데이터 분석", 2),
        desc="부서별 지출 실적으로 차년도 예산 초안 산출",
        platforms=[("SAP", 3)], data=[("회계 전표", 3), ("인건비 데이터", 3)], calls=[],
        date=("2025.10", 4), usage=("15건 정도", 4), status=("운영 중", 4)),
    dict(id="A053", name="사내 공지 작성 Agent", sentences=[
        "기획팀에서 사용하는 Agent로, 담당자는 아직 정해지지 않았습니다.",
        "경영진 메시지와 사내 공지문 초안을 만드는 콘텐츠 생성 Agent입니다.",
        "팀즈와 그룹웨어 메일에 연동되어 있고, 사내 규정과 임직원 정보를 참고합니다.",
        "26/7/7부터 운영 중이며 월 20건 정도입니다."],
        dept=("기획팀", 1), owner=("", 1), type=("콘텐츠 생성", 2),
        desc="경영진 메시지와 사내 공지문 초안 작성",
        platforms=[("팀즈", 3), ("그룹웨어 메일", 3)], data=[("사내 규정", 3), ("임직원 정보", 3)], calls=[],
        date=("26/7/7", 4), usage=("20건 정도", 4), status=("운영 중", 4)),
]

# 경영진 질문 '비슷한 역할의 Agent가 중복 개발되고 있나?'용으로 일부러 넣은 쌍
PLANTED_DUPLICATES = [
    ("A031", "A044", "둘 다 계약서의 불리한 조항을 찾아 표시 (구매 계약 / 전체 계약)"),
    ("A016", "A036", "둘 다 사내 문서를 검색해 직원 질문에 답변 (기술 문서 / 사내 규정)"),
    ("A006", "A012", "둘 다 실적을 집계해 정기 보고 (영업 주간 / 마케팅 월간)"),
]

# 팀 설계안 'AI 관계 추출·등록 요청' 표를 옮긴 자유 입력 시험 문장
FREE_TEXT_CASES = [
    dict(id="T01", input="상품 추천 Agent는 고객 분석 결과와 상품 정보 DB를 사용해.", context_agent=None,
         expected_action="중복 표시",
         expected=[{"type": "USES_RESULT_OF", "source": "A002", "target": "A001"},
                   {"type": "ACCESSES_DATA", "source": "A002", "target": "D002"}],
         note="두 관계 모두 이미 SAMPLE로 등록됨 — 새로 만들지 말고 '기존 관계와 중복'으로 표시하고 근거만 덧붙임"),
    dict(id="T02", input="고객 분석 결과를 사용해.", context_agent=None, expected_action="기준 Agent 선택 요청",
         expected=[], note="주어(어떤 Agent가 쓰는지)가 없음 — 기준 Agent를 고르게 해야 함"),
    dict(id="T03", input="분석 Agent의 결과를 사용해.", context_agent="A012", expected_action="후보 제시 후 선택 요청",
         expected=[], note="'분석 Agent'와 부분 일치하는 Agent가 여러 개 — 후보 목록을 보여주고 고르게 함"),
    dict(id="T04", input="시장 동향 리서치 Agent는 외부 시장 분석 서비스를 사용해.", context_agent=None,
         expected_action="미등록 대상 검토 항목으로 보냄", expected=[],
         note="등록된 대상과 매칭되지 않음 — 이미 올라온 미등록 항목 '마켓인텔 API'와 같은 대상일 가능성도 함께 표시"),
    dict(id="T05", input="견적서 자동작성 Agent는 견적서 자동작성 Agent의 이전 결과를 다시 사용해.", context_agent=None,
         expected_action="저장 거부 (자기 참조)", expected=[], note="출발과 도착이 같은 관계는 검증 단계에서 거부"),
    dict(id="T06", input="월 결산 리포트 Agent는 향후 예산 편성 보조 Agent와 연동할 예정이다.", context_agent=None,
         expected_action="관계 없음 (미래 계획)", expected=[], note="현재 사용 관계가 아님"),
    dict(id="T07", input="경영 현황 요약 Agent는 CRM 고객정보를 사용하지 않는다.", context_agent=None,
         expected_action="관계 없음 (부정문)", expected=[], note="부정문을 관계로 바꾸면 안 됨"),
    dict(id="T08", input="HR팀의 채용 서류 1차 검토 Agent는 워크데이에서 이력서와 JD를 읽는다.", context_agent=None,
         expected_action="중복 표시",
         expected=[{"type": "BELONGS_TO", "source": "A034", "target": "DEP06"},
                   {"type": "USES_PLATFORM", "source": "A034", "target": "P04"},
                   {"type": "ACCESSES_DATA", "source": "A034", "target": "D019"},
                   {"type": "ACCESSES_DATA", "source": "A034", "target": "D020"}],
         note="별칭 사전(SKOS)으로 HR팀→인사팀, 워크데이→Workday, 이력서→지원자 정보, JD→직무기술서로 맞춘 뒤 기존 관계와 중복 확인"),
]


# ---------------------------------------------------------------- normalizers
def build_index():
    idx = {"department": {}, "work_type": {}, "platform": {}, "data": {}, "status": {}}
    for i, pref, _b, alts, _d in DEPARTMENTS:
        idx["department"][pref] = (i, "exact")
        for a in alts:
            idx["department"][a] = (i, "alias")
    for i, pref, alts, _d in WORK_TYPES:
        idx["work_type"][pref] = (i, "exact")
        for a in alts:
            idx["work_type"][a] = (i, "alias")
    for i, pref, _c, alts in PLATFORMS:
        idx["platform"][pref] = (i, "exact")
        for a in alts:
            idx["platform"][a] = (i, "alias")
    for i, pref, _s, _c, _p, alts in DATA:
        idx["data"][pref] = (i, "exact")
        for a in alts:
            idx["data"][a] = (i, "alias")
    for pref, alts in STATUS_VOCAB.items():
        idx["status"][pref] = (pref, "exact")
        for a in alts:
            idx["status"][a] = (pref, "alias")
    return idx


IDX = build_index()
NAMES = {}  # id -> 표준 이름 (모든 엔티티)
for i, pref, *_ in DEPARTMENTS:
    NAMES[i] = pref
for i, pref, *_ in WORK_TYPES:
    NAMES[i] = pref
for i, pref, *_ in PLATFORMS:
    NAMES[i] = pref
for i, pref, *_ in DATA:
    NAMES[i] = pref
SENS = {d[0]: d[2] for d in DATA}
STORED = {d[0]: d[4] for d in DATA}


def resolve(kind, raw):
    """원문 표기 → (표준 id 또는 None, 매칭 방식, 검토 사유)."""
    raw = (raw or "").strip()
    if raw in IDX[kind]:
        i, how = IDX[kind][raw]
        return i, how, None
    if raw in REVIEW.get(kind, {}):
        i, reason = REVIEW[kind][raw]
        return i, "review", reason
    if raw in UNREGISTERED.get(kind, {}):
        return None, "unregistered", UNREGISTERED[kind][raw]
    raise ValueError(f"{kind}: 정해지지 않은 표기 {raw!r}")


MONTHS = {m: i + 1 for i, m in enumerate("Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split())}


def parse_date(raw):
    """원문 날짜 → (YYYY-MM 또는 YYYY-MM-DD, 검토 사유)."""
    s = (raw or "").strip().strip("'")
    if not s:
        return None, "운영 시작일 누락"
    relative = {
        "작년 12월": ("2025-12", "상대 날짜 — 제출일(2026년 10월) 기준 2025년 12월로 추정"),
        "작년 말": ("2025-12", "상대 날짜 — 제출일 기준 2025년 말로 추정"),
        "4월 초": ("2026-04", "연도 없음 — 제출 연도(2026)로 추정, 일자 불명확"),
    }
    if s in relative:
        return relative[s]
    if re.fullmatch(r"\d{4}/Q[1-4]", s):
        return None, "분기 표기 — 일자 확인 필요"
    if re.search(r"상반기|하반기", s):
        return None, "반기 표기 — 일자 확인 필요"
    m = re.fullmatch(r"(\d{4})년 (\d{1,2})월 (초|중순|말)", s)
    if m:
        return f"{m[1]}-{int(m[2]):02d}", "일자 불명확 — 월까지만 확정"
    m = re.fullmatch(r"(\d{4})[-./](\d{1,2})[-./](\d{1,2})", s)
    if m:
        return f"{m[1]}-{int(m[2]):02d}-{int(m[3]):02d}", None
    m = re.fullmatch(r"(\d{2})/(\d{1,2})/(\d{1,2})", s)
    if m:
        return f"20{m[1]}-{int(m[2]):02d}-{int(m[3]):02d}", None
    m = re.fullmatch(r"(\d{4})[-./](\d{1,2})", s)
    if m:
        return f"{m[1]}-{int(m[2]):02d}", None
    m = re.fullmatch(r"(\d{4})년 (\d{1,2})월", s)
    if m:
        return f"{m[1]}-{int(m[2]):02d}", None
    m = re.fullmatch(r"([A-Z][a-z]{2}) (\d{4})", s)
    if m:
        return f"{m[2]}-{MONTHS[m[1]]:02d}", None
    raise ValueError(f"날짜 형식 미정의: {raw!r}")


def parse_usage(raw):
    """원문 월 사용량 → (정수, 검토 사유)."""
    s = ("" if raw is None else str(raw)).strip().strip("'")
    if not s:
        return None, "월 사용량 누락"
    if re.fullmatch(r"\d+\s*~\s*\d+", s):
        return None, "범위값 — 단일 값 확인 필요"
    if s in ("매일", "하루 한 번"):
        return 30, "빈도 표현 — 월 약 30회로 추정"
    if s == "많음":
        return None, "숫자가 아닌 값"
    t = re.sub(r"^약\s*", "", s)
    t = re.sub(r"건?\s*(정도|내외)$", "", t).strip()
    m = re.fullmatch(r"([\d.,]+)\s*(천|만|k)?", t)
    if not m:
        raise ValueError(f"사용량 형식 미정의: {raw!r}")
    num = float(m[1].replace(",", ""))
    num *= {"천": 1000, "만": 10000, "k": 1000}.get(m[2], 1)
    return int(round(num)), None


TITLES = re.compile(r"\s*(책임|매니저|선임|변호사|님)$")


def parse_owner(raw):
    s = (raw or "").strip()
    if not s:
        return [], "담당자 누락"
    if s == "법무팀 담당자":
        return [], "특정 담당자 이름 없음"
    return [TITLES.sub("", p).strip() for p in re.split(r"\s*[/,]\s*", s)], None


# ---------------------------------------------------------------- raw writers
LOC = {}  # (agent_id, field, index) -> (file, locator, evidence)


def write_json():
    fname = "01_영업_마케팅.json"
    keys = dict(name="agentName", dept="dept", owner="owner", type="category", desc="description",
                platforms="connectedPlatforms", data="dataSources", calls="callsAgents", date="launchDate",
                usage="monthlyUsage", status="status")
    agents = []
    for n, a in enumerate(JSON_AGENTS):
        rec = {keys[k]: a[k] for k in keys}
        agents.append(rec)
        for k in ("name", "dept", "owner", "type", "date", "usage", "status"):
            LOC[(a["id"], k, 0)] = (fname, f"/agents/{n}/{keys[k]}", a[k])
        for k in ("platforms", "data", "calls"):
            for j, v in enumerate(a[k]):
                LOC[(a["id"], k, j)] = (fname, f"/agents/{n}/{keys[k]}/{j}", v)
    doc = {"submittedBy": "영업본부 / 마케팅본부", "submittedAt": "2026.10.20", "agents": agents}
    (RAW / fname).write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def ystr(v):
    if v is None:
        return "null"
    return v  # 원문 그대로(따옴표 포함 여부까지 원본 표기)


def write_yaml():
    fname = "02_개발_IT운영.yaml"
    lines = ["제출부서: 개발본부·IT운영팀", "제출일: 2026/10/21", "agents:"]
    keys = [("name", "name"), ("dept", "team"), ("owner", "maintainer"), ("type", "type"), ("desc", "summary"),
            ("platforms", "integrations"), ("data", "uses_data"), ("calls", "depends_on"), ("date", "since"),
            ("usage", "calls_per_month"), ("status", "state")]
    for n, a in enumerate(YAML_AGENTS):
        for k, yk in keys:
            prefix = "- " if k == "name" else "  "
            v = a[k]
            if isinstance(v, list):
                if not v:
                    lines.append(f"{prefix}{yk}: null")
                    continue
                lines.append(f"{prefix}{yk}:")
                for j, item in enumerate(v):
                    lines.append(f"  - {item}")
                    LOC[(a["id"], k, j)] = (fname, f"{len(lines)}행 (agents[{n}].{yk}[{j}])", item)
            else:
                shown = "''" if v == "" else ystr(v)
                lines.append(f"{prefix}{yk}: {shown}")
                LOC[(a["id"], k, 0)] = (fname, f"{len(lines)}행 (agents[{n}].{yk})", v)
    (RAW / fname).write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_csv():
    fname = "03_고객지원_구매.csv"
    cols = [("name", "agent_nm"), ("dept", "dept_nm"), ("owner", "manager"), ("type", "task_type"), ("desc", "desc"),
            ("platforms", "system"), ("data", "data_used"), ("calls", "linked_agent"), ("date", "open_dt"),
            ("usage", "monthly_req"), ("status", "status")]
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\n")
    w.writerow([c for _, c in cols])
    for n, a in enumerate(CSV_AGENTS):
        line = n + 2
        row = []
        for k, c in cols:
            v = a[k]
            if isinstance(v, list):
                row.append(";".join(v))
                for j, item in enumerate(v):
                    LOC[(a["id"], k, j)] = (fname, f"{line}행 {c}열", item)
            else:
                row.append(v or "")
                LOC[(a["id"], k, 0)] = (fname, f"{line}행 {c}열", v)
        w.writerow(row)
    (RAW / fname).write_text(buf.getvalue(), encoding="utf-8")


def xlsx_cell(ref, value, style=1):
    if isinstance(value, int):
        return f'<c r="{ref}" s="{style}"><v>{value}</v></c>'
    if value in (None, ""):
        return f'<c r="{ref}" s="{style}"/>'
    return f'<c r="{ref}" t="inlineStr" s="{style}"><is><t xml:space="preserve">{escape(value)}</t></is></c>'


def write_xlsx():
    fname = "04_인사_재무.xlsx"
    sheet = "Agent 현황"
    cols = [("no", "No"), ("name", "Agent 이름"), ("dept", "부서"), ("owner", "담당자"), ("type", "업무 유형"),
            ("desc", "주요 기능"), ("platforms", "연동 시스템"), ("data", "사용 데이터"), ("calls", "연계 Agent"),
            ("date", "도입 시기"), ("usage", "월 사용량"), ("status", "상태"), ("note", "비고")]
    letters = "ABCDEFGHIJKLM"
    rows = ['<row r="1">' + "".join(xlsx_cell(f"{letters[i]}1", h, 2) for i, (_, h) in enumerate(cols)) + "</row>"]
    for n, a in enumerate(XLSX_AGENTS):
        r = n + 2
        cells = []
        for i, (k, _h) in enumerate(cols):
            ref = f"{letters[i]}{r}"
            if k == "no":
                cells.append(xlsx_cell(ref, n + 1))
                continue
            v = a[k]
            if isinstance(v, list):
                cells.append(xlsx_cell(ref, "\n".join(v)))
                for j, item in enumerate(v):
                    LOC[(a["id"], k, j)] = (fname, f"{sheet}!{ref}", item)
                continue
            if k == "usage" and re.fullmatch(r"\d+", v or ""):
                cells.append(xlsx_cell(ref, int(v)))
            else:
                cells.append(xlsx_cell(ref, v))
            LOC[(a["id"], k, 0)] = (fname, f"{sheet}!{ref}", v)
        rows.append(f'<row r="{r}">' + "".join(cells) + "</row>")
    widths = [5, 26, 10, 26, 12, 46, 20, 20, 22, 12, 10, 8, 40]
    cols_xml = "".join(f'<col min="{i + 1}" max="{i + 1}" width="{w}" customWidth="1"/>' for i, w in enumerate(widths))
    ns = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
    rns = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
    files = {
        "[Content_Types].xml": (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
            '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
            '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
            "</Types>"),
        "_rels/.rels": (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
            "</Relationships>"),
        "xl/workbook.xml": (
            f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook {ns} {rns}>'
            f'<sheets><sheet name="{sheet}" sheetId="1" r:id="rId1"/></sheets></workbook>'),
        "xl/_rels/workbook.xml.rels": (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
            '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
            "</Relationships>"),
        "xl/styles.xml": (
            f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet {ns}>'
            '<fonts count="2"><font><sz val="11"/><name val="맑은 고딕"/></font>'
            '<font><b/><sz val="11"/><name val="맑은 고딕"/></font></fonts>'
            '<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>'
            '<fill><patternFill patternType="solid"><fgColor rgb="FFDDEBF7"/><bgColor indexed="64"/></patternFill></fill></fills>'
            '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
            '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
            '<cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
            '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf>'
            '<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs>'
            '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>'),
        "xl/worksheets/sheet1.xml": (
            f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet {ns}>'
            f'<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>'
            f"<cols>{cols_xml}</cols><sheetData>{''.join(rows)}</sheetData></worksheet>"),
    }
    with zipfile.ZipFile(RAW / fname, "w", zipfile.ZIP_DEFLATED) as z:
        for name, body in files.items():
            z.writestr(name, body)


def docx_par(text, bold=False, size=None):
    rpr = '<w:rFonts w:ascii="맑은 고딕" w:eastAsia="맑은 고딕" w:hAnsi="맑은 고딕"/>'
    if bold:
        rpr += "<w:b/>"
    if size:
        rpr += f'<w:sz w:val="{size}"/>'
    return (f'<w:p><w:pPr><w:spacing w:after="120"/></w:pPr><w:r><w:rPr>{rpr}</w:rPr>'
            f'<w:t xml:space="preserve">{escape(text)}</w:t></w:r></w:p>')


def write_docx():
    fname = "05_법무_경영기획.docx"
    paras = [("법무팀·경영기획팀 AI Agent 사용 현황", True, 32),
             ("작성일: 2026년 10월 22일 / 작성: 법무팀, 경영기획팀", False, None),
             ("요청하신 부서별 Agent 현황을 제출합니다. 정해진 양식이 없어 Agent마다 서술형으로 정리했습니다.", False, None)]
    for n, a in enumerate(DOCX_AGENTS):
        paras.append((f"{n + 1}. {a['name']}", True, None))
        LOC[(a["id"], "name", 0)] = (fname, f"문단 {len(paras)}", a["name"])
        paras.append((" ".join(a["sentences"]), False, None))
        p = len(paras)

        def put(field, idx, token, sent):
            if sent is None:
                LOC[(a["id"], field, idx)] = (fname, f"문단 {p}", "")
                return
            s = a["sentences"][sent - 1]
            if token and token not in s:
                raise ValueError(f"{a['id']} {field}: {token!r} not in sentence {sent}")
            LOC[(a["id"], field, idx)] = (fname, f"문단 {p} 문장 {sent}", s)

        for f in ("dept", "owner", "type", "date", "usage", "status"):
            put(f, 0, a[f][0], a[f][1])
        for f in ("platforms", "data", "calls", "provided_to"):
            for j, (tok, sent) in enumerate(a.get(f, [])):
                put(f, j, tok, sent)
        for j, (sent, *_rest) in enumerate(a.get("negatives", [])):
            put("negatives", j, None, sent)
        a["_para"] = p
    paras.append(("문의는 법무팀 또는 경영기획팀으로 연락 바랍니다.", False, None))
    body = "".join(docx_par(t, b, s) for t, b, s in paras)
    doc = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
           '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
           f'{body}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
           '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/>'
           "</w:sectPr></w:body></w:document>")
    files = {
        "[Content_Types].xml": (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
            "</Types>"),
        "_rels/.rels": (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
            "</Relationships>"),
        "word/document.xml": doc,
    }
    with zipfile.ZipFile(RAW / fname, "w", zipfile.ZIP_DEFLATED) as z:
        for name, body in files.items():
            z.writestr(name, body)


# ---------------------------------------------------------------- canonical model
def docx_value(a, f):
    v = a[f]
    return v[0] if isinstance(v, tuple) else v


def flat(a, f):
    """필드 원문 값을 목록으로(docx는 (token, 문장) 묶음에서 token만)."""
    v = a.get(f, [])
    return [x[0] if isinstance(x, tuple) else x for x in v]


def build_model():
    agents = {}
    for s in SAMPLE_AGENTS:
        NAMES[s["id"]] = s["name"]
        agents[s["id"]] = dict(id=s["id"], name=s["name"], department=s["dept"], owners=s["owners"], work_type=s["wt"],
                               description=s["desc"], start_date=s["start"], monthly_calls=s["usage"],
                               status=s["status"], source_file="seed", review=[])
    raw_groups = [("01_영업_마케팅.json", JSON_AGENTS), ("02_개발_IT운영.yaml", YAML_AGENTS),
                  ("03_고객지원_구매.csv", CSV_AGENTS), ("04_인사_재무.xlsx", XLSX_AGENTS),
                  ("05_법무_경영기획.docx", DOCX_AGENTS)]
    for _f, group in raw_groups:
        for a in group:
            NAMES[a["id"]] = a["name"]
    by_name = {re.sub(r"\s+", "", n): i for i, n in NAMES.items() if i.startswith("A")}

    rels = {}  # (type, source, target) -> rel dict
    pending = []
    cases = []

    def add_rel(t, src, tgt, status, ev, reason=None, extra=None):
        k = (t, src, tgt)
        if k not in rels:
            rels[k] = dict(type=t, source=src, target=tgt, status=status, evidence=[], review_reason=reason)
            if extra:
                rels[k].update(extra)
        r = rels[k]
        if ev:
            r["evidence"].append(ev)
        if status == "CANDIDATE" and r["status"] == "CONFIRMED":
            r["status"], r["review_reason"] = "CANDIDATE", reason
        return r

    # SAMPLE: 팀 설계안 예시
    sample_ev = {"file": "seed (팀 설계안 '저장되는 Agent 관계' 예시)", "locator": "팀 설계 보드", "text": None}
    for s in SAMPLE_AGENTS:
        ev = dict(sample_ev, text=f"{s['name']} 등록 정보")
        add_rel("BELONGS_TO", s["id"], s["dept"], "SAMPLE", ev)
        add_rel("HAS_WORK_TYPE", s["id"], s["wt"], "SAMPLE", ev)
        for p in s["platforms"]:
            add_rel("USES_PLATFORM", s["id"], p, "SAMPLE", ev)
        for d in s["data"]:
            add_rel("ACCESSES_DATA", s["id"], d, "SAMPLE", dict(sample_ev, text=f"{s['id']} {s['name']} → {d} {NAMES[d]} (데이터사용)"))
        for c in s["calls"]:
            add_rel("USES_RESULT_OF", s["id"], c, "SAMPLE", dict(sample_ev, text=f"{s['id']} {s['name']} → {c} {NAMES[c]} (결과사용)"))

    for fname, group in raw_groups:
        is_docx = fname.endswith(".docx")
        for a in group:
            aid = a["id"]
            review = []

            def ev(field, j=0):
                f, loc, text = LOC[(aid, field, j)]
                return {"file": f, "locator": loc, "text": text}

            def flag(field, raw, reason, candidate=None):
                review.append({"field": field, "raw": raw, "candidate": candidate, "reason": reason})

            dept_raw = docx_value(a, "dept") if is_docx else a["dept"]
            if not dept_raw and a.get("dept_hint"):
                dept_id, reason = a["dept_hint"]
                flag("department", dept_raw, reason, dept_id)
                header = {"file": fname, "locator": "1행 (제출부서)", "text": "제출부서: 개발본부·IT운영팀"}
                add_rel("BELONGS_TO", aid, dept_id, "CANDIDATE", header, reason)
            else:
                dept_id, how, reason = resolve("department", dept_raw)
                status = "CANDIDATE" if how == "review" else "CONFIRMED"
                if reason:
                    flag("department", dept_raw, reason, dept_id)
                add_rel("BELONGS_TO", aid, dept_id, status, ev("dept"), reason)

            wt_raw = docx_value(a, "type") if is_docx else a["type"]
            wt_id, _how, _r = resolve("work_type", wt_raw)
            add_rel("HAS_WORK_TYPE", aid, wt_id, "CONFIRMED", ev("type"))

            for j, p in enumerate(flat(a, "platforms")):
                pid, how, reason = resolve("platform", p)
                if reason:
                    flag("platforms", p, reason, pid)
                add_rel("USES_PLATFORM", aid, pid, "CANDIDATE" if how == "review" else "CONFIRMED", ev("platforms", j), reason)

            for j, d in enumerate(flat(a, "data")):
                did, how, reason = resolve("data", d)
                if how == "unregistered":
                    flag("data", d, reason)
                    pending.append({"kind": "DataAsset", "raw": d, "mentioned_by": aid, "reason": reason, "evidence": ev("data", j)})
                    continue
                if reason:
                    flag("data", d, reason, did)
                add_rel("ACCESSES_DATA", aid, did, "CANDIDATE" if how == "review" else "CONFIRMED", ev("data", j), reason)

            for j, c in enumerate(flat(a, "calls")):
                key = re.sub(r"\s+", "", c)
                if c in UNREGISTERED["agent"]:
                    reason = UNREGISTERED["agent"][c]
                    flag("calls", c, reason)
                    pending.append({"kind": "Agent/외부 서비스", "raw": c, "mentioned_by": aid, "reason": reason, "evidence": ev("calls", j)})
                    cases.append({"case": "미등록 대상", "agent": aid, "raw": c, "expected": "미등록 대상 검토 항목으로 보냄", "evidence": ev("calls", j)})
                    continue
                if c in AMBIGUOUS_AGENT:
                    info = AMBIGUOUS_AGENT[c]
                    cands = sorted(i for i, n in NAMES.items() if i.startswith("A") and "분석" in n and i != aid)
                    flag("calls", c, info["reason"], info["resolved"])
                    add_rel("USES_RESULT_OF", aid, info["resolved"], "CANDIDATE", ev("calls", j), info["reason"],
                            extra={"candidates_shown": cands})
                    cases.append({"case": "모호한 이름", "agent": aid, "raw": c, "candidates": cands,
                                  "expected": f"후보를 보여주고 사용자가 {info['resolved']}({NAMES[info['resolved']]})를 고름",
                                  "evidence": ev("calls", j)})
                    continue
                tid = by_name.get(key)
                if tid is None:
                    raise ValueError(f"{aid}: 호출 대상 {c!r} 미정의")
                if tid == aid:
                    reason = "자기 참조 — 출발과 도착이 같은 관계는 저장하지 않음"
                    flag("calls", c, reason)
                    cases.append({"case": "자기 참조", "agent": aid, "raw": c, "expected": "검증 단계에서 거부", "evidence": ev("calls", j)})
                    continue
                exact = NAMES[tid] == c
                e = ev("calls", j)
                if not exact:
                    e["match"] = f"공백 차이로 '{NAMES[tid]}'와 매칭"
                add_rel("USES_RESULT_OF", aid, tid, "CONFIRMED", e)

            for j, (c, _s) in enumerate(a.get("provided_to", [])):
                src = by_name[re.sub(r"\s+", "", c)]
                e = ev("provided_to", j)
                e["direction_note"] = f"제공하는 쪽({a['name']})이 주어인 문장 — 관계 방향은 {NAMES[src]} → {a['name']}"
                r = add_rel("USES_RESULT_OF", src, aid, "CONFIRMED", e)
                cases.append({"case": "중복 진술·방향", "agent": aid, "raw": c,
                              "expected": f"{NAMES[src]} → {a['name']} 관계 하나로 합치고 근거 {len(r['evidence'])}건을 모두 남김",
                              "evidence": e})

            for j, (_sent, t, tgt, why) in enumerate(a.get("negatives", [])):
                cases.append({"case": "부정문", "agent": aid, "type": t, "target": tgt, "expected": "관계를 만들지 않음",
                              "reason": why, "evidence": ev("negatives", j)})
            if a.get("note"):
                cases.append({"case": "미래 계획", "agent": aid, "raw": a["note"], "expected": "관계를 만들지 않음",
                              "reason": "현재 사용 중인 관계가 아님",
                              "evidence": ev("note")})

            owner_raw = docx_value(a, "owner") if is_docx else a["owner"]
            owners, reason = parse_owner(owner_raw)
            if reason:
                flag("owners", owner_raw or "", reason)
            date_raw = docx_value(a, "date") if is_docx else a["date"]
            start, reason = parse_date(date_raw)
            if reason:
                flag("start_date", date_raw or "", reason, start)
            usage_raw = docx_value(a, "usage") if is_docx else a["usage"]
            usage, reason = parse_usage(usage_raw)
            if reason:
                flag("monthly_calls", usage_raw or "", reason, usage)
            status_raw = docx_value(a, "status") if is_docx else a["status"]
            status, _how, _r = resolve("status", status_raw)
            agents[aid] = dict(id=aid, name=a["name"], department=dept_id, owners=owners, work_type=wt_id,
                               description=a["desc"], start_date=start, monthly_calls=usage, status=status,
                               source_file=fname, review=review,
                               raw={k: (docx_value(a, k) if is_docx and k in ("dept", "owner", "type", "date", "usage", "status") else a.get(k))
                                    for k in ("dept", "owner", "type", "date", "usage", "status")})
            if is_docx:
                agents[aid]["raw"].update(platforms=flat(a, "platforms"), data=flat(a, "data"), calls=flat(a, "calls"))
            else:
                agents[aid]["raw"].update(platforms=a["platforms"], data=a["data"], calls=a["calls"])

    for did, _n, _s, _c, pid, _alts in DATA:
        if pid:
            add_rel("STORED_IN", did, pid, "CONFIRMED",
                    {"file": "vocabulary/vocabulary.json", "locator": f"data_assets/{did}/stored_in", "text": "주관 팀이 정한 저장 위치"})

    ordered = sorted(rels.values(), key=lambda r: (r["source"], REL_ORDER[r["type"]], r["target"]))
    for n, r in enumerate(ordered, 1):
        r["id"] = f"R{n:04d}"
        r["source_name"], r["target_name"] = NAMES[r["source"]], NAMES[r["target"]]
        r["type_label"] = REL_LABEL[r["type"]]
        if r["status"] == "CONFIRMED":
            r["reviewed_by"], r["reviewed_at"] = REVIEWERS[n % 2], REVIEW_DATES[n % 2]
        else:
            r["reviewed_by"], r["reviewed_at"] = None, None
    return agents, ordered, pending, cases


# ---------------------------------------------------------------- graph queries
class Graph:
    def __init__(self, rels, scope):
        ok = {"all": {"SAMPLE", "CANDIDATE", "CONFIRMED"}, "confirmed": {"CONFIRMED"}}[scope]
        self.rels = [r for r in rels if r["status"] in ok]
        self.out = defaultdict(list)
        self.inc = defaultdict(list)
        for r in self.rels:
            self.out[r["source"]].append(r)
            self.inc[r["target"]].append(r)

    def deps(self, anchor, depth):
        """정방향: anchor가 쓰는 Agent·데이터 (결과사용 + 데이터사용)."""
        seen, res = {anchor}, []
        q = deque([(anchor, 0, [anchor])])
        while q:
            n, d, path = q.popleft()
            if d == depth:
                continue
            for r in sorted(self.out[n], key=lambda r: r["target"]):
                if r["type"] not in ("USES_RESULT_OF", "ACCESSES_DATA") or r["target"] in seen:
                    continue
                seen.add(r["target"])
                res.append(dict(id=r["target"], name=NAMES[r["target"]], depth=d + 1, via=r["type_label"],
                                status=r["status"], path=[NAMES[x] for x in path + [r["target"]]]))
                if r["type"] == "USES_RESULT_OF":
                    q.append((r["target"], d + 1, path + [r["target"]]))
        return res

    def impact(self, anchor, depth=99):
        """역방향: anchor를 바꾸면 점검할 Agent. 첫 단계는 anchor 종류에 맞는 관계, 이후는 결과사용만."""
        first = {"A": "USES_RESULT_OF", "D": "ACCESSES_DATA", "P": "USES_PLATFORM"}[anchor[0]]
        seen, res = {anchor}, []
        q = deque([(anchor, 0, [anchor])])
        while q:
            n, d, path = q.popleft()
            if d == depth:
                continue
            want = first if d == 0 else "USES_RESULT_OF"
            for r in sorted(self.inc[n], key=lambda r: r["source"]):
                if r["type"] != want or r["source"] in seen:
                    continue
                seen.add(r["source"])
                res.append(dict(id=r["source"], name=NAMES[r["source"]], depth=d + 1, status=r["status"],
                                path=[NAMES[x] for x in path + [r["source"]]]))
                q.append((r["source"], d + 1, path + [r["source"]]))
        return res

    def path(self, src, dst):
        q = deque([(src, [src])])
        seen = {src}
        while q:
            n, p = q.popleft()
            if n == dst:
                return p
            for r in sorted(self.out[n], key=lambda r: r["target"]):
                if r["type"] == "USES_RESULT_OF" and r["target"] not in seen:
                    seen.add(r["target"])
                    q.append((r["target"], p + [r["target"]]))
        return None

    def cycles(self):
        found = set()
        agents = sorted({r["source"] for r in self.rels if r["type"] == "USES_RESULT_OF"})
        for s in agents:
            stack = [(s, [s])]
            while stack:
                n, p = stack.pop()
                for r in self.out[n]:
                    if r["type"] != "USES_RESULT_OF":
                        continue
                    t = r["target"]
                    if t == s:
                        cyc = p[:]
                        k = cyc.index(min(cyc))
                        found.add(tuple(cyc[k:] + cyc[:k]))
                    elif t not in p and len(p) < 10:
                        stack.append((t, p + [t]))
        return [[NAMES[x] for x in c] for c in sorted(found)]

    def targets(self, src, t):
        return sorted(r["target"] for r in self.out[src] if r["type"] == t)


def summarize(items, arrow=" ← "):
    """의존(정방향)은 ' → ', 영향(역방향)은 ' ← '로 경로를 잇습니다."""
    return {"direct": [x["name"] for x in items if x["depth"] == 1],
            "indirect": [{"name": x["name"], "depth": x["depth"], "path": arrow.join(x["path"])} for x in items if x["depth"] > 1],
            "count": len(items)}


def both(fn):
    a, c = fn(GRAPHS["all"]), fn(GRAPHS["confirmed"])
    return {"all_statuses": a, "confirmed_only": c, "differs": a != c}


def build_queries(agents, rels, pending):
    Q = []
    A = sorted(i for i in agents)

    def q(qid, persona, question, intent, params, expected, rule):
        Q.append(dict(id=qid, persona=persona, question=question, intent=intent, params=params, expected=expected,
                      answer_rule=rule))

    def anchor(i):
        kind = {"A": "Agent", "D": "DataAsset", "P": "Platform"}[i[0]]
        return {"id": i, "name": NAMES[i], "type": kind}

    def dept_counts(g):
        c = Counter(NAMES[g.targets(a, "BELONGS_TO")[0]] if g.targets(a, "BELONGS_TO") else "(미확정)" for a in A)
        return dict(sorted(c.items()))

    q("Q01", "공통", "어떤 Agent가 등록돼 있나? 부서별로 몇 개야?", "aggregate",
      {"group_by": "BELONGS_TO", "status_scope": "both"},
      dict(total_agents=len(A), by_department=both(dept_counts)),
      "부서가 미확정(CANDIDATE)인 Agent는 확정 기준에서 '(미확정)'으로 셈")
    q("Q02", "공통", "상품추천 Agent는 무엇을 직접 사용하나?", "dependencies",
      {"anchor": anchor("A002"), "direction": "forward", "depth": 1, "relation_types": ["USES_RESULT_OF", "ACCESSES_DATA"], "status_scope": "both"},
      both(lambda g: summarize(g.deps("A002", 1), " → ")),
      "SAMPLE 관계만 있으므로 확정 기준으로는 '등록된 확정 관계에서는 발견되지 않음'이라고 답하고 SAMPLE 2건을 함께 보여줌")
    q("Q03", "공통", "상품추천 Agent의 간접 의존 대상은?", "dependencies",
      {"anchor": anchor("A002"), "direction": "forward", "depth": 2, "relation_types": ["USES_RESULT_OF", "ACCESSES_DATA"], "status_scope": "both"},
      both(lambda g: summarize(g.deps("A002", 2), " → ")), "깊이 2 이상만 간접으로 표시")
    q("Q04", "공통", "고객분석 Agent를 바꾸면 어디를 점검해야 하나?", "impact",
      {"anchor": anchor("A001"), "direction": "reverse", "depth": "all", "relation_types": ["USES_RESULT_OF"], "status_scope": "both"},
      both(lambda g: summarize(g.impact("A001"))), "직접(깊이 1)과 간접(깊이 2 이상)을 나눠서, 간접은 경로까지 보여줌")
    q("Q05", "공통", "상담지원 Agent가 왜 점검 대상인가?", "path_explain",
      {"anchor": anchor("A001"), "target": anchor("A003"), "direction": "reverse", "status_scope": "all"},
      {"path": next(x["path"] for x in GRAPHS["all"].impact("A001") if x["id"] == "A003"),
       "statuses": [r["status"] for r in rels if (r["source"], r["target"]) in {("A003", "A002"), ("A002", "A001")}]},
      "역방향 탐색 경로를 그대로 설명하고, 경로의 관계가 SAMPLE이라는 점을 함께 알림")
    q("Q06", "현업", "견적서 자동작성 Agent에 직접 연결된 대상만 보여줘.", "dependencies",
      {"anchor": anchor("A004"), "direction": "forward", "depth": 1, "relation_types": ["USES_RESULT_OF", "ACCESSES_DATA"], "status_scope": "both"},
      both(lambda g: summarize(g.deps("A004", 1), " → ")), "깊이 1만")
    r07 = next(r for r in rels if r["source"] == "A007" and r["target"] == "D001")
    q("Q07", "데이터·보안", "계약 갱신 알림 Agent가 CRM 고객정보를 쓴다는 근거는?", "evidence",
      {"relation": {"source": "A007", "type": "ACCESSES_DATA", "target": "D001"}},
      {"relation_id": r07["id"], "status": r07["status"], "evidence": r07["evidence"],
       "reviewed_by": r07["reviewed_by"], "reviewed_at": r07["reviewed_at"]},
      "근거 원문(별칭 'CRM 고객테이블')과 파일·위치, 검토자를 함께 보여줌")
    r08 = [r for r in rels if r["source"] == "A026" and r["type"] == "ACCESSES_DATA"]
    q("Q08", "데이터·보안", "VOC 분류 Agent가 쓰는 데이터는 검토된 실제 관계인가?", "status",
      {"anchor": anchor("A026"), "relation_types": ["ACCESSES_DATA"]},
      [{"relation_id": r["id"], "target": r["target_name"], "status": r["status"], "review_reason": r["review_reason"]} for r in r08],
      "CANDIDATE면 '검토 대기'와 사유를 그대로 알림")
    q("Q09", "현업", "보도자료 초안 Agent를 바꿔도 점검할 대상이 있나?", "impact",
      {"anchor": anchor("A013"), "direction": "reverse", "depth": "all", "status_scope": "all"},
      {"result": summarize(GRAPHS["all"].impact("A013")),
       "answer": "등록된 관계에서는 점검 대상이 발견되지 않았습니다. 다만 등록되지 않은 연결이 있을 수 있어 안전하다고 확정할 수는 없습니다."},
      "결과가 없을 때 '안전'이라고 말하지 않음")

    def high(g):
        return sorted(a for a in A if any(SENS[d] == "상" for d in g.targets(a, "ACCESSES_DATA")))
    q("Q10", "경영진", "민감도 '상' 데이터에 접근하는 Agent는 몇 개야?", "aggregate",
      {"filter": {"data.sensitivity": "상"}, "relation_types": ["ACCESSES_DATA"], "status_scope": "both"},
      both(lambda g: {"count": len(high(g)), "agents": [NAMES[x] for x in high(g)]}),
      "확정 기준과 후보 포함 기준 숫자를 함께 보여줌")

    def ranking(g):
        rows = [(len(g.impact(a)), NAMES[a]) for a in A]
        rows = sorted((r for r in rows if r[0] > 0), key=lambda x: (-x[0], x[1]))[:6]
        return [{"agent": n, "dependents": c} for c, n in rows]
    q("Q11", "경영진", "여러 Agent가 공통으로 의존하는 핵심 Agent는?", "ranking",
      {"metric": "직접·간접 의존 Agent 수 (결과사용 역방향, 깊이 제한 없음)", "top": 6, "status_scope": "both"},
      both(ranking), "같은 수면 이름순")

    def multi(g):
        return [NAMES[a] for a in A if any(SENS[d] == "상" for d in g.targets(a, "ACCESSES_DATA"))
                and len(g.targets(a, "USES_PLATFORM")) >= 3]
    q("Q12", "경영진", "고민감도 데이터에 접근하면서 플랫폼을 3개 이상 쓰는 Agent는?", "insight",
      {"filter": {"data.sensitivity": "상", "platform_count_gte": 3}, "status_scope": "both"},
      both(multi), "관리 복잡도 인사이트(초안 보고서 5절)")
    q("Q13", "경영진", "비슷한 역할의 Agent가 중복 개발되고 있나?", "similarity",
      {"method": "설명 유사도(임베딩) + 업무 유형", "status_scope": "all"},
      {"must_include_pairs": [{"pair": [NAMES[x], NAMES[y]], "why": w} for x, y, w in PLANTED_DUPLICATES]},
      "일부러 넣은 3쌍을 후보로 찾으면 정답. 그 밖의 쌍은 사람이 판단")

    def plat(g):
        c = Counter(NAMES[r["target"]] for r in g.rels if r["type"] == "USES_PLATFORM")
        return [{"platform": p, "agents": n} for p, n in sorted(c.items(), key=lambda x: (-x[1], x[0]))[:5]]
    q("Q14", "경영진", "Agent가 가장 많이 몰린 플랫폼은?", "ranking",
      {"metric": "플랫폼별 연동 Agent 수", "top": 5, "status_scope": "both"}, both(plat),
      "단일 장애점 인사이트. ERP 표기가 SAP 후보라 기준에 따라 숫자가 다름")
    q("Q15", "개발·운영", "CRM 고객정보를 변경하면 어떤 Agent를 점검해야 하나?", "impact",
      {"anchor": anchor("D001"), "direction": "reverse", "depth": "all", "relation_types": ["ACCESSES_DATA", "USES_RESULT_OF"], "status_scope": "both"},
      both(lambda g: summarize(g.impact("D001"))), "첫 단계는 데이터 사용 Agent, 이후는 그 결과를 쓰는 Agent")
    q("Q16", "개발·운영", "단가 조회 Agent 출력 형식을 바꾸면 어디를 점검해야 하나?", "impact",
      {"anchor": anchor("A005"), "direction": "reverse", "depth": "all", "relation_types": ["USES_RESULT_OF"], "status_scope": "both"},
      both(lambda g: summarize(g.impact("A005"))), "부서를 넘어가는 연결(구매팀 발주서 생성)을 놓치지 않음")
    p17 = GRAPHS["all"].path("A051", "A005")
    q("Q17", "개발·운영", "이사회 자료 작성 Agent에서 단가 조회 Agent까지 어떤 경로로 연결돼 있나?", "path",
      {"source": anchor("A051"), "target": anchor("A005"), "direction": "forward", "status_scope": "all"},
      {"path": [NAMES[x] for x in p17], "hops": len(p17) - 1}, "최단 경로 하나")
    q("Q18", "개발·운영", "순환 의존 관계가 있나?", "cycle",
      {"relation_types": ["USES_RESULT_OF"], "status_scope": "both"}, both(lambda g: g.cycles()),
      "탐색이 무한 반복하지 않아야 함. 자기 참조(테스트 코드 생성 Agent)는 저장 단계에서 거부되므로 순환에 없음")
    q("Q19", "개발·운영", "Salesforce를 교체하면 영향받는 Agent는?", "impact",
      {"anchor": anchor("P01"), "direction": "reverse", "depth": "all", "relation_types": ["USES_PLATFORM", "USES_RESULT_OF"], "status_scope": "both"},
      both(lambda g: summarize(g.impact("P01"))), "직접 연동 Agent와, 그 결과를 쓰는 간접 Agent를 나눠서")

    def hi_dept(g):
        c = Counter(NAMES[g.targets(a, "BELONGS_TO")[0]] if g.targets(a, "BELONGS_TO") else "(미확정)" for a in high(g))
        return dict(sorted(c.items(), key=lambda x: (-x[1], x[0])))
    q("Q20", "데이터·보안", "개인정보 등 민감도 '상' 데이터를 쓰는 Agent는 어느 부서에 많나?", "aggregate",
      {"filter": {"data.sensitivity": "상"}, "group_by": "BELONGS_TO", "status_scope": "both"}, both(hi_dept), "")
    cand = [r for r in rels if r["status"] == "CANDIDATE"]
    q("Q21", "데이터·보안", "아직 검토되지 않은 관계는?", "status",
      {"filter": {"status": "CANDIDATE"}},
      {"count": len(cand), "relations": [{"id": r["id"], "relation": f"{r['source_name']} → {r['target_name']}",
                                          "type": r["type_label"], "reason": r["review_reason"]} for r in cand]},
      "CANDIDATE 목록과 사유")
    r22 = next(r for r in rels if r["source"] == "A019" and r["target"] == "D015")
    q("Q22", "데이터·보안", "계정 권한 점검 Agent와 시스템 접근 권한 목록의 관계는 누가 확인했나?", "evidence",
      {"relation": {"source": "A019", "type": "ACCESSES_DATA", "target": "D015"}},
      {"relation_id": r22["id"], "status": r22["status"], "reviewed_by": r22["reviewed_by"], "reviewed_at": r22["reviewed_at"]},
      "검토자·검토 일시")
    q("Q23", "현업", "ChatGPT Enterprise를 쓰는 Agent는?", "unknown_entity",
      {"anchor": {"raw": "ChatGPT Enterprise", "type": "Platform"}},
      {"answer": "ChatGPT Enterprise는 등록된 플랫폼이 아닙니다. 등록된 플랫폼 목록에서 고르거나 미등록 대상으로 올려 주세요.",
       "must_not": "0개라고 답하지 않음"}, "등록되지 않은 대상은 '없음'이 아니라 '미등록'")
    amb = sorted(NAMES[i] for i in NAMES if i.startswith("A") and "분석" in NAMES[i])
    q("Q24", "개발·운영", "분석 Agent를 바꾸면 어디를 점검해야 해?", "ambiguous_anchor",
      {"anchor": {"raw": "분석 Agent", "type": "Agent"}},
      {"answer": "어느 Agent인지 골라 달라고 되묻기", "candidates": amb}, "후보를 보여주고 고른 뒤에 영향 탐색")
    q("Q25", "데이터·보안", "아직 등록되지 않은 대상은 무엇이 있나?", "pending",
      {"filter": {"pending": True}},
      [{"raw": p["raw"], "kind": p["kind"], "mentioned_by": NAMES[p["mentioned_by"]], "reason": p["reason"]} for p in pending],
      "미등록 대상 검토 항목")
    return Q


def build_dashboard(agents, rels, pending):
    A = sorted(agents)

    def view(g):
        hi = sorted(a for a in A if any(SENS[d] == "상" for d in g.targets(a, "ACCESSES_DATA")))
        dep = Counter(NAMES[g.targets(a, "BELONGS_TO")[0]] if g.targets(a, "BELONGS_TO") else "(미확정)" for a in A)
        return {"agents_by_department": dict(sorted(dep.items())),
                "agents_accessing_high_sensitivity": len(hi),
                "share_accessing_high_sensitivity": round(len(hi) / len(A), 3)}
    return {
        "total_agents": len(A),
        "agents_by_status": dict(Counter(agents[a]["status"] for a in A)),
        "data_assets_by_sensitivity": dict(Counter(d[2] for d in DATA)),
        "relationships_by_status": dict(Counter(r["status"] for r in rels)),
        "relationships_by_type": dict(Counter(r["type_label"] for r in rels)),
        "pending_entities": len(pending),
        "review_flags": sum(len(agents[a]["review"]) for a in A),
        "agents_with_review_flags": sum(1 for a in A if agents[a]["review"]),
        "by_scope": {"all_statuses": view(GRAPHS["all"]), "confirmed_only": view(GRAPHS["confirmed"])},
    }


# ---------------------------------------------------------------- outputs
def write_vocab():
    vocab = {
        "scheme": "엑시옴 표준 어휘 (SKOS 형태: prefLabel / altLabel / broader / definition)",
        "relation_types": [dict(code=c, prefLabel=l, domain=d, range=r, definition=x) for c, l, d, r, x in RELATION_TYPES],
        "direction_rule": "관계 방향은 '쓰는 쪽 → 제공하는 쪽'",
        "status_values": {"SAMPLE": "팀 설계안 예시에서 온 첫 샘플(검증 전)", "CANDIDATE": "추출·추정했지만 사람 검토 대기",
                          "CONFIRMED": "사람이 근거를 확인해 확정"},
        "sensitivity": SENSITIVITY,
        "departments": [dict(id=i, prefLabel=p, broader=b, altLabel=a, definition=d) for i, p, b, a, d in DEPARTMENTS],
        "work_types": [dict(id=i, prefLabel=p, altLabel=a, definition=d) for i, p, a, d in WORK_TYPES],
        "platforms": [dict(id=i, prefLabel=p, broader=c, altLabel=a) for i, p, c, a in PLATFORMS],
        "data_assets": [dict(id=i, prefLabel=p, sensitivity=s, broader=c, stored_in=pid, altLabel=a)
                        for i, p, s, c, pid, a in DATA],
        "agent_status": [dict(prefLabel=p, altLabel=a) for p, a in STATUS_VOCAB.items()],
        "needs_review_labels": {k: {raw: {"candidate": c, "reason": r} for raw, (c, r) in v.items()} for k, v in REVIEW.items()},
        "unregistered_labels": UNREGISTERED,
    }
    (VOCAB / "vocabulary.json").write_text(json.dumps(vocab, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def lit(s):
        return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"@ko'
    t = ["@prefix ax: <https://example.org/axiom#> .", "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .",
         "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .", "@prefix owl: <http://www.w3.org/2002/07/owl#> .", "",
         "# 클래스"]
    for c in ["Agent", "Department", "Platform", "DataAsset", "WorkType"]:
        t.append(f"ax:{c} a owl:Class .")
    t += ["", "# 관계(허용 관계 코드)"]
    for code, label, dom, rng, d in RELATION_TYPES:
        t.append(f"ax:{code} a owl:ObjectProperty ; rdfs:label {lit(label)} ; rdfs:domain ax:{dom} ; rdfs:range ax:{rng} ; skos:definition {lit(d)} .")
    t += ["ax:sensitivity a owl:DatatypeProperty ; rdfs:label \"민감도\"@ko ; rdfs:domain ax:DataAsset .", ""]
    groups = [("departments", "Department", [(i, p, a, d, b) for i, p, b, a, d in DEPARTMENTS]),
              ("work_types", "WorkType", [(i, p, a, d, None) for i, p, a, d in WORK_TYPES]),
              ("platforms", "Platform", [(i, p, a, None, c) for i, p, c, a in PLATFORMS]),
              ("data_assets", "DataAsset", [(i, p, a, None, c) for i, p, _s, c, _pid, a in DATA])]
    for scheme, cls, items in groups:
        t += [f"ax:{scheme} a skos:ConceptScheme .", ""]
        for i, p, alts, d, b in items:
            line = [f"ax:{i} a skos:Concept, ax:{cls} ; skos:inScheme ax:{scheme} ; skos:prefLabel {lit(p)}"]
            for a in alts:
                line.append(f"skos:altLabel {lit(a)}")
            if d:
                line.append(f"skos:definition {lit(d)}")
            if b:
                line.append(f"skos:note {lit('상위 분류: ' + b)}")
            if cls == "DataAsset":
                line.append(f"ax:sensitivity {lit(SENS[i])}")
                if STORED[i]:
                    line.append(f"ax:STORED_IN ax:{STORED[i]}")
            t.append(" ;\n    ".join(line) + " .")
        t.append("")
    (VOCAB / "axiom_vocabulary.ttl").write_text("\n".join(t) + "\n", encoding="utf-8")


def write_seed(agents, rels, pending):
    def w(name, header, rows):
        with open(SEED / name, "w", encoding="utf-8-sig", newline="") as f:
            cw = csv.writer(f)
            cw.writerow(header)
            cw.writerows(rows)
    w("agents.csv", ["agent_id", "name", "department_id", "owners", "work_type_id", "description", "start_date",
                     "monthly_calls", "status", "source_file", "review_flag_count"],
      [[a["id"], a["name"], a["department"], "; ".join(a["owners"]), a["work_type"], a["description"],
        a["start_date"] or "", "" if a["monthly_calls"] is None else a["monthly_calls"], a["status"], a["source_file"],
        len(a["review"])] for a in (agents[k] for k in sorted(agents))])
    w("data_resources.csv", ["data_id", "name", "sensitivity", "category", "stored_in_platform_id", "alt_labels"],
      [[i, p, s, c, pid or "", "; ".join(a)] for i, p, s, c, pid, a in DATA])
    w("departments.csv", ["department_id", "name", "division", "alt_labels"],
      [[i, p, b, "; ".join(a)] for i, p, b, a, _d in DEPARTMENTS])
    w("platforms.csv", ["platform_id", "name", "category", "alt_labels"], [[i, p, c, "; ".join(a)] for i, p, c, a in PLATFORMS])
    w("work_types.csv", ["work_type_id", "name", "definition"], [[i, p, d] for i, p, _a, d in WORK_TYPES])
    w("relationships.csv", ["rel_id", "type", "type_label", "source_id", "source_name", "target_id", "target_name",
                            "status", "evidence_count", "evidence_text", "source_file", "source_locator", "review_reason",
                            "reviewed_by", "reviewed_at"],
      [[r["id"], r["type"], r["type_label"], r["source"], r["source_name"], r["target"], r["target_name"], r["status"],
        len(r["evidence"]), " || ".join(e["text"] or "" for e in r["evidence"]),
        " || ".join(e["file"] for e in r["evidence"]), " || ".join(e["locator"] for e in r["evidence"]),
        r["review_reason"] or "", r["reviewed_by"] or "", r["reviewed_at"] or ""] for r in rels])
    w("pending_entities.csv", ["raw_label", "kind", "mentioned_by", "reason", "source_file", "source_locator"],
      [[p["raw"], p["kind"], p["mentioned_by"], p["reason"], p["evidence"]["file"], p["evidence"]["locator"]] for p in pending])


def main():
    for d in (RAW, VOCAB, SEED, KEY):
        d.mkdir(parents=True, exist_ok=True)
    write_json()
    write_yaml()
    write_csv()
    write_xlsx()
    write_docx()
    agents, rels, pending, cases = build_model()
    global GRAPHS
    GRAPHS = {"all": Graph(rels, "all"), "confirmed": Graph(rels, "confirmed")}
    write_vocab()
    write_seed(agents, rels, pending)
    for c in FREE_TEXT_CASES:
        for e in c["expected"]:
            e["existing"] = next((r["id"] + " " + r["status"] for r in rels
                                  if (r["type"], r["source"], r["target"]) == (e["type"], e["source"], e["target"])), None)
            e["source_name"], e["target_name"] = NAMES[e["source"]], NAMES[e["target"]]
    extraction = {
        "description": "일괄 등록 + AI 관계 추출 정답. 원본 5개 파일을 읽어 표준화했을 때 나와야 하는 값",
        "agents": [agents[k] for k in sorted(agents) if agents[k]["source_file"] != "seed"],
        "sample_agents": [agents[k] for k in sorted(agents) if agents[k]["source_file"] == "seed"],
        "relationships": rels,
        "pending_entities": pending,
        "special_cases_in_files": cases,
        "free_text_cases": FREE_TEXT_CASES,
    }
    (KEY / "extraction_answer_key.json").write_text(json.dumps(extraction, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    queries = build_queries(agents, rels, pending)
    (KEY / "query_golden_set.json").write_text(json.dumps(
        {"description": "자연어 질의 정답. params는 질문 해석(②)이 만들어야 하는 값, expected는 탐색 결과",
         "status_scopes": {"all_statuses": "SAMPLE·CANDIDATE·CONFIRMED 모두", "confirmed_only": "CONFIRMED만"},
         "queries": queries}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (KEY / "dashboard_expected.json").write_text(json.dumps(build_dashboard(agents, rels, pending), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"agents {len(agents)} (raw {len(agents) - len(SAMPLE_AGENTS)}), relationships {len(rels)}, "
          f"pending {len(pending)}, special cases {len(cases)}, queries {len(queries)}")
    print("relationship status:", dict(Counter(r["status"] for r in rels)))


GRAPHS = {}
if __name__ == "__main__":
    main()
