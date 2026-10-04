package com.sajo.operation_service.service;

import com.sajo.operation_service.controller.dto.request.AlertManagerWebhookRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 알람 원본/resolved는 웹훅 요청 스레드에서 바로 Slack에 보내고, LLM 분석만 비동기로 넘긴다 -
// 분석 스레드가 LLM 호출로 밀려 있어도 원본 알람 인지가 분석 시간만큼 늦어지지 않게 하기 위함.
// 원본 발송이 실패해도 5xx로 응답하지 않는다(항상 202): Alertmanager는 실패한 알람만이 아니라 그룹 전체를
// 재전송하므로, 이미 발송된 원본과 분석이 중복된다. 원본 실패 시엔 분석 완료 후 원본+분석 단독 메시지로 대체된다.
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertReceiveService {

    private final SlackNotifier slackNotifier;
    private final AlertAnalysisAsyncProcessor alertAnalysisAsyncProcessor;

    public void receive(AlertManagerWebhookRequest request) {
        request.alerts().forEach(this::receiveOne);
    }

    // 알람 단위로 예외를 흡수한다 - forEach 중 예외가 새면 그 뒤 알람들이 조용히 누락되기 때문
    private void receiveOne(AlertManagerWebhookRequest.Alert alert) {
        try {
            if (alert.isFiring()) {
                alertAnalysisAsyncProcessor.analyze(alert, sendOriginalToSlack(alert));
            } else {
                slackNotifier.notifyResolved(alert);
            }
        } catch (Exception e) {
            log.error("알람 수신 처리 실패. alertname={}, application={}",
                    alert.labels().get("alertname"), alert.labels().get("application"), e);
        }
    }

    // 원본 발송이 어떤 이유로 실패해도 분석은 진행해야 하므로 예외를 null(답글 대상 없음)로 바꾼다
    private String sendOriginalToSlack(AlertManagerWebhookRequest.Alert alert) {
        try {
            return slackNotifier.postOriginal(alert).orElse(null);
        } catch (Exception e) {
            log.error("알람 원본 발송 실패. alertname={}, application={}",
                    alert.labels().get("alertname"), alert.labels().get("application"), e);
            return null;
        }
    }
}
