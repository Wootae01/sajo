package com.sajo.operation_service.service;

import com.sajo.operation_service.controller.dto.request.AlertManagerWebhookRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertReceiveServiceTest {

    private static final String THREAD_TS = "1728000000.000100";

    private final SlackNotifier slackNotifier = mock(SlackNotifier.class);
    private final AlertAnalysisAsyncProcessor alertAnalysisAsyncProcessor = mock(AlertAnalysisAsyncProcessor.class);
    private final AlertReceiveService alertReceiveService =
            new AlertReceiveService(slackNotifier, alertAnalysisAsyncProcessor);

    private AlertManagerWebhookRequest.Alert createAlert(String status, String alertname) {
        return new AlertManagerWebhookRequest.Alert(
                status,
                Map.of("alertname", alertname, "application", "trading-service"),
                Map.of(),
                Instant.parse("2026-09-17T03:00:00Z"),
                Instant.parse("2026-09-17T03:05:00Z")
        );
    }

    private AlertManagerWebhookRequest request(AlertManagerWebhookRequest.Alert... alerts) {
        return new AlertManagerWebhookRequest("firing", List.of(alerts));
    }

    @Test
    @DisplayName("firing 알람은 원본을 먼저 발송하고, 받은 ts를 넘겨 비동기 분석을 시작한다")
    void receive_firing_postsOriginalThenAnalyzesWithTs() {
        AlertManagerWebhookRequest.Alert firing = createAlert("firing", "HighCpuUsage");
        when(slackNotifier.postOriginal(firing)).thenReturn(Optional.of(THREAD_TS));

        alertReceiveService.receive(request(firing));

        InOrder inOrder = inOrder(slackNotifier, alertAnalysisAsyncProcessor);
        inOrder.verify(slackNotifier).postOriginal(firing);
        inOrder.verify(alertAnalysisAsyncProcessor).analyze(firing, THREAD_TS);
    }

    @Test
    @DisplayName("원본 발송에 실패해 ts가 없어도 분석은 threadTs=null로 진행한다")
    void receive_sendOriginalToSlackFailed_analyzesWithNullTs() {
        AlertManagerWebhookRequest.Alert firing = createAlert("firing", "HighCpuUsage");
        when(slackNotifier.postOriginal(firing)).thenReturn(Optional.empty());

        alertReceiveService.receive(request(firing));

        verify(alertAnalysisAsyncProcessor).analyze(firing, null);
    }

    @Test
    @DisplayName("원본 발송 중 예상 못한 예외가 나도 분석은 threadTs=null로 진행한다")
    void receive_sendOriginalToSlackThrows_analyzesWithNullTs() {
        AlertManagerWebhookRequest.Alert firing = createAlert("firing", "HighCpuUsage");
        when(slackNotifier.postOriginal(firing)).thenThrow(new RuntimeException("예상 못한 예외"));

        alertReceiveService.receive(request(firing));

        verify(alertAnalysisAsyncProcessor).analyze(firing, null);
    }

    @Test
    @DisplayName("resolved 알람은 원본/분석 없이 복구 알림만 바로 보낸다")
    void receive_resolved_notifiesResolvedOnly() {
        AlertManagerWebhookRequest.Alert resolved = createAlert("resolved", "HighCpuUsage");

        alertReceiveService.receive(request(resolved));

        verify(slackNotifier).notifyResolved(resolved);
        verify(slackNotifier, never()).postOriginal(any());
        verify(alertAnalysisAsyncProcessor, never()).analyze(any(), any());
    }

    @Test
    @DisplayName("한 알람 처리 중 예외가 나도 같은 요청의 나머지 알람은 계속 처리한다")
    void receive_oneAlertThrows_continuesWithOthers() {
        AlertManagerWebhookRequest.Alert resolved = createAlert("resolved", "HighCpuUsage");
        AlertManagerWebhookRequest.Alert first = createAlert("firing", "HighErrorRate");
        AlertManagerWebhookRequest.Alert second = createAlert("firing", "HighLatency");
        doThrow(new RuntimeException("예상 못한 예외")).when(slackNotifier).notifyResolved(resolved);
        doThrow(new RuntimeException("분석 작업 제출 실패"))
                .when(alertAnalysisAsyncProcessor).analyze(first, THREAD_TS);
        when(slackNotifier.postOriginal(any())).thenReturn(Optional.of(THREAD_TS));

        alertReceiveService.receive(request(resolved, first, second));

        verify(alertAnalysisAsyncProcessor).analyze(second, THREAD_TS);
    }
}
