package com.axiom.agentmap.dashboard;

import com.axiom.agentmap.domain.AgentEntity;
import com.axiom.agentmap.repository.AgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 중복 개발 후보 쌍. 프론트(브라우저 엔진)와 같은 규칙:
 * 점수 = 0.4 × Jaccard(이름(" Agent" 제외) 글자 bigram) + 0.6 × Jaccard(설명 글자 bigram), 공백 무시, 상위 6쌍.
 * 업무 유형이 같은지도 함께 알려 줍니다.
 */
@Service
public class SimilarityService {
    public static final int TOP = 6;

    public record Pair(String a, String aName, String b, String bName, double score, boolean sameWorkType) {}

    private final AgentRepository agents;

    public SimilarityService(AgentRepository agents) {
        this.agents = agents;
    }

    @Transactional(readOnly = true)
    public List<Pair> topPairs() {
        List<AgentEntity> all = new ArrayList<>(agents.findAll());
        all.sort(Comparator.comparing(a -> a.id));
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                AgentEntity x = all.get(i), y = all.get(j);
                double s = 0.4 * jaccard(bigrams(core(x.name)), bigrams(core(y.name)))
                        + 0.6 * jaccard(bigrams(x.description), bigrams(y.description));
                pairs.add(new Pair(x.id, x.name, y.id, y.name, Math.round(s * 10000) / 10000.0,
                        x.workTypeId != null && Objects.equals(x.workTypeId, y.workTypeId)));
            }
        }
        pairs.sort(Comparator.comparingDouble((Pair p) -> -p.score()).thenComparing(Pair::a).thenComparing(Pair::b));
        return pairs.stream().filter(p -> p.score() > 0).limit(TOP).toList();
    }

    static String core(String name) {
        return name == null ? "" : name.replaceAll("\\s*Agent$", "");
    }

    static Set<String> bigrams(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", "");
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 1 < t.length(); i++) out.add(t.substring(i, i + 2));
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        if (union.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        return (double) inter.size() / union.size();
    }
}
