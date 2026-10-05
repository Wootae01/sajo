package com.sajo.operation_service.service.analysis;

import com.sajo.operation_service.controller.dto.request.AlertManagerWebhookRequest;
import com.sajo.operation_service.service.strategy.StrategyDiagnosis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AlertPromptBuilderTest {

    @Test
    @DisplayName("시스템 프롬프트에 StructuredAnalysis에서 생성한 JSON 스키마가 붙는다")
    void systemPromptIncludesSchema() {
        assertThat(AlertPromptBuilder.SYSTEM_PROMPT)
                .contains("observations", "candidates", "topCauses", "nextChecks", "evidence")
                .contains("LIKELY", "RULED_OUT", "INSUFFICIENT_DATA")
                .contains("CONNECTION_EXHAUSTED", "CONSUMER_LAG");
    }

    @Test
    @DisplayName("유저 프롬프트에 원인 후보 목록이 순서대로 들어간다")
    void userPromptIncludesCandidates() {
        Instant startsAt = Instant.parse("2026-10-05T10:00:00Z");
        AlertManagerWebhookRequest.Alert alert = new AlertManagerWebhookRequest.Alert(
                "firing",
                Map.of("alertname", "HighLatency", "application", "market-service", "severity", "warning"),
                Map.of("summary", "요약", "description", "설명"),
                startsAt,
                Instant.EPOCH,
                "fp"
        );

        String prompt = AlertPromptBuilder.userPrompt(
                alert,
                new StrategyDiagnosis(startsAt, Map.of()),
                Map.of(),
                List.of("market-service", "postgres", "external-api", "host")
        );

        assertThat(prompt).contains("[Cause candidates]\nmarket-service, postgres, external-api, host");
    }
}
