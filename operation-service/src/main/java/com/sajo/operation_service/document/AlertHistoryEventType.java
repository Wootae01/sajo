package com.sajo.operation_service.document;

public enum AlertHistoryEventType {
    ANALYZED,           // firing + LLM 분석 성공
    ANALYSIS_SKIPPED,   // firing + 분석 대상 아님(alertname 없음/전략 미등록) - 의도된 미분석
    ANALYSIS_FAILED,    // firing + 분석 시도했으나 실패(Prometheus/LLM 예외, 빈 응답)
    RESOLVED            // resolved - 분석 없음
}
