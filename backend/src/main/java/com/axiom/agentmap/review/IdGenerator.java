package com.axiom.agentmap.review;

import com.axiom.agentmap.repository.AgentRepository;
import com.axiom.agentmap.repository.DataResourceRepository;
import com.axiom.agentmap.repository.PendingEntityRepository;
import com.axiom.agentmap.repository.RelationshipRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 새 ID: 현재 최댓값 다음 번호 (A054, D031, R0327, PE04 …) */
@Component
public class IdGenerator {
    private static final Pattern NUM = Pattern.compile("^[A-Z]+(\\d+)$");
    private final AgentRepository agents;
    private final DataResourceRepository data;
    private final RelationshipRepository relationships;
    private final PendingEntityRepository pending;

    public IdGenerator(AgentRepository agents, DataResourceRepository data, RelationshipRepository relationships,
                       PendingEntityRepository pending) {
        this.agents = agents;
        this.data = data;
        this.relationships = relationships;
        this.pending = pending;
    }

    public static int max(Collection<String> ids, String prefix) {
        int max = 0;
        for (String id : ids) {
            if (id == null || !id.startsWith(prefix)) continue;
            Matcher m = NUM.matcher(id);
            if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return max;
    }

    public int maxAgent() { return max(agents.findAll().stream().map(a -> a.id).toList(), "A"); }
    public int maxRelationship() { return max(relationships.findAll().stream().map(r -> r.id).toList(), "R"); }

    public static String agentId(int n) { return String.format("A%03d", n); }
    public static String relId(int n) { return String.format("R%04d", n); }

    public String nextData() {
        return String.format("D%03d", max(data.findAll().stream().map(d -> d.id).filter(i -> !i.startsWith("DEP")).toList(), "D") + 1);
    }

    public String nextAgent() { return agentId(maxAgent() + 1); }
    public String nextRelationship() { return relId(maxRelationship() + 1); }
    public String nextPending() { return String.format("PE%02d", max(pending.findAll().stream().map(p -> p.id).toList(), "PE") + 1); }
}
