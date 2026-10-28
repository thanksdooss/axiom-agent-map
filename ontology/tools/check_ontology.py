"""온톨로지 초안 검증: 파일 파싱, 정의되지 않은 용어, 별칭 충돌, 역량 질문 실행, 예시 판정 재계산.

pip install rdflib
python3 ontology/tools/check_ontology.py
"""
import itertools
import pathlib
import sys

from rdflib import Graph, Namespace, URIRef
from rdflib.namespace import OWL, RDF, RDFS, SKOS

HERE = pathlib.Path(__file__).resolve().parent.parent
AX = Namespace("https://github.com/thanksdooss/axiom-agent-map/ontology#")
AXC = Namespace("https://github.com/thanksdooss/axiom-agent-map/concept/")
EX = Namespace("https://github.com/thanksdooss/axiom-agent-map/example/")

# 유사도 가중치(가안). 기능 쌍 점수의 최대값은 0.90, Agent 단위 항목(시스템·사용자)이 0.10.
WEIGHTS = {"task": 0.35, "input": 0.30, "output": 0.15, "domain": 0.10}
AGENT_WEIGHTS = {"system": 0.05, "user": 0.05}

problems = []

onto = Graph().parse(HERE / "axiom-ontology.ttl")
both = Graph().parse(HERE / "axiom-ontology.ttl").parse(HERE / "examples.ttl")

# 1) 정의되지 않은 ax:/axc: 용어
declared = set(onto.subjects())
for s, p, o in both:
    for term in (s, p, o):
        if isinstance(term, URIRef) and (str(term).startswith(str(AX)) or str(term).startswith(str(AXC))):
            if term not in declared:
                problems.append(f"정의되지 않은 용어: {term}")

# 2) 같은 개념 체계 안에서 별칭 하나가 두 개념을 가리키는지
for scheme in set(onto.objects(None, SKOS.inScheme)):
    seen = {}
    for c in onto.subjects(SKOS.inScheme, scheme):
        for prop in (SKOS.prefLabel, SKOS.altLabel):
            for label in onto.objects(c, prop):
                key = str(label).strip().lower()
                if key in seen and seen[key] != c:
                    problems.append(f"별칭 충돌({onto.value(scheme, SKOS.prefLabel)}): '{label}' → {seen[key]} / {c}")
                seen[key] = c

classes = len(set(onto.subjects(RDF.type, OWL.Class)))
objprops = len(set(onto.subjects(RDF.type, OWL.ObjectProperty)))
dataprops = len(set(onto.subjects(RDF.type, OWL.DatatypeProperty)))
print(f"클래스 {classes} · 관계 {objprops} · 속성 {dataprops}")
for scheme in sorted(set(onto.subjects(RDF.type, SKOS.ConceptScheme)), key=str):
    members = set(onto.subjects(SKOS.inScheme, scheme))
    leaves = [m for m in members if not any(onto.subjects(SKOS.broader, m))]
    alts = sum(len(list(onto.objects(m, SKOS.altLabel))) for m in members)
    print(f"  {onto.value(scheme, SKOS.prefLabel)}: 개념 {len(members)} (말단 {len(leaves)}) · 별칭 {alts}")

# 3) 역량 질문 실행
print("\n== 역량 질문")
blocks = (HERE / "competency-questions.rq").read_text(encoding="utf-8").split("\n#### ")[1:]
for block in blocks:
    title, query = block.split("\n", 1)
    rows = list(both.query(query))
    print(f"\n{title}  → {len(rows)}행")
    for row in rows:
        print("   ", " | ".join(str(v) for v in row))
    if not rows:
        problems.append(f"결과 없음: {title}")


# 4) 예시 판정 재계산
def broader(c):
    return set(both.objects(c, SKOS.broader))


def match(a, b):
    if a is None or b is None:
        return 0.0
    if a == b:
        return 1.0
    if b in broader(a) or a in broader(b):
        return 0.6
    if broader(a) & broader(b):
        return 0.3
    return 0.0


def caps(agent):
    out = []
    for c in both.objects(agent, AX.hasCapability):
        out.append({
            "id": c,
            "task": both.value(c, AX.performsTask),
            "input": both.value(c, AX.consumes),
            "output": both.value(c, AX.produces),
            "domain": both.value(c, AX.inDomain),
        })
    return out


def jaccard(x, y):
    return len(x & y) / len(x | y) if (x | y) else 0.0


def assess(a, b):
    ca, cb = caps(a), caps(b)
    pairs = []
    for p, q in itertools.product(ca, cb):
        m = {k: match(p[k], q[k]) for k in WEIGHTS}
        pairs.append((sum(WEIGHTS[k] * m[k] for k in WEIGHTS), m, p, q))
    best = max(pairs, key=lambda x: x[0])
    score = best[0]
    score += AGENT_WEIGHTS["system"] * jaccard(set(both.objects(a, AX.integratesWith)), set(both.objects(b, AX.integratesWith)))
    score += AGENT_WEIGHTS["user"] * jaccard(set(both.objects(a, AX.serves)), set(both.objects(b, AX.serves)))
    dup = lambda m: m["task"] == 1 and m["input"] >= 0.6 and m["output"] >= 0.6
    dup_pairs = [(p, q) for _, m, p, q in pairs if dup(m)]
    smaller = ca if len(ca) <= len(cb) else cb
    covered = {id(c) for pq in dup_pairs for c in pq}
    if dup_pairs and all(id(c) in covered for c in smaller):
        verdict = AX.DUPLICATE
    elif dup_pairs:
        verdict = AX.OVERLAP
    elif any(m["task"] == 1 for _, m, _, _ in pairs):
        verdict = AX.REUSABLE
    else:
        verdict = AX.DISTINCT
    return round(score + 1e-9, 2), verdict


name = lambda a: both.value(a, AX.agentName)
label = lambda v: both.value(v, RDFS.label)

print("\n== 예시 판정 재계산 (파일에 적힌 값과 비교)")
for sa in both.subjects(RDF.type, AX.SimilarityAssessment):
    a, b = sorted(both.objects(sa, AX.comparesAgent), key=str)
    score, verdict = assess(a, b)
    want_score, want_verdict = float(both.value(sa, AX.score)), both.value(sa, AX.verdict)
    ok = abs(score - want_score) < 1e-6 and verdict == want_verdict
    print(f"  {sa.split('/')[-1]} {name(a)} ↔ {name(b)}: 계산 {score:.2f} {label(verdict)} / 파일 {want_score:.2f} {label(want_verdict)} {'✔' if ok else '✖'}")
    if not ok:
        problems.append(f"{sa} 판정 불일치")

print("\n== 등록 전 중복 점검: NEW1과 기존 Agent")
existing = [a for a in both.subjects(RDF.type, AX.Agent) if a != EX.NEW1]
for a in sorted(existing, key=lambda x: -assess(EX.NEW1, x)[0]):
    score, verdict = assess(EX.NEW1, a)
    print(f"  {name(a)}: {score:.2f} {label(verdict)}")

print("\n문제:", problems or "없음")
sys.exit(1 if problems else 0)
