package com.sajo.operation_service.service;

import com.sajo.operation_service.client.SlackClient;
import com.sajo.operation_service.client.dto.request.SlackMessageRequest;
import com.sajo.operation_service.controller.dto.request.AlertManagerWebhookRequest.Alert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlackNotifierTest {

    private static final String THREAD_TS = "1728000000.000100";

    private final SlackClient slackClient = mock(SlackClient.class);
    private final SlackNotifier slackNotifier = new SlackNotifier(slackClient);

    private Alert alert(String status, String severity, Instant startsAt, Instant endsAt) {
        return new Alert(
                status,
                Map.of("alertname", "HighCpuUsage", "application", "trading-service", "severity", severity),
                Map.of("summary", "CPU 사용률 95% 초과", "description", "5분간 지속"),
                startsAt,
                endsAt,
                null
        );
    }

    private Alert firing(String severity) {
        return alert("firing", severity, Instant.now(), Instant.EPOCH);
    }

    private SlackMessageRequest.Attachment captureSent() {
        ArgumentCaptor<SlackMessageRequest> captor = ArgumentCaptor.forClass(SlackMessageRequest.class);
        verify(slackClient).send(captor.capture());
        return captor.getValue().attachments().get(0);
    }

    private SlackMessageRequest.Attachment captureReplied(String threadTs) {
        ArgumentCaptor<SlackMessageRequest> captor = ArgumentCaptor.forClass(SlackMessageRequest.class);
        verify(slackClient).reply(eq(threadTs), captor.capture());
        return captor.getValue().attachments().get(0);
    }

    @Test
    @DisplayName("원본은 알람 정보와 스레드 안내 문구로 새 메시지를 보내고, client가 돌려준 ts를 그대로 반환한다")
    void postOriginal_sendsAlertInfoAndReturnsTs() {
        when(slackClient.send(any())).thenReturn(Optional.of(THREAD_TS));

        Optional<String> ts = slackNotifier.postOriginal(firing("critical"));

        assertThat(ts).contains(THREAD_TS);
        SlackMessageRequest.Attachment attachment = captureSent();
        assertThat(attachment.color()).isEqualTo("danger");
        assertThat(attachment.text())
                .contains(":red_circle:")
                .contains("HighCpuUsage")
                .contains("CPU 사용률 95% 초과")
                .contains("trading-service")
                .contains("5분간 지속")
                .contains("스레드에 답글로 달립니다")
                .doesNotContain("*LLM 분석*");
    }

    @Test
    @DisplayName("원본 발송에 실패하면 빈 값을 그대로 반환한다")
    void postOriginal_sendFailed_returnsEmpty() {
        when(slackClient.send(any())).thenReturn(Optional.empty());

        assertThat(slackNotifier.postOriginal(firing("critical"))).isEmpty();
    }

    @Test
    @DisplayName("warning 알람은 warning 색상으로 보낸다")
    void postOriginal_warning_sendsWarningColor() {
        slackNotifier.postOriginal(firing("warning"));

        assertThat(captureSent().color()).isEqualTo("warning");
    }

    @Test
    @DisplayName("severity가 critical/warning이 아니면 기본(good) 색상으로 보낸다")
    void postOriginal_unknownSeverity_sendsDefaultColor() {
        slackNotifier.postOriginal(firing("info"));

        assertThat(captureSent().color()).isEqualTo("good");
    }

    @Test
    @DisplayName("threadTs가 있으면 분석 결과만 스레드 답글로 보낸다")
    void replyAnalysis_withThreadTs_repliesAnalysisOnly() {
        slackNotifier.replyAnalysis(firing("critical"), THREAD_TS, "분석 결과 텍스트");

        SlackMessageRequest.Attachment attachment = captureReplied(THREAD_TS);
        assertThat(attachment.color()).isEqualTo("danger");
        assertThat(attachment.text())
                .contains("*LLM 분석*")
                .contains("분석 결과 텍스트")
                .doesNotContain("CPU 사용률 95% 초과");
        verify(slackClient, never()).send(any());
    }

    @Test
    @DisplayName("답글 발송 결과(ts)를 그대로 돌려준다 - 실패(empty)도 버리지 않아야 이력에 전달 실패가 남는다")
    void replyAnalysis_returnsSentMessageTs() {
        when(slackClient.reply(eq(THREAD_TS), any())).thenReturn(Optional.of("1728000000.000200"));
        assertThat(slackNotifier.replyAnalysis(firing("critical"), THREAD_TS, "분석")).contains("1728000000.000200");

        when(slackClient.reply(eq(THREAD_TS), any())).thenReturn(Optional.empty());
        assertThat(slackNotifier.replyWithoutAnalysis(firing("critical"), THREAD_TS)).isEmpty();
    }

    @Test
    @DisplayName("threadTs가 없으면(원본 발송 실패) 알람 정보 + 분석을 합친 단독 메시지로 보낸다")
    void replyAnalysis_withoutThreadTs_sendsCombinedMessage() {
        slackNotifier.replyAnalysis(firing("critical"), null, "분석 결과 텍스트");

        SlackMessageRequest.Attachment attachment = captureSent();
        assertThat(attachment.text())
                .contains("HighCpuUsage")
                .contains("CPU 사용률 95% 초과")
                .contains("*LLM 분석*")
                .contains("분석 결과 텍스트");
        verify(slackClient, never()).reply(anyString(), any());
    }

    @Test
    @DisplayName("threadTs가 있으면 분석 없음 안내만 스레드 답글로 보낸다")
    void replyWithoutAnalysis_withThreadTs_repliesNotice() {
        slackNotifier.replyWithoutAnalysis(firing("critical"), THREAD_TS);

        SlackMessageRequest.Attachment attachment = captureReplied(THREAD_TS);
        assertThat(attachment.text())
                .contains("전략 미등록 또는 분석 실패")
                .doesNotContain("CPU 사용률 95% 초과");
        verify(slackClient, never()).send(any());
    }

    @Test
    @DisplayName("threadTs가 없으면 알람 정보 + 분석 없음 안내를 단독 메시지로 보낸다 - Slack에 아예 안 뜨는 회귀 방지")
    void replyWithoutAnalysis_withoutThreadTs_sendsFallbackMessage() {
        slackNotifier.replyWithoutAnalysis(firing("critical"), null);

        SlackMessageRequest.Attachment attachment = captureSent();
        assertThat(attachment.text())
                .contains("CPU 사용률 95% 초과")
                .contains("전략 미등록 또는 분석 실패")
                .doesNotContain("*LLM 분석*");
        verify(slackClient, never()).reply(anyString(), any());
    }

    @Test
    @DisplayName("resolved는 지속시간을 분/초로 계산해서 포함하고 good 색상으로 보낸다")
    void notifyResolved_includesDuration() {
        Alert alert = alert(
                "resolved", "critical",
                Instant.parse("2026-09-20T10:00:00Z"),
                Instant.parse("2026-09-20T10:01:30Z")
        );

        slackNotifier.notifyResolved(alert);

        SlackMessageRequest.Attachment attachment = captureSent();
        assertThat(attachment.color()).isEqualTo("good");
        assertThat(attachment.text()).contains("RESOLVED").contains("1분 30초");
    }

    @Test
    @DisplayName("endsAt이 startsAt보다 앞서는 비정상 데이터도 음수 대신 0초로 표시한다")
    void notifyResolved_negativeDuration_showsZero() {
        Alert alert = alert(
                "resolved", "critical",
                Instant.parse("2026-09-20T10:01:00Z"),
                Instant.parse("2026-09-20T10:00:00Z")
        );

        slackNotifier.notifyResolved(alert);

        assertThat(captureSent().text()).contains("지속시간: 0초");
    }
}
