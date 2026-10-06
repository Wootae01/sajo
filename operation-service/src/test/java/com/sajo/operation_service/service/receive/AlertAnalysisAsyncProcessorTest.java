package com.sajo.operation_service.service.receive;

import com.sajo.operation_service.controller.dto.request.AlertManagerWebhookRequest;
import com.sajo.operation_service.service.analysis.AlertAnalysisResult;
import com.sajo.operation_service.service.analysis.AlertAnalyzer;
import com.sajo.operation_service.service.analysis.StructuredAnalysis;
import com.sajo.operation_service.service.analysis.TokenUsage;
import com.sajo.operation_service.service.history.AlertHistoryService;
import com.sajo.operation_service.service.notification.SlackNotifier;
import com.sajo.operation_service.service.notification.StructuredAnalysisFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AlertAnalysisAsyncProcessorTest {

    private static final String THREAD_TS = "1728000000.000100";
    private static final String MESSAGE_TS = "1728000000.000200";
    private static final StructuredAnalysis ANALYSIS =
            new StructuredAnalysis(List.of("CPU 사용률 0.92"), List.of(), List.of(), List.of());
    private static final String ANALYSIS_TEXT = StructuredAnalysisFormatter.format(ANALYSIS);

    private final AlertAnalyzer alertAnalyzer = mock(AlertAnalyzer.class);
    private final SlackNotifier slackNotifier = mock(SlackNotifier.class);
    private final AlertHistoryService alertHistoryService = mock(AlertHistoryService.class);
    private final AlertAnalysisAsyncProcessor processor =
            new AlertAnalysisAsyncProcessor(alertAnalyzer, slackNotifier, alertHistoryService);

    private AlertManagerWebhookRequest.Alert createAlert(String alertname) {
        return new AlertManagerWebhookRequest.Alert(
                "firing",
                Map.of("alertname", alertname, "application", "trading-service"),
                Map.of(),
                Instant.parse("2026-09-17T03:00:00Z"),
                Instant.parse("2026-09-17T03:05:00Z"),
                null
        );
    }

    private AlertAnalysisResult result(String response) {
        return new AlertAnalysisResult(response, ANALYSIS, List.of(), "system", "user", "gpt-test", new TokenUsage(1, 2, 3), 10L);
    }

    @Test
    @DisplayName("분석 결과를 원본 메시지(threadTs)의 스레드 답글로 보낸다")
    void analyze_withAnalysis_repliesToThread() {
        AlertManagerWebhookRequest.Alert alert = createAlert("HighCpuUsage");
        when(alertAnalyzer.analyze(alert)).thenReturn(Optional.of(result("분석 결과")));

        processor.analyze(alert, THREAD_TS);

        verify(slackNotifier).replyAnalysis(alert, THREAD_TS, ANALYSIS_TEXT);
    }

    @Test
    @DisplayName("원본 발송에 실패해 threadTs가 null이어도 그대로 넘겨 분석 결과를 발송한다")
    void analyze_nullThreadTs_passesNullThrough() {
        AlertManagerWebhookRequest.Alert alert = createAlert("HighCpuUsage");
        when(alertAnalyzer.analyze(alert)).thenReturn(Optional.of(result("분석 결과")));

        processor.analyze(alert, null);

        verify(slackNotifier).replyAnalysis(alert, null, ANALYSIS_TEXT);
    }

    @Test
    @DisplayName("전략 미등록 등으로 분석이 없으면 분석 없음 안내(replyWithoutAnalysis)를 보낸다 - Slack에 아예 안 뜨는 회귀 방지")
    void analyze_noAnalysis_repliesWithoutAnalysis() {
        AlertManagerWebhookRequest.Alert alert = createAlert("UnknownAlert");
        when(alertAnalyzer.analyze(alert)).thenReturn(Optional.empty());

        processor.analyze(alert, THREAD_TS);

        verify(slackNotifier).replyWithoutAnalysis(alert, THREAD_TS);
        verify(slackNotifier, never()).replyAnalysis(eq(alert), any(), anyString());
    }

    @Test
    @DisplayName("분석 중 예외가 나도 분석 없음 안내를 보낸다")
    void analyze_analyzerThrows_repliesWithoutAnalysis() {
        AlertManagerWebhookRequest.Alert alert = createAlert("HighCpuUsage");
        when(alertAnalyzer.analyze(alert)).thenThrow(new RuntimeException("Prometheus 실패"));

        processor.analyze(alert, THREAD_TS);

        verify(slackNotifier).replyWithoutAnalysis(alert, THREAD_TS);
    }

    @Test
    @DisplayName("Slack 발송 중 예상 못한 예외가 나도 밖으로 던지지 않는다")
    void analyze_notifyThrows_doesNotPropagate() {
        AlertManagerWebhookRequest.Alert alert = createAlert("HighCpuUsage");
        when(alertAnalyzer.analyze(alert)).thenReturn(Optional.of(result("분석 결과")));
        doThrow(new RuntimeException("예상 못한 예외"))
                .when(slackNotifier).replyAnalysis(eq(alert), any(), anyString());

        assertThatCode(() -> processor.analyze(alert, THREAD_TS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("분석 성공 시 Slack 답글을 보낸 뒤 ANALYZED 이력을 원본/답글 ts와 함께 남긴다")
    void analyze_withAnalysis_recordsAnalyzedAfterSlack() {
        AlertManagerWebhookRequest.Alert alert = createAlert("HighCpuUsage");
        AlertAnalysisResult result = result("분석 결과");
        when(alertAnalyzer.analyze(alert)).thenReturn(Optional.of(result));
        when(slackNotifier.replyAnalysis(alert, THREAD_TS, ANALYSIS_TEXT)).thenReturn(Optional.of(MESSAGE_TS));

        processor.analyze(alert, THREAD_TS);

        var inOrder = inOrder(slackNotifier, alertHistoryService);
        inOrder.verify(slackNotifier).replyAnalysis(alert, THREAD_TS, ANALYSIS_TEXT);
        inOrder.verify(alertHistoryService).recordAnalyzed(alert, result, THREAD_TS, MESSAGE_TS);
    }

    @Test
    @DisplayName("분석 대상이 아니면(empty) ANALYSIS_SKIPPED 이력을 남긴다")
    void analyze_noAnalysis_recordsSkipped() {
        AlertManagerWebhookRequest.Alert alert = createAlert("UnknownAlert");
        when(alertAnalyzer.analyze(alert)).thenReturn(Optional.empty());
        when(slackNotifier.replyWithoutAnalysis(alert, THREAD_TS)).thenReturn(Optional.of(MESSAGE_TS));

        processor.analyze(alert, THREAD_TS);

        verify(alertHistoryService).recordAnalysisSkipped(alert, THREAD_TS, MESSAGE_TS);
        verify(alertHistoryService, never()).recordAnalysisFailed(any(), any(), any(), any());
    }

    @Test
    @DisplayName("분석 중 예외가 나면 SKIPPED가 아니라 ANALYSIS_FAILED 이력을 원인 예외와 함께 남긴다")
    void analyze_analyzerThrows_recordsFailedWithCause() {
        AlertManagerWebhookRequest.Alert alert = createAlert("HighCpuUsage");
        RuntimeException cause = new RuntimeException("OpenAI 타임아웃");
        when(alertAnalyzer.analyze(alert)).thenThrow(cause);
        when(slackNotifier.replyWithoutAnalysis(alert, THREAD_TS)).thenReturn(Optional.of(MESSAGE_TS));

        processor.analyze(alert, THREAD_TS);

        verify(alertHistoryService).recordAnalysisFailed(alert, cause, THREAD_TS, MESSAGE_TS);
        verify(alertHistoryService, never()).recordAnalysisSkipped(any(), any(), any());
    }

    @Test
    @DisplayName("Slack 답글 발송이 실패하면(empty) messageTs=null로 이력을 남긴다 - 사람에게 닿지 않은 알람을 이력에서 찾을 수 있게")
    void analyze_slackReplyFailed_recordsNullMessageTs() {
        AlertManagerWebhookRequest.Alert alert = createAlert("HighCpuUsage");
        AlertAnalysisResult result = result("분석 결과");
        when(alertAnalyzer.analyze(alert)).thenReturn(Optional.of(result));
        when(slackNotifier.replyAnalysis(alert, null, ANALYSIS_TEXT)).thenReturn(Optional.empty());

        processor.analyze(alert, null);

        verify(alertHistoryService).recordAnalyzed(eq(alert), eq(result), isNull(), isNull());
    }

    @Test
    @DisplayName("분석 결과를 Slack 메시지로 조립하다 예외가 나면 분석 없음 안내를 보내고 ANALYSIS_FAILED 이력을 남긴다")
    void analyze_formatThrows_repliesWithoutAnalysisAndRecordsFailed() {
        AlertManagerWebhookRequest.Alert alert = createAlert("HighCpuUsage");
        RuntimeException cause = new RuntimeException("조립 실패");
        when(alertAnalyzer.analyze(alert)).thenReturn(Optional.of(result("분석 결과")));
        when(slackNotifier.replyWithoutAnalysis(alert, null)).thenReturn(Optional.of(MESSAGE_TS));

        try (MockedStatic<StructuredAnalysisFormatter> formatter = mockStatic(StructuredAnalysisFormatter.class)) {
            formatter.when(() -> StructuredAnalysisFormatter.format(any())).thenThrow(cause);

            processor.analyze(alert, null);
        }

        verify(slackNotifier, never()).replyAnalysis(any(), any(), any());
        verify(slackNotifier).replyWithoutAnalysis(alert, null);
        verify(alertHistoryService).recordAnalysisFailed(alert, cause, null, MESSAGE_TS);
    }

    @Test
    @DisplayName("Slack 발송 중 예상 못한 예외가 나면 이력은 남기지 않는다(예외는 analyze가 흡수)")
    void analyze_notifyThrows_doesNotRecord() {
        AlertManagerWebhookRequest.Alert alert = createAlert("HighCpuUsage");
        when(alertAnalyzer.analyze(alert)).thenReturn(Optional.of(result("분석 결과")));
        doThrow(new RuntimeException("예상 못한 예외"))
                .when(slackNotifier).replyAnalysis(eq(alert), any(), anyString());

        processor.analyze(alert, THREAD_TS);

        verifyNoInteractions(alertHistoryService);
    }
}
