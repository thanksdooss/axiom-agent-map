package com.axiom.agentmap.importing;

import com.axiom.agentmap.api.AgentDtos.ImportAgent;
import com.axiom.agentmap.api.GraphDtos.Evidence;
import com.axiom.agentmap.api.ImportDtos.CommitResult;
import com.axiom.agentmap.api.ImportDtos.FileSummary;
import com.axiom.agentmap.api.ImportDtos.ImportEdge;
import com.axiom.agentmap.api.ImportDtos.ImportPreview;
import com.axiom.agentmap.api.ReviewDtos.PendingEntity;
import com.axiom.agentmap.config.DataPaths;
import com.axiom.agentmap.domain.AgentEntity;
import com.axiom.agentmap.domain.EvidenceEntity;
import com.axiom.agentmap.domain.PendingEntityRecord;
import com.axiom.agentmap.domain.RelStatus;
import com.axiom.agentmap.domain.RelType;
import com.axiom.agentmap.domain.RelationshipEntity;
import com.axiom.agentmap.domain.Scope;
import com.axiom.agentmap.graph.GraphStore;
import com.axiom.agentmap.graph.GraphView;
import com.axiom.agentmap.llm.LlmClient;
import com.axiom.agentmap.repository.AgentRepository;
import com.axiom.agentmap.repository.PendingEntityRepository;
import com.axiom.agentmap.repository.RelationshipRepository;
import com.axiom.agentmap.review.IdGenerator;
import com.axiom.agentmap.seed.DatasetLoader;
import com.axiom.agentmap.vocabulary.Lexicon;
import com.axiom.agentmap.vocabulary.Vocabulary;
import com.axiom.agentmap.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 일괄 등록: 파일 파싱 → (Word는 LLM 또는 규칙 추출) → 어휘 표준화 → 미리보기 → 저장.
 * 새 Agent ID는 파일 이름 순서(01 json → 05 docx)대로 현재 최댓값 다음 번호를 줍니다.
 */
@Service
public class ImportService {
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    public static final String AUTO_REVIEWER = "표준 어휘 자동 확정";

    public record SourceFile(String name, byte[] bytes) {}

    private final GraphStore store;
    private final AgentRepository agents;
    private final RelationshipRepository relationships;
    private final PendingEntityRepository pendingRepo;
    private final IdGenerator ids;
    private final DataPaths paths;
    private final Vocabulary vocabulary;
    private final LlmClient llm;
    private final JsonMapper mapper;
    private final Map<String, ImportBatch> batches = new ConcurrentHashMap<>();

    public ImportService(GraphStore store, AgentRepository agents, RelationshipRepository relationships,
                         PendingEntityRepository pendingRepo, IdGenerator ids, DataPaths paths, Vocabulary vocabulary,
                         LlmClient llm, JsonMapper mapper) {
        this.store = store;
        this.agents = agents;
        this.relationships = relationships;
        this.pendingRepo = pendingRepo;
        this.ids = ids;
        this.paths = paths;
        this.vocabulary = vocabulary;
        this.llm = llm;
        this.mapper = mapper;
    }

    @EventListener
    public void onReset(DatasetLoader.ResetEvent e) {
        batches.clear();
    }

    /** data/raw의 5개 파일 */
    public List<SourceFile> sampleFiles() {
        try (Stream<Path> s = Files.list(paths.rawDir())) {
            List<SourceFile> out = new ArrayList<>();
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith(".")) continue;
                out.add(new SourceFile(n, Files.readAllBytes(p)));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("data/raw를 읽을 수 없습니다: " + paths.rawDir(), e);
        }
    }

    @Transactional(readOnly = true)
    public ImportPreview preview(List<SourceFile> files) {
        if (files == null || files.isEmpty()) throw ApiException.badRequest("파일이 없습니다.");
        List<SourceFile> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(SourceFile::name));
        Lexicon lex = store.lexicon();
        GraphView existing = store.view(Scope.ALL);
        ImportBatch batch = new ImportBatch(UUID.randomUUID().toString());

        // 1) 파싱 + Agent ID 정하기 (파일 순서대로)
        List<ParsedFile> parsed = new ArrayList<>();
        for (SourceFile f : sorted) parsed.add(SubmissionParsers.parse(f.name(), f.bytes()));
        int next = ids.maxAgent();
        Map<String, Boolean> isNew = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (ParsedFile pf : parsed) {
            List<String> names = pf.format().equals("docx")
                    ? pf.sections().stream().map(ParsedFile.NarrativeSection::agentName).toList()
                    : pf.agents().stream().map(SubmittedAgent::nameText).toList();
            for (String n : names) {
                if (n == null || n.isBlank()) continue;
                String id = lex.agentIdByName(n);
                if (id == null) {
                    id = IdGenerator.agentId(++next);
                    lex.addAgent(id, n.strip());
                    isNew.put(id, true);
                } else {
                    isNew.putIfAbsent(id, false);
                }
                order.add(id);
            }
        }

        // 2) Word 서술형 추출 (모든 Agent 이름을 안 뒤에)
        Map<String, List<SubmittedAgent>> extracted = new LinkedHashMap<>();
        for (ParsedFile pf : parsed) {
            if (!pf.format().equals("docx")) {
                batch.files.add(new FileSummary(pf.name(), pf.format(), pf.agents().size(), "structured"));
                continue;
            }
            List<SubmittedAgent> list = null;
            String parser = "rule";
            if (llm.enabled()) {
                try {
                    list = new LlmNarrativeExtractor(llm, mapper).extract(pf.name(), pf.sections(), lex);
                    parser = "llm";
                } catch (RuntimeException e) {
                    log.warn("LLM 서술형 추출 실패 — 규칙 기반으로 대신합니다: {}", e.getMessage());
                }
            }
            if (list == null) {
                NarrativeRuleExtractor rule = new NarrativeRuleExtractor();
                list = new ArrayList<>();
                for (int i = 0; i < pf.sections().size(); i++) list.add(rule.extract(pf.name(), i, pf.sections().get(i), lex));
            }
            extracted.put(pf.name(), list);
            batch.files.add(new FileSummary(pf.name(), pf.format(), list.size(), parser));
        }

        // 3) 표준화 (파일 순서)
        ImportNormalizer norm = new ImportNormalizer(lex, batch);
        Set<String> done = new HashSet<>();
        for (ParsedFile pf : parsed) {
            List<SubmittedAgent> list = pf.format().equals("docx") ? extracted.get(pf.name()) : pf.agents();
            for (SubmittedAgent s : list) {
                if (s.nameText().isBlank()) continue;
                String id = lex.agentIdByName(s.nameText());
                if (!done.add(id)) continue; // 같은 배치에서 같은 이름이 다시 나오면 첫 번째만 씀
                batch.agents.add(norm.normalize(s, id, isNew.getOrDefault(id, true)));
            }
        }
        // 저장 위치(STORED_IN)는 어휘에서 오므로 배치에서 만들지 않습니다.

        // 4) 기존 관계와 중복 표시 + 임시 ID
        int relNo = ids.maxRelationship();
        List<ImportBatch.Rel> fresh = new ArrayList<>();
        for (ImportBatch.Rel r : batch.rels.values()) {
            GraphView.Rel ex = existing.find(r.type, r.source, r.target);
            if (ex != null) {
                r.duplicateOf = ex.id();
                r.tentativeId = ex.id();
            } else {
                fresh.add(r);
            }
        }
        fresh.sort(relOrder());
        for (ImportBatch.Rel r : fresh) r.tentativeId = IdGenerator.relId(++relNo);
        batches.put(batch.id, batch);
        return toPreview(batch, lex, existing);
    }

    private static Comparator<ImportBatch.Rel> relOrder() {
        return Comparator.comparing((ImportBatch.Rel r) -> r.source).thenComparingInt(r -> r.type.ordinal())
                .thenComparing(r -> r.target);
    }

    private ImportPreview toPreview(ImportBatch b, Lexicon lex, GraphView existing) {
        List<ImportAgent> as = new ArrayList<>();
        for (ImportBatch.Agent a : b.agents) {
            List<String> platforms = new ArrayList<>(), data = new ArrayList<>();
            boolean high = false;
            for (ImportBatch.Rel r : b.rels.values()) {
                if (!r.source.equals(a.id)) continue;
                if (r.type == RelType.USES_PLATFORM) platforms.add(r.target);
                if (r.type == RelType.ACCESSES_DATA) {
                    data.add(r.target);
                    GraphView.Node n = existing.node(r.target);
                    if (n != null && "상".equals(n.sensitivity())) high = true;
                }
            }
            platforms.sort(String::compareTo);
            data.sort(String::compareTo);
            as.add(new ImportAgent(a.id, a.name, a.department, a.department == null ? null : lex.name(a.department),
                    a.workType, a.workType == null ? null : lex.name(a.workType), a.status, a.owners, platforms, data, high,
                    a.review.size(), a.isNew, a.review, a.sourceFile, a.description, a.startDate, a.monthlyCalls));
        }
        List<ImportEdge> es = new ArrayList<>();
        for (ImportBatch.Rel r : b.rels.values()) {
            es.add(new ImportEdge(r.tentativeId, r.type, vocabulary.relationLabel(r.type), r.source, r.target,
                    lex.name(r.source), lex.name(r.target), r.status, r.reviewReason, List.copyOf(r.evidence), null, null,
                    r.candidatesShown, r.duplicateOf));
        }
        List<PendingEntity> ps = new ArrayList<>();
        int n = 0;
        for (ImportBatch.Pending p : b.pending) {
            ps.add(new PendingEntity("NEW-" + (++n), p.raw, p.kind, p.mentionedBy, lex.name(p.mentionedBy), p.reason,
                    p.evidence, "OPEN"));
        }
        return new ImportPreview(b.id, b.files, as, es, ps, b.specialCases);
    }

    @Transactional
    public CommitResult commit(String batchId) {
        ImportBatch b = batches.remove(batchId);
        if (b == null) throw ApiException.notFound("미리보기 배치를 찾을 수 없습니다(이미 저장했거나 초기화됨): " + batchId);
        int addedAgents = 0;
        for (ImportBatch.Agent a : b.agents) {
            if (!a.isNew || agents.existsById(a.id)) continue;
            AgentEntity e = new AgentEntity();
            e.id = a.id;
            e.name = a.name;
            e.departmentId = a.department;
            e.owners = String.join("; ", a.owners);
            e.workTypeId = a.workType;
            e.description = a.description;
            e.startDate = a.startDate;
            e.monthlyCalls = a.monthlyCalls;
            e.status = a.status;
            e.sourceFile = a.sourceFile;
            e.reviewFlagsJson = mapper.writeValueAsString(a.review);
            e.rawJson = mapper.writeValueAsString(a.raw);
            agents.save(e);
            addedAgents++;
        }
        agents.flush();

        GraphView existing = store.view(Scope.ALL);
        String today = paths.today();
        int relNo = ids.maxRelationship();
        int added = 0, merged = 0;
        List<ImportBatch.Rel> fresh = new ArrayList<>();
        for (ImportBatch.Rel r : b.rels.values()) {
            GraphView.Rel ex = existing.find(r.type, r.source, r.target);
            if (ex == null) {
                fresh.add(r);
                continue;
            }
            RelationshipEntity e = relationships.findById(ex.id()).orElseThrow();
            for (Evidence ev : r.evidence) {
                boolean dup = e.evidence.stream().anyMatch(x -> Objects.equals(x.file, ev.file())
                        && Objects.equals(x.locator, ev.locator()) && Objects.equals(x.text, ev.text()));
                if (dup) continue;
                e.addEvidence(new EvidenceEntity(ev.file(), ev.locator(), ev.text(), ev.match(), ev.directionNote()));
                merged++;
            }
            relationships.save(e);
        }
        fresh.sort(relOrder());
        for (ImportBatch.Rel r : fresh) {
            RelationshipEntity e = new RelationshipEntity();
            e.id = IdGenerator.relId(++relNo);
            e.type = r.type;
            e.sourceId = r.source;
            e.targetId = r.target;
            e.status = r.status;
            e.reviewReason = r.reviewReason;
            if (r.status == RelStatus.CONFIRMED) {
                e.reviewedBy = AUTO_REVIEWER;
                e.reviewedAt = today;
            }
            if (r.candidatesShown != null) e.candidatesShown = String.join(", ", r.candidatesShown);
            for (Evidence ev : r.evidence) {
                e.addEvidence(new EvidenceEntity(ev.file(), ev.locator(), ev.text(), ev.match(), ev.directionNote()));
            }
            relationships.save(e);
            added++;
        }
        relationships.flush();

        int pendingAdded = 0;
        List<PendingEntityRecord> current = pendingRepo.findAll();
        for (ImportBatch.Pending p : b.pending) {
            boolean dup = current.stream().anyMatch(x -> x.rawLabel.equals(p.raw) && Objects.equals(x.mentionedBy, p.mentionedBy));
            if (dup) continue;
            PendingEntityRecord e = new PendingEntityRecord();
            e.id = ids.nextPending();
            e.rawLabel = p.raw;
            e.kind = p.kind;
            e.mentionedBy = p.mentionedBy;
            e.reason = p.reason;
            e.evidenceFile = p.evidence == null ? null : p.evidence.file();
            e.evidenceLocator = p.evidence == null ? null : p.evidence.locator();
            e.evidenceText = p.evidence == null ? null : p.evidence.text();
            e.state = "OPEN";
            pendingRepo.saveAndFlush(e);
            current.add(e);
            pendingAdded++;
        }
        return new CommitResult(addedAgents, added, merged, pendingAdded);
    }
}
