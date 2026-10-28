package com.axiom.agentmap.domain;

import java.util.EnumSet;
import java.util.Set;

/** all = SAMPLE+CANDIDATE+CONFIRMED, confirmed = CONFIRMED만. REJECTED는 어느 쪽에도 없습니다. */
public enum Scope {
    ALL("all", EnumSet.of(RelStatus.SAMPLE, RelStatus.CANDIDATE, RelStatus.CONFIRMED)),
    CONFIRMED("confirmed", EnumSet.of(RelStatus.CONFIRMED));

    private final String code;
    private final Set<RelStatus> statuses;

    Scope(String code, Set<RelStatus> statuses) {
        this.code = code;
        this.statuses = statuses;
    }

    public String code() { return code; }

    public boolean includes(RelStatus s) { return statuses.contains(s); }

    public static Scope parse(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("all")) return ALL;
        if (raw.equalsIgnoreCase("confirmed")) return CONFIRMED;
        throw new IllegalArgumentException("scope는 all 또는 confirmed만 쓸 수 있습니다: " + raw);
    }
}
