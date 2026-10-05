package com.sajo.operation_service.service.history;

import com.sajo.operation_service.controller.dto.request.AlertManagerWebhookRequest;
import com.sajo.operation_service.document.AlertHistory;
import com.sajo.operation_service.document.AlertHistoryEventType;
import com.sajo.operation_service.repository.AlertHistoryRepository;
import com.sajo.operation_service.service.analysis.AlertAnalysisResult;
import com.sajo.operation_service.service.analysis.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertHistoryServiceTest {

    private static final String THREAD_TS = "1728000000.000100";
    private static final String MESSAGE_TS = "1728000000.000200";

    private final AlertHistoryRepository alertHistoryRepository = mock(AlertHistoryRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AlertHistoryService alertHistoryService = new AlertHistoryService(alertHistoryRepository, meterRegistry);

    private AlertManagerWebhookRequest.Alert alert(String status) {
        return new AlertManagerWebhookRequest.Alert(
                status,
                Map.of("alertname", "HighCpuUsage", "application", "trading-service", "severity", "warning", "instance", "host:8080"),
                Map.of("summary", "CPU 사용률 95% 초과"),
                Instant.parse("2026-10-05T03:00:00Z"),
                Instant.parse("2026-10-05T03:05:00Z"),
                "fp-123"
        );
    }

    private AlertHistory captureInserted() {
        ArgumentCaptor<AlertHistory> captor = ArgumentCaptor.forClass(AlertHistory.class);
        verify(alertHistoryRepository).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("ANALYZED: 알람 스냅샷(조회용 라벨은 꺼내 두고 나머지는 맵 그대로) + 분석 정보 + Slack ts를 저장한다")
    void recordAnalyzed_savesAlertAnalysisAndSlackTs() {
        AlertAnalysisResult result = new AlertAnalysisResult(
                "분석 결과", "system", "user", "gpt-test", new TokenUsage(100, 20, 120), 1500L);

        alertHistoryService.recordAnalyzed(alert("firing"), result, THREAD_TS, MESSAGE_TS);

        AlertHistory saved = captureInserted();
        assertThat(saved.getEventType()).isEqualTo(AlertHistoryEventType.ANALYZED);
        assertThat(saved.getAlert().fingerprint()).isEqualTo("fp-123");
        assertThat(saved.getAlert().alertname()).isEqualTo("HighCpuUsage");
        assertThat(saved.getAlert().application()).isEqualTo("trading-service");
        assertThat(saved.getAlert().severity()).isEqualTo("warning");
        assertThat(saved.getAlert().labels()).containsEntry("instance", "host:8080");
        assertThat(saved.getAnalysis().response()).isEqualTo("분석 결과");
        assertThat(saved.getAnalysis().model()).isEqualTo("gpt-test");
        assertThat(saved.getAnalysis().totalTokens()).isEqualTo(120);
        assertThat(saved.getAnalysis().latencyMs()).isEqualTo(1500L);
        assertThat(saved.getErrorMessage()).isNull();
        assertThat(saved.getThreadTs()).isEqualTo(THREAD_TS);
        assertThat(saved.getMessageTs()).isEqualTo(MESSAGE_TS);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("토큰 사용량이 없으면(제공자가 안 줌) 토큰 필드는 null로 남긴다 - 0과 구분")
    void recordAnalyzed_withoutTokenUsage_leavesTokensNull() {
        AlertAnalysisResult result = new AlertAnalysisResult("분석 결과", "system", "user", "gpt-test", null, 10L);

        alertHistoryService.recordAnalyzed(alert("firing"), result, THREAD_TS, MESSAGE_TS);

        assertThat(captureInserted().getAnalysis().totalTokens()).isNull();
    }

    @Test
    @DisplayName("ANALYSIS_SKIPPED: 분석 정보 없이 Slack ts만 저장한다")
    void recordAnalysisSkipped_savesWithoutAnalysis() {
        alertHistoryService.recordAnalysisSkipped(alert("firing"), THREAD_TS, MESSAGE_TS);

        AlertHistory saved = captureInserted();
        assertThat(saved.getEventType()).isEqualTo(AlertHistoryEventType.ANALYSIS_SKIPPED);
        assertThat(saved.getAnalysis()).isNull();
        assertThat(saved.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("ANALYSIS_FAILED: 원인 예외를 '타입: 메시지'로 남긴다")
    void recordAnalysisFailed_savesErrorMessage() {
        alertHistoryService.recordAnalysisFailed(
                alert("firing"), new IllegalStateException("LLM 응답이 비어 있음"), null, null);

        AlertHistory saved = captureInserted();
        assertThat(saved.getEventType()).isEqualTo(AlertHistoryEventType.ANALYSIS_FAILED);
        assertThat(saved.getErrorMessage()).isEqualTo("IllegalStateException: LLM 응답이 비어 있음");
        assertThat(saved.getThreadTs()).isNull();
        assertThat(saved.getMessageTs()).isNull();
    }

    @Test
    @DisplayName("RESOLVED: threadTs 없이 복구 알림 ts만 저장하고, fingerprint로 firing 이력과 이어진다")
    void recordResolved_savesMessageTsOnly() {
        alertHistoryService.recordResolved(alert("resolved"), MESSAGE_TS);

        AlertHistory saved = captureInserted();
        assertThat(saved.getEventType()).isEqualTo(AlertHistoryEventType.RESOLVED);
        assertThat(saved.getAlert().fingerprint()).isEqualTo("fp-123");
        assertThat(saved.getThreadTs()).isNull();
        assertThat(saved.getMessageTs()).isEqualTo(MESSAGE_TS);
    }

    @Test
    @DisplayName("firing 알람의 endsAt(Alertmanager의 0시각 '아직 안 끝남' 표시)은 null로 저장한다")
    void record_firing_storesNullEndsAt() {
        AlertManagerWebhookRequest.Alert firing = new AlertManagerWebhookRequest.Alert(
                "firing",
                Map.of("alertname", "RedisConnectionDown"),
                Map.of(),
                Instant.parse("2026-10-05T02:39:36.592Z"),
                Instant.parse("0001-01-01T00:00:00Z"),
                "fp-123"
        );

        alertHistoryService.recordAnalysisSkipped(firing, THREAD_TS, MESSAGE_TS);

        assertThat(captureInserted().getAlert().endsAt()).isNull();
    }

    @Test
    @DisplayName("resolved 알람의 endsAt은 실제 종료 시각이라 그대로 저장한다")
    void record_resolved_keepsEndsAt() {
        alertHistoryService.recordResolved(alert("resolved"), MESSAGE_TS);

        assertThat(captureInserted().getAlert().endsAt()).isEqualTo(Instant.parse("2026-10-05T03:05:00Z"));
    }

    @Test
    @DisplayName("저장 실패는 밖으로 던지지 않고 실패 카운터만 올린다 - 이력 때문에 알람 처리가 깨지면 안 됨")
    void record_repositoryThrows_doesNotPropagateAndCountsFailure() {
        when(alertHistoryRepository.insert(any(AlertHistory.class))).thenThrow(new RuntimeException("Mongo 연결 실패"));

        assertThatCode(() -> alertHistoryService.recordResolved(alert("resolved"), MESSAGE_TS))
                .doesNotThrowAnyException();

        assertThat(meterRegistry.get("alert_history_save_failures_total").counter().count()).isEqualTo(1.0);
    }
}
