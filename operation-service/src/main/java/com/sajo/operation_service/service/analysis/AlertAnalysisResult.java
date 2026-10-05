package com.sajo.operation_service.service.analysis;

import java.util.List;

// LLM 분석 1회의 결과 - Slack에는 structuredAnalysis를 조립한 텍스트를 보내고, 나머지는 이력(감사)용으로 함께 남긴다
public record AlertAnalysisResult(
        String response,                        // LLM 응답 원문(JSON 문자열) - 파싱 결과와 별개로 원문을 남겨 재검토할 수 있게 한다
        StructuredAnalysis structuredAnalysis,  // response를 파싱한 결과
        List<String> validationErrors,          // 내용 규칙 위반(후보 누락 등, StructuredAnalysisValidator) - 빈 목록 = 위반 없음
        String systemPrompt,
        String userPrompt,
        String model,
        TokenUsage tokenUsage,
        long latencyMs          // LLM 호출 구간만 측정 (Prometheus 진단 조회 시간은 제외)
) {
}
