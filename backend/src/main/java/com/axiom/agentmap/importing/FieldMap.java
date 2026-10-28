package com.axiom.agentmap.importing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 파일마다 다른 필드 이름 → 표준 필드 (agentName / name / agent_nm / "Agent 이름" …) */
public final class FieldMap {
    public enum Field { NAME, DEPT, OWNER, TYPE, DESC, PLATFORMS, DATA, CALLS, DATE, USAGE, STATUS, NOTE }

    private static final Map<String, Field> ALIASES = new HashMap<>();

    static {
        put(Field.NAME, "agentName", "name", "agent_nm", "Agent 이름", "agent", "이름", "agent명");
        put(Field.DEPT, "dept", "team", "dept_nm", "부서", "소속", "department", "소속 부서");
        put(Field.OWNER, "owner", "maintainer", "manager", "담당자", "owners");
        put(Field.TYPE, "category", "type", "task_type", "업무 유형", "work_type", "workType", "업무 성격");
        put(Field.DESC, "description", "summary", "desc", "주요 기능", "설명", "기능");
        put(Field.PLATFORMS, "connectedPlatforms", "integrations", "system", "연동 시스템", "platforms", "플랫폼", "연동 플랫폼");
        put(Field.DATA, "dataSources", "uses_data", "data_used", "사용 데이터", "data", "데이터");
        put(Field.CALLS, "callsAgents", "depends_on", "linked_agent", "연계 Agent", "calls", "uses_result_of", "참조 Agent");
        put(Field.DATE, "launchDate", "since", "open_dt", "도입 시기", "startDate", "start_date", "운영 시작일");
        put(Field.USAGE, "monthlyUsage", "calls_per_month", "monthly_req", "월 사용량", "monthlyCalls", "monthly_calls");
        put(Field.STATUS, "status", "state", "상태", "agent_status");
        put(Field.NOTE, "note", "notes", "비고", "remark", "remarks", "메모");
    }

    private FieldMap() {}

    private static void put(Field f, String... names) {
        for (String n : names) ALIASES.put(key(n), f);
    }

    private static String key(String s) {
        return s.replaceAll("[\\s_\\-]", "").toLowerCase();
    }

    public static Field of(String header) {
        return header == null ? null : ALIASES.get(key(header));
    }

    public static boolean isList(Field f) {
        return f == Field.PLATFORMS || f == Field.DATA || f == Field.CALLS;
    }

    /** 제출 부서(파일 머리) 키 */
    public static boolean isSubmitterKey(String k) {
        return List.of("submittedby", "제출부서", "submitter", "작성부서").contains(key(k));
    }
}
