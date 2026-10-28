package com.axiom.agentmap.importing;

import com.axiom.agentmap.api.AgentDtos.ReviewFlag;
import com.axiom.agentmap.api.GraphDtos.Evidence;
import com.axiom.agentmap.api.ImportDtos.FileSummary;
import com.axiom.agentmap.api.ImportDtos.SpecialCase;
import com.axiom.agentmap.domain.RelStatus;
import com.axiom.agentmap.domain.RelType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 미리보기 결과(저장 전). commit하면 DB에 들어갑니다. */
public class ImportBatch {
    public static class Agent {
        public String id;
        public boolean isNew;
        public String name;
        public String department;
        public List<String> owners = new ArrayList<>();
        public String workType;
        public String description;
        public String startDate;
        public Integer monthlyCalls;
        public String status;
        public String sourceFile;
        public final List<ReviewFlag> review = new ArrayList<>();
        public final Map<String, Object> raw = new LinkedHashMap<>();
    }

    public static class Rel {
        public RelType type;
        public String source;
        public String target;
        public RelStatus status;
        public String reviewReason;
        public final List<Evidence> evidence = new ArrayList<>();
        public List<String> candidatesShown;
        public String duplicateOf;
        public String tentativeId;

        public String key() { return type + "|" + source + "|" + target; }
    }

    public static class Pending {
        public String raw;
        public String kind;
        public String mentionedBy;
        public String reason;
        public Evidence evidence;
    }

    public final String id;
    public final List<FileSummary> files = new ArrayList<>();
    public final List<Agent> agents = new ArrayList<>();
    public final Map<String, Rel> rels = new LinkedHashMap<>();
    public final List<Pending> pending = new ArrayList<>();
    public final List<SpecialCase> specialCases = new ArrayList<>();

    public ImportBatch(String id) {
        this.id = id;
    }

    public static String key(RelType t, String s, String g) { return t + "|" + s + "|" + g; }
}
