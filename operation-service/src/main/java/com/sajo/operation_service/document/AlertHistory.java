package com.sajo.operation_service.document;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

// operation-service가 받은 알람 1건을 어떻게 처리했는지(분석 여부, Slack 전달 여부) 남기는 이력.
// 생성 후 수정하지 않는 append-only 기록이다 - resolved도 firing 문서를 고치지 않고 별도 문서로 쌓고 fingerprint + startsAt으로 잇는다
// (firing 분석은 별도 풀에서 늦게 끝나므로, 수정 방식이면 resolved가 먼저 도착했을 때 고칠 문서가 없다).
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Document(collection = "p_alert_histories")
@CompoundIndex(name = "idx_application_starts_at", def = "{'alert.application': 1, 'alert.startsAt': -1}")
@CompoundIndex(name = "idx_event_type_created_at", def = "{'eventType': 1, 'createdAt': -1}")
@CompoundIndex(name = "idx_fingerprint_starts_at", def = "{'alert.fingerprint': 1, 'alert.startsAt': 1}")
public class AlertHistory {

    @Id
    private String id;

    private AlertHistoryEventType eventType;

    private AlertSnapshot alert;

    private AnalysisSnapshot analysis;  // ANALYZED일 때만

    private String errorMessage;        // ANALYSIS_FAILED일 때만

    // Slack 전달 여부 - null이면 해당 메시지 발송 실패.
    // firing: threadTs=원본 메시지, messageTs=분석 답글(원본 실패 시 원본+분석 단독 메시지)
    // resolved: threadTs 없음, messageTs=복구 알림
    // 즉 messageTs == null이면 이 처리 결과가 Slack에 닿지 않은 것, firing에서 둘 다 null이면 알람 자체가 사람에게 닿지 않은 것
    private String threadTs;

    private String messageTs;

    private Instant createdAt;

    private AlertHistory(
            AlertHistoryEventType eventType,
            AlertSnapshot alert,
            AnalysisSnapshot analysis,
            String errorMessage,
            String threadTs,
            String messageTs
    ) {
        this.eventType = eventType;
        this.alert = alert;
        this.analysis = analysis;
        this.errorMessage = errorMessage;
        this.threadTs = threadTs;
        this.messageTs = messageTs;
        this.createdAt = Instant.now();
    }

    public static AlertHistory analyzed(AlertSnapshot alert, AnalysisSnapshot analysis, String threadTs, String messageTs) {
        return new AlertHistory(AlertHistoryEventType.ANALYZED, alert, analysis, null, threadTs, messageTs);
    }

    public static AlertHistory analysisSkipped(AlertSnapshot alert, String threadTs, String messageTs) {
        return new AlertHistory(AlertHistoryEventType.ANALYSIS_SKIPPED, alert, null, null, threadTs, messageTs);
    }

    public static AlertHistory analysisFailed(AlertSnapshot alert, String errorMessage, String threadTs, String messageTs) {
        return new AlertHistory(AlertHistoryEventType.ANALYSIS_FAILED, alert, null, errorMessage, threadTs, messageTs);
    }

    public static AlertHistory resolved(AlertSnapshot alert, String messageTs) {
        return new AlertHistory(AlertHistoryEventType.RESOLVED, alert, null, null, null, messageTs);
    }

    // fingerprint는 라벨 집합 해시라 같은 알람이 다시 울려도 같다 - 한 번의 발생은 fingerprint + startsAt으로 구분한다.
    // 자주 조회할 라벨(alertname/application/severity)은 꺼내 두고, 알람마다 키가 다른 나머지는 맵 그대로 둔다
    public record AlertSnapshot(
            String fingerprint,
            String alertname,
            String application,
            String severity,
            Map<String, String> labels,
            Map<String, String> annotations,
            Instant startsAt,
            Instant endsAt
    ) {
    }

    public record AnalysisSnapshot(
            String response,
            String systemPrompt,
            String userPrompt,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            long latencyMs
    ) {
    }
}
