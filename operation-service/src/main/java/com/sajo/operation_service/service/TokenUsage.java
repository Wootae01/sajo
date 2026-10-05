package com.sajo.operation_service.service;

import org.springframework.ai.chat.metadata.Usage;

// LLM 호출 1회의 토큰 사용량 - 제공자가 값을 안 주면 null로 남긴다(0으로 채우면 "0토큰 사용"과 구분이 안 됨)
public record TokenUsage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
    public static TokenUsage from(Usage usage) {
        if (usage == null) {
            return null;
        }
        return new TokenUsage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }
}
