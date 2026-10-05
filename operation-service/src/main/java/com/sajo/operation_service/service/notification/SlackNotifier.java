package com.sajo.operation_service.service.notification;

import com.sajo.operation_service.client.SlackClient;
import com.sajo.operation_service.client.dto.request.SlackMessageRequest;
import com.sajo.operation_service.controller.dto.request.AlertManagerWebhookRequest.Alert;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SlackNotifier {

    private static final String COLOR_CRITICAL = "danger";
    private static final String COLOR_WARNING = "warning";
    private static final String COLOR_DEFAULT = "good";

    private static final String NO_ANALYSIS_TEXT = "_LLM 분석 없음 - 전략 미등록 또는 분석 실패_";

    private final SlackClient slackClient;

    // 알람 원본을 분석 전에 먼저 발송하고, 분석 결과를 스레드 답글로 달 수 있도록 메시지 ts를 돌려준다.
    // 안내 문구는 "분석 중" 같은 진행 상태가 아니라 고정 안내라서 분석이 끝나거나 실패해도 원본을 수정할 필요가 없다.
    public Optional<String> postOriginal(Alert alert) {
        String color = severityColor(alert.labels().get("severity"));
        String message = formatHeader(alert) + "\n:thread: _LLM 분석 결과는 이 메시지의 스레드에 답글로 달립니다_";
        return slackClient.send(SlackMessageRequest.of(color, message));
    }

    // 아래 발송 메서드들은 보낸 메시지의 ts를 돌려준다(empty = 발송 실패) - 알람 이력에 Slack 전달 여부를 남기기 위함.
    // threadTs가 null이면(원본 발송 실패) 답글을 달 곳이 없으므로 원본 정보 + 분석을 합친 단독 메시지로 보낸다
    public Optional<String> replyAnalysis(Alert alert, String threadTs, String analysis) {
        String color = severityColor(alert.labels().get("severity"));
        if (threadTs == null) {
            return slackClient.send(SlackMessageRequest.of(color, formatMessage(alert, analysis)));
        }
        return slackClient.reply(threadTs, SlackMessageRequest.of(color, formatAnalysis(analysis)));
    }

    // 전략 미등록 또는 LLM 분석 실패 시에도 알람 자체는 원본 정보로라도 전달한다 -
    // slack_configs 제거 후 "분석 안 되면 Slack에 아예 안 뜸"이 되는 회귀를 막기 위함.
    // threadTs가 null이면(원본 발송 실패) 원본 정보를 포함한 단독 메시지로 보낸다.
    public Optional<String> replyWithoutAnalysis(Alert alert, String threadTs) {
        String color = severityColor(alert.labels().get("severity"));
        if (threadTs == null) {
            return slackClient.send(SlackMessageRequest.of(color, formatMessageWithoutAnalysis(alert)));
        }
        return slackClient.reply(threadTs, SlackMessageRequest.of(color, NO_ANALYSIS_TEXT));
    }

    public Optional<String> notifyResolved(Alert alert) {
        String message = formatResolvedMessage(alert);
        return slackClient.send(SlackMessageRequest.of(COLOR_DEFAULT, message));
    }

    // severity 라벨을 Slack attachment 색상(danger/warning/good)으로 변환
    private String severityColor(String severity) {
        if ("critical".equalsIgnoreCase(severity)) {
            return COLOR_CRITICAL;
        }
        if ("warning".equalsIgnoreCase(severity)) {
            return COLOR_WARNING;
        }
        return COLOR_DEFAULT;
    }

    // resolved 알림 본문 - 알람 이름/요약/지속시간
    private String formatResolvedMessage(Alert alert) {
        Duration duration = Duration.between(alert.startsAt(), alert.endsAt());
        return """
                :white_check_mark: *[RESOLVED][%s] %s* (`%s`)
                지속시간: %s
                """.formatted(
                alert.labels().get("alertname"),
                alert.annotations().get("summary"),
                alert.labels().get("application"),
                formatDuration(duration)
        );
    }

    // 지속시간을 "N분 N초" 형식으로 표시(음수는 0초로 보정)
    private String formatDuration(Duration duration) {

        if (duration.isNegative()) {
            duration = Duration.ZERO;
        }
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).getSeconds();
        if (minutes > 0) {
            return "%d분 %d초".formatted(minutes, seconds);
        }
        return "%d초".formatted(seconds);
    }

    // 원본/단독 메시지 공통 머리말 - 이모지, 알람 이름, 요약, 서비스, 설명
    private String formatHeader(Alert alert) {
        return """
                %s *[%s] %s* (`%s`)
                %s
                """.formatted(
                severityEmoji(alert.labels().get("severity")),
                alert.labels().get("alertname"),
                alert.annotations().get("summary"),
                alert.labels().get("application"),
                alert.annotations().get("description")
        );
    }

    // LLM 분석 결과 섹션
    private String formatAnalysis(String analysis) {
        return """
                *LLM 분석*

                %s
                """.formatted(analysis);
    }

    // threadTs가 없을 때(원본 발송 실패) 쓰는 단독 메시지 - 머리말 + 분석 결과
    private String formatMessage(Alert alert, String analysis) {
        return formatHeader(alert) + "\n" + formatAnalysis(analysis);
    }

    // threadTs가 없을 때(원본 발송 실패) 쓰는 단독 메시지 - 머리말 + 분석 없음 안내
    private String formatMessageWithoutAnalysis(Alert alert) {
        return formatHeader(alert) + "\n" + NO_ANALYSIS_TEXT + "\n";
    }

    // severity 라벨을 메시지 앞 이모지로 변환
    private String severityEmoji(String severity) {
        if ("critical".equalsIgnoreCase(severity)) {
            return ":red_circle:";
        }
        if ("warning".equalsIgnoreCase(severity)) {
            return ":large_yellow_circle:";
        }
        return ":white_circle:";
    }
}
