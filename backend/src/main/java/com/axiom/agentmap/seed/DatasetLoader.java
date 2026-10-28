package com.axiom.agentmap.seed;

import com.axiom.agentmap.config.DataPaths;
import com.axiom.agentmap.domain.AgentEntity;
import com.axiom.agentmap.domain.DataResourceEntity;
import com.axiom.agentmap.domain.DepartmentEntity;
import com.axiom.agentmap.domain.EvidenceEntity;
import com.axiom.agentmap.domain.PendingEntityRecord;
import com.axiom.agentmap.domain.PlatformEntity;
import com.axiom.agentmap.domain.RelStatus;
import com.axiom.agentmap.domain.RelType;
import com.axiom.agentmap.domain.RelationshipEntity;
import com.axiom.agentmap.domain.WorkTypeEntity;
import com.axiom.agentmap.repository.AgentRepository;
import com.axiom.agentmap.repository.DataResourceRepository;
import com.axiom.agentmap.repository.DepartmentRepository;
import com.axiom.agentmap.repository.PendingEntityRepository;
import com.axiom.agentmap.repository.PlatformRepository;
import com.axiom.agentmap.repository.RelationshipRepository;
import com.axiom.agentmap.repository.WorkTypeRepository;
import com.axiom.agentmap.vocabulary.Vocabulary;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * DB 초기화. full = 정답 DB 상태(extraction_answer_key.json 전체), sample = 팀 설계안 샘플 3개 +
 * 그 SAMPLE 관계 + 어휘 + 저장 위치(STORED_IN)만 (일괄 등록 시연용).
 */
@Service
public class DatasetLoader {
    public enum Mode { FULL, SAMPLE }

    /** 초기화가 끝나면 알립니다(미리보기 배치 비우기 등). */
    public record ResetEvent(Mode mode) {}

    public record ResetResult(String mode, int agents, int relationships, int pendingEntities) {}

    private final DataPaths paths;
    private final Vocabulary vocabulary;
    private final JsonMapper mapper;
    private final EntityManager em;
    private final AgentRepository agents;
    private final DataResourceRepository data;
    private final PlatformRepository platforms;
    private final DepartmentRepository departments;
    private final WorkTypeRepository workTypes;
    private final RelationshipRepository relationships;
    private final PendingEntityRepository pending;
    private final ApplicationEventPublisher events;

    public DatasetLoader(DataPaths paths, Vocabulary vocabulary, JsonMapper mapper, EntityManager em,
                         AgentRepository agents, DataResourceRepository data, PlatformRepository platforms,
                         DepartmentRepository departments, WorkTypeRepository workTypes,
                         RelationshipRepository relationships, PendingEntityRepository pending,
                         ApplicationEventPublisher events) {
        this.paths = paths;
        this.vocabulary = vocabulary;
        this.mapper = mapper;
        this.em = em;
        this.agents = agents;
        this.data = data;
        this.platforms = platforms;
        this.departments = departments;
        this.workTypes = workTypes;
        this.relationships = relationships;
        this.pending = pending;
        this.events = events;
    }

    public static Mode parseMode(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("full")) return Mode.FULL;
        if (raw.equalsIgnoreCase("sample")) return Mode.SAMPLE;
        throw new IllegalArgumentException("mode는 full 또는 sample이어야 합니다: " + raw);
    }

    @Transactional
    public ResetResult reset(Mode mode) {
        JsonNode key = readAnswerKey();
        em.createQuery("delete from EvidenceEntity").executeUpdate();
        em.createQuery("delete from RelationshipEntity").executeUpdate();
        em.createQuery("delete from PendingEntityRecord").executeUpdate();
        em.createQuery("delete from AgentEntity").executeUpdate();
        em.createQuery("delete from DataResourceEntity").executeUpdate();
        em.createQuery("delete from PlatformEntity").executeUpdate();
        em.createQuery("delete from DepartmentEntity").executeUpdate();
        em.createQuery("delete from WorkTypeEntity").executeUpdate();
        em.flush();
        em.clear();

        loadVocabularyNodes();

        List<AgentEntity> agentRows = new ArrayList<>();
        for (JsonNode a : key.path("sample_agents")) agentRows.add(agent(a));
        if (mode == Mode.FULL) for (JsonNode a : key.path("agents")) agentRows.add(agent(a));
        agents.saveAll(agentRows);

        List<RelationshipEntity> rels = new ArrayList<>();
        for (JsonNode r : key.path("relationships")) {
            RelStatus status = RelStatus.valueOf(r.path("status").asString());
            RelType type = RelType.valueOf(r.path("type").asString());
            if (mode == Mode.SAMPLE && status != RelStatus.SAMPLE && type != RelType.STORED_IN) continue;
            rels.add(relationship(r));
        }
        relationships.saveAll(rels);

        int pendingCount = 0;
        if (mode == Mode.FULL) {
            int n = 0;
            for (JsonNode p : key.path("pending_entities")) {
                PendingEntityRecord e = new PendingEntityRecord();
                e.id = String.format("PE%02d", ++n);
                e.rawLabel = p.path("raw").asString();
                e.kind = p.path("kind").asString();
                e.mentionedBy = p.path("mentioned_by").asString();
                e.reason = p.path("reason").asString();
                e.evidenceFile = text(p.path("evidence").path("file"));
                e.evidenceLocator = text(p.path("evidence").path("locator"));
                e.evidenceText = text(p.path("evidence").path("text"));
                e.state = "OPEN";
                pending.save(e);
                pendingCount++;
            }
        }
        em.flush();
        events.publishEvent(new ResetEvent(mode));
        return new ResetResult(mode.name().toLowerCase(), agentRows.size(), rels.size(), pendingCount);
    }

    private JsonNode readAnswerKey() {
        try {
            return mapper.readTree(Files.readString(paths.answerKey()));
        } catch (IOException e) {
            throw new UncheckedIOException("정답 파일을 읽을 수 없습니다: " + paths.answerKey(), e);
        }
    }

    private void loadVocabularyNodes() {
        for (Vocabulary.Concept c : vocabulary.departments()) {
            DepartmentEntity d = new DepartmentEntity();
            d.id = c.id();
            d.name = c.prefLabel();
            d.division = c.broader();
            d.altLabels = String.join("; ", c.altLabels());
            departments.save(d);
        }
        for (Vocabulary.Concept c : vocabulary.workTypes()) {
            WorkTypeEntity w = new WorkTypeEntity();
            w.id = c.id();
            w.name = c.prefLabel();
            w.definition = c.definition();
            w.altLabels = String.join("; ", c.altLabels());
            workTypes.save(w);
        }
        for (Vocabulary.Concept c : vocabulary.platforms()) {
            PlatformEntity p = new PlatformEntity();
            p.id = c.id();
            p.name = c.prefLabel();
            p.category = c.broader();
            p.altLabels = String.join("; ", c.altLabels());
            platforms.save(p);
        }
        for (Vocabulary.Concept c : vocabulary.dataAssets()) {
            DataResourceEntity d = new DataResourceEntity();
            d.id = c.id();
            d.name = c.prefLabel();
            d.sensitivity = c.sensitivity();
            d.category = c.broader();
            d.storedInPlatformId = c.storedIn();
            d.altLabels = String.join("; ", c.altLabels());
            data.save(d);
        }
    }

    private AgentEntity agent(JsonNode a) {
        AgentEntity e = new AgentEntity();
        e.id = a.path("id").asString();
        e.name = a.path("name").asString();
        e.departmentId = text(a.path("department"));
        List<String> owners = new ArrayList<>();
        for (JsonNode o : a.path("owners")) owners.add(o.asString());
        e.owners = String.join("; ", owners);
        e.workTypeId = text(a.path("work_type"));
        e.description = text(a.path("description"));
        e.startDate = text(a.path("start_date"));
        e.monthlyCalls = a.path("monthly_calls").isNumber() ? a.path("monthly_calls").asInt() : null;
        e.status = text(a.path("status"));
        e.sourceFile = text(a.path("source_file"));
        e.reviewFlagsJson = mapper.writeValueAsString(a.path("review"));
        e.rawJson = a.has("raw") ? mapper.writeValueAsString(a.path("raw")) : null;
        return e;
    }

    private RelationshipEntity relationship(JsonNode r) {
        RelationshipEntity e = new RelationshipEntity();
        e.id = r.path("id").asString();
        e.type = RelType.valueOf(r.path("type").asString());
        e.sourceId = r.path("source").asString();
        e.targetId = r.path("target").asString();
        e.status = RelStatus.valueOf(r.path("status").asString());
        e.reviewReason = text(r.path("review_reason"));
        e.reviewedBy = text(r.path("reviewed_by"));
        e.reviewedAt = text(r.path("reviewed_at"));
        if (r.has("candidates_shown")) {
            List<String> c = new ArrayList<>();
            for (JsonNode x : r.path("candidates_shown")) c.add(x.asString());
            e.candidatesShown = String.join(", ", c);
        }
        for (JsonNode ev : r.path("evidence")) {
            e.addEvidence(new EvidenceEntity(text(ev.path("file")), text(ev.path("locator")), text(ev.path("text")),
                    text(ev.path("match")), text(ev.path("direction_note"))));
        }
        return e;
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asString();
    }

}
