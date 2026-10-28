package com.axiom.agentmap.web;

import com.axiom.agentmap.agents.AgentService;
import com.axiom.agentmap.api.AgentDtos.AgentDetail;
import com.axiom.agentmap.api.AgentDtos.AgentSummary;
import com.axiom.agentmap.api.AskDtos.AskRequest;
import com.axiom.agentmap.api.AskDtos.AskResponse;
import com.axiom.agentmap.api.ExtractionDtos.ApplyRequest;
import com.axiom.agentmap.api.ExtractionDtos.ExtractionRequest;
import com.axiom.agentmap.api.ExtractionDtos.ExtractionResult;
import com.axiom.agentmap.api.GraphDtos.CyclesResponse;
import com.axiom.agentmap.api.GraphDtos.GraphEdge;
import com.axiom.agentmap.api.GraphDtos.GraphResponse;
import com.axiom.agentmap.api.GraphDtos.NeighborhoodResponse;
import com.axiom.agentmap.api.GraphDtos.PathResponse;
import com.axiom.agentmap.api.GraphDtos.TraversalResponse;
import com.axiom.agentmap.api.ImportDtos.CommitResult;
import com.axiom.agentmap.api.ImportDtos.ImportPreview;
import com.axiom.agentmap.api.ReviewDtos.PendingEntity;
import com.axiom.agentmap.api.ReviewDtos.PendingResolve;
import com.axiom.agentmap.api.ReviewDtos.RelationshipPatch;
import com.axiom.agentmap.ask.AskService;
import com.axiom.agentmap.dashboard.DashboardService;
import com.axiom.agentmap.domain.NodeType;
import com.axiom.agentmap.domain.Scope;
import com.axiom.agentmap.extraction.ExtractionService;
import com.axiom.agentmap.graph.GraphService;
import com.axiom.agentmap.importing.ImportService;
import com.axiom.agentmap.llm.LlmClient;
import com.axiom.agentmap.review.ReviewService;
import com.axiom.agentmap.seed.DatasetLoader;
import com.axiom.agentmap.vocabulary.Vocabulary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** docs/api-contract.md의 모든 엔드포인트 (/api/v1) */
@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private final GraphService graph;
    private final AgentService agents;
    private final ReviewService review;
    private final DashboardService dashboard;
    private final ImportService imports;
    private final ExtractionService extraction;
    private final AskService ask;
    private final DatasetLoader loader;
    private final Vocabulary vocabulary;
    private final LlmClient llm;

    public ApiController(GraphService graph, AgentService agents, ReviewService review, DashboardService dashboard,
                         ImportService imports, ExtractionService extraction, AskService ask, DatasetLoader loader,
                         Vocabulary vocabulary, LlmClient llm) {
        this.graph = graph;
        this.agents = agents;
        this.review = review;
        this.dashboard = dashboard;
        this.imports = imports;
        this.extraction = extraction;
        this.ask = ask;
        this.loader = loader;
        this.vocabulary = vocabulary;
        this.llm = llm;
    }

    // ------------------------------------------------------------------ 그래프

    @GetMapping("/graph")
    public GraphResponse graph(@RequestParam(required = false) String scope) {
        return graph.graph(Scope.parse(scope));
    }

    @GetMapping("/nodes/{id}/neighborhood")
    public NeighborhoodResponse neighborhood(@PathVariable String id, @RequestParam(defaultValue = "1") int depth,
                                            @RequestParam(required = false) String scope) {
        return graph.neighborhood(id, depth, Scope.parse(scope));
    }

    @GetMapping("/path")
    public PathResponse path(@RequestParam String from, @RequestParam String to, @RequestParam(required = false) String scope) {
        return graph.path(from, to, Scope.parse(scope));
    }

    @GetMapping("/cycles")
    public CyclesResponse cycles(@RequestParam(required = false) String scope) {
        return graph.cycles(Scope.parse(scope));
    }

    // ------------------------------------------------------------------ Agent

    @GetMapping("/agents")
    public List<AgentSummary> agents(@RequestParam(required = false) String q, @RequestParam(required = false) String department,
                                     @RequestParam(required = false) String status, @RequestParam(required = false) String scope) {
        return agents.list(q, department, status, Scope.parse(scope));
    }

    @GetMapping("/agents/{id}")
    public AgentDetail agent(@PathVariable String id, @RequestParam(required = false) String scope) {
        return agents.detail(id, Scope.parse(scope));
    }

    @GetMapping("/agents/{id}/dependencies")
    public TraversalResponse dependencies(@PathVariable String id, @RequestParam(required = false) Integer depth,
                                          @RequestParam(required = false) String scope) {
        return graph.dependencies(id, depth, Scope.parse(scope));
    }

    @GetMapping("/agents/{id}/impact")
    public TraversalResponse agentImpact(@PathVariable String id, @RequestParam(required = false) Integer depth,
                                         @RequestParam(required = false) String scope) {
        return graph.impact(id, NodeType.AGENT, depth, Scope.parse(scope));
    }

    @GetMapping("/data-resources/{id}/impact")
    public TraversalResponse dataImpact(@PathVariable String id, @RequestParam(required = false) Integer depth,
                                        @RequestParam(required = false) String scope) {
        return graph.impact(id, NodeType.DATA_ASSET, depth, Scope.parse(scope));
    }

    @GetMapping("/platforms/{id}/impact")
    public TraversalResponse platformImpact(@PathVariable String id, @RequestParam(required = false) Integer depth,
                                            @RequestParam(required = false) String scope) {
        return graph.impact(id, NodeType.PLATFORM, depth, Scope.parse(scope));
    }

    // ------------------------------------------------------------------ 검토

    @GetMapping("/relationships")
    public List<GraphEdge> relationships(@RequestParam(required = false) String status, @RequestParam(required = false) String type,
                                         @RequestParam(required = false) String nodeId) {
        return review.list(status, type, nodeId);
    }

    @PatchMapping("/relationships/{id}")
    public GraphEdge patchRelationship(@PathVariable String id, @RequestBody RelationshipPatch body) {
        return review.patch(id, body);
    }

    @GetMapping("/pending-entities")
    public List<PendingEntity> pending(@RequestParam(required = false) String state) {
        return review.pendingList(state);
    }

    @PostMapping("/pending-entities/{id}/resolve")
    public PendingEntity resolvePending(@PathVariable String id, @RequestBody PendingResolve body) {
        return review.resolve(id, body);
    }

    // ------------------------------------------------------------------ 대시보드

    @GetMapping("/dashboard")
    public DashboardService.Dashboard dashboard(@RequestParam(required = false) String scope) {
        return dashboard.dashboard(Scope.parse(scope));
    }

    // ------------------------------------------------------------------ 일괄 등록

    @PostMapping(value = "/imports", consumes = "multipart/form-data")
    public ImportPreview importFiles(MultipartHttpServletRequest request) {
        List<ImportService.SourceFile> files = new ArrayList<>();
        for (List<MultipartFile> list : request.getMultiFileMap().values()) {
            for (MultipartFile f : list) {
                if (f.isEmpty()) continue;
                try {
                    files.add(new ImportService.SourceFile(f.getOriginalFilename() == null ? f.getName() : f.getOriginalFilename(), f.getBytes()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        if (files.isEmpty()) throw ApiException.badRequest("multipart files[]에 파일이 없습니다.");
        return imports.preview(files);
    }

    @PostMapping("/imports/samples")
    public ImportPreview importSamples() {
        return imports.preview(imports.sampleFiles());
    }

    @PostMapping("/imports/{batchId}/commit")
    public CommitResult commit(@PathVariable String batchId) {
        return imports.commit(batchId);
    }

    // ------------------------------------------------------------------ AI 관계 추출 · 자연어 질의

    @PostMapping("/extractions")
    public ExtractionResult extract(@RequestBody ExtractionRequest body) {
        if (body == null) throw ApiException.badRequest("본문이 필요합니다.");
        return extraction.extract(body.text(), body.contextAgentId());
    }

    @PostMapping("/extractions/apply")
    public List<GraphEdge> apply(@RequestBody ApplyRequest body) {
        return extraction.apply(body);
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest body) {
        if (body == null) throw ApiException.badRequest("본문이 필요합니다.");
        return ask.ask(body.question(), Scope.parse(body.scope()));
    }

    // ------------------------------------------------------------------ 기타

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> llmInfo = new LinkedHashMap<>();
        llmInfo.put("enabled", llm.enabled());
        llmInfo.put("provider", llm.provider());
        llmInfo.put("model", llm.model());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("llm", llmInfo);
        return out;
    }

    @GetMapping("/vocabulary")
    public JsonNode vocabulary() {
        return vocabulary.raw();
    }

    public record ResetRequest(String mode) {}

    @PostMapping("/admin/reset")
    public DatasetLoader.ResetResult reset(@RequestBody(required = false) ResetRequest body) {
        return loader.reset(DatasetLoader.parseMode(body == null ? null : body.mode()));
    }
}
