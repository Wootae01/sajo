package com.sajo.operation_service.service.receive;

import com.sajo.operation_service.controller.dto.request.AlertManagerWebhookRequest;
import com.sajo.operation_service.service.analysis.AlertAnalysisResult;
import com.sajo.operation_service.service.analysis.AlertAnalyzer;
import com.sajo.operation_service.service.history.AlertHistoryService;
import com.sajo.operation_service.service.notification.SlackNotifier;
import com.sajo.operation_service.service.notification.StructuredAnalysisFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlertAnalysisAsyncProcessor {

    private final AlertAnalyzer alertAnalyzer;
    private final SlackNotifier slackNotifier;
    private final AlertHistoryService alertHistoryService;

    // firing 알람 하나를 분석해서 원본 메시지(threadTs)의 스레드 답글로 보낸다.
    // threadTs가 null이면(원본 발송 실패) SlackNotifier가 원본 정보 + 분석을 합친 단독 메시지로 대체한다.
    // @Async 메서드라 예외가 호출자에게 전달되지 않으므로, 분석/Slack 발송 어디서 나든 여기서 로그로 남긴다.
    @Async("alertAnalysisExecutor")
    public void analyze(AlertManagerWebhookRequest.Alert alert, String threadTs) {
        try {
            analyzeOne(alert, threadTs);
        } catch (Exception e) {
            log.error("알람 처리 실패. alertname={}, application={}",
                    alert.labels().get("alertname"), alert.labels().get("application"), e);
        }
    }

    // 전략 미등록이든 LLM 호출 실패든, 분석이 없어도 "분석 없음"을 알린다 - 원본 발송까지 실패한 경우
    // 이 메시지가 알람의 유일한 Slack 알림이 되므로 생략하면 "Slack에 아예 안 뜸" 회귀가 생긴다.
    // Slack 쪽은 둘을 같은 안내로 보내지만, 이력에는 SKIPPED(의도된 미분석)/FAILED(장애)로 구분해 남긴다.
    // 이력 저장은 Slack 발송 이후에 한다 - 저장이 느리거나 실패해도 알람 전달에는 영향이 없게 하기 위함.
    private void analyzeOne(AlertManagerWebhookRequest.Alert alert, String threadTs) {
        Optional<AlertAnalysisResult> analysis;
        try {
            analysis = alertAnalyzer.analyze(alert);
        } catch (Exception e) {
            log.error("알람 분석 실패. alertname={}, application={}",
                    alert.labels().get("alertname"), alert.labels().get("application"), e);
            String messageTs = slackNotifier.replyWithoutAnalysis(alert, threadTs).orElse(null);
            alertHistoryService.recordAnalysisFailed(alert, e, threadTs, messageTs);
            return;
        }

        if (analysis.isPresent()) {
            AlertAnalysisResult result = analysis.get();
            log.info("알람 분석 결과. alertname={}, application={}\n{}",
                    alert.labels().get("alertname"),
                    alert.labels().get("application"),
                    result.response()
            );
            // Slack에는 LLM 원문(JSON)이 아니라 구조화 결과를 읽기 좋게 조립한 텍스트를 보낸다 - 원문은 이력에 남는다
            // 조립 실패도 Slack에는 분석 실패와 같이 "분석 없음"을 보낸다 - 여기서 예외가 밖으로 나가면 안내도 이력도 안 남는다.
            // 이력은 ANALYSIS_FAILED로 남기되 이미 받은 분석 결과(원문/토큰)는 같이 저장한다
            String analysisText;
            try {
                analysisText = StructuredAnalysisFormatter.format(result.structuredAnalysis(), result.validationErrors());
            } catch (Exception e) {
                log.error("분석 결과 Slack 메시지 조립 실패. alertname={}, application={}",
                        alert.labels().get("alertname"), alert.labels().get("application"), e);
                String messageTs = slackNotifier.replyWithoutAnalysis(alert, threadTs).orElse(null);
                alertHistoryService.recordFormatFailed(alert, result, e, threadTs, messageTs);
                return;
            }
            String messageTs = slackNotifier.replyAnalysis(alert, threadTs, analysisText).orElse(null);
            alertHistoryService.recordAnalyzed(alert, result, threadTs, messageTs);
        } else {
            String messageTs = slackNotifier.replyWithoutAnalysis(alert, threadTs).orElse(null);
            alertHistoryService.recordAnalysisSkipped(alert, threadTs, messageTs);
        }
    }
}
