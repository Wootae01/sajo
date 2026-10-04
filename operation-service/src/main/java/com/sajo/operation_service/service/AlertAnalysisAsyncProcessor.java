package com.sajo.operation_service.service;

import com.sajo.operation_service.controller.dto.request.AlertManagerWebhookRequest;
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

    private void analyzeOne(AlertManagerWebhookRequest.Alert alert, String threadTs) {
        Optional<String> analysis;
        try {
            analysis = alertAnalyzer.analyze(alert);
        } catch (Exception e) {
            log.error("알람 분석 실패. alertname={}, application={}",
                    alert.labels().get("alertname"), alert.labels().get("application"), e);
            analysis = Optional.empty();
        }

        // 전략 미등록이든 LLM 호출 실패든, 분석이 없어도 "분석 없음"을 알린다 - 원본 발송까지 실패한 경우
        // 이 메시지가 알람의 유일한 Slack 알림이 되므로 생략하면 "Slack에 아예 안 뜸" 회귀가 생긴다.
        if (analysis.isPresent()) {
            log.info("알람 분석 결과. alertname={}, application={}\n{}",
                    alert.labels().get("alertname"),
                    alert.labels().get("application"),
                    analysis.get()
            );
            slackNotifier.replyAnalysis(alert, threadTs, analysis.get());
        } else {
            slackNotifier.replyWithoutAnalysis(alert, threadTs);
        }
    }
}
