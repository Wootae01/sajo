package com.sajo.operation_service.service.analysis;

// LLM 분석 1회의 결과 - Slack에는 response만 보내고, 나머지는 이력(감사)용으로 함께 남긴다
public record AlertAnalysisResult(
        String response,
        String systemPrompt,
        String userPrompt,
        String model,
        TokenUsage tokenUsage,
        long latencyMs          // LLM 호출 구간만 측정 (Prometheus 진단 조회 시간은 제외)
) {
}
