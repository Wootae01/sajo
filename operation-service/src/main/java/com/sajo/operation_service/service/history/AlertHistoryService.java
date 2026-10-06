package com.sajo.operation_service.service.history;

import com.sajo.operation_service.controller.dto.request.AlertManagerWebhookRequest.Alert;
import com.sajo.operation_service.document.AlertHistory;
import com.sajo.operation_service.document.AlertHistory.AlertSnapshot;
import com.sajo.operation_service.document.AlertHistory.AnalysisSnapshot;
import com.sajo.operation_service.document.AlertHistory.CandidateSnapshot;
import com.sajo.operation_service.document.AlertHistory.EvidenceSnapshot;
import com.sajo.operation_service.document.AlertHistory.RankedCauseSnapshot;
import com.sajo.operation_service.document.AlertHistory.StructuredAnalysisSnapshot;
import com.sajo.operation_service.repository.AlertHistoryRepository;
import com.sajo.operation_service.service.analysis.AlertAnalysisResult;
import com.sajo.operation_service.service.analysis.StructuredAnalysis;
import com.sajo.operation_service.service.analysis.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

// 알람 처리 이력 기록. 이력은 부가 기록이라 저장 실패가 알람 처리(Slack 발송)를 깨뜨리면 안 되므로
// 모든 record 메서드는 예외를 밖으로 던지지 않는다 - 실패는 로그 + alert_history_save_failures_total로만 남긴다.
// 저장은 전용 풀(alertHistoryExecutor)에서 비동기로 한다 - Mongo 장애 시 저장 1건이 타임아웃(약 2초)까지 스레드를 붙잡는데,
// 호출한 알람 처리 스레드(원본 발송 전용 alertNotifyExecutor 등)가 그 대기에 묶여 다음 알람 발송이 밀리지 않게 하기 위함.
@Slf4j
@Service
public class AlertHistoryService {

    private final AlertHistoryRepository alertHistoryRepository;
    private final Counter saveFailureCounter;

    public AlertHistoryService(AlertHistoryRepository alertHistoryRepository, MeterRegistry meterRegistry) {
        this.alertHistoryRepository = alertHistoryRepository;
        this.saveFailureCounter = Counter.builder("alert_history_save_failures_total")
                .description("알람 처리 이력(Mongo) 저장 실패 횟수")
                .register(meterRegistry);
    }

    @Async("alertHistoryExecutor")
    public void recordAnalyzed(Alert alert, AlertAnalysisResult result, String threadTs, String messageTs) {
        save(alert, () -> AlertHistory.analyzed(toSnapshot(alert), toSnapshot(result), threadTs, messageTs));
    }

    @Async("alertHistoryExecutor")
    public void recordAnalysisSkipped(Alert alert, String threadTs, String messageTs) {
        save(alert, () -> AlertHistory.analysisSkipped(toSnapshot(alert), threadTs, messageTs));
    }

    @Async("alertHistoryExecutor")
    public void recordAnalysisFailed(Alert alert, Exception cause, String threadTs, String messageTs) {
        String errorMessage = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        save(alert, () -> AlertHistory.analysisFailed(toSnapshot(alert), errorMessage, threadTs, messageTs));
    }

    @Async("alertHistoryExecutor")
    public void recordResolved(Alert alert, String messageTs) {
        save(alert, () -> AlertHistory.resolved(toSnapshot(alert), messageTs));
    }

    // 문서 생성(스냅샷 변환)까지 try 안에 둔다 - 변환 중 예상 못한 예외도 호출자에게 새면 안 되기 때문
    private void save(Alert alert, Supplier<AlertHistory> historySupplier) {
        try {
            alertHistoryRepository.insert(historySupplier.get());
        } catch (Exception e) {
            saveFailureCounter.increment();
            log.error("알람 이력 저장 실패. alertname={}, application={}",
                    alert.labels().get("alertname"), alert.labels().get("application"), e);
        }
    }

    // firing 알람의 endsAt은 Alertmanager가 "아직 안 끝남"을 Go 시간 기본값(0001-01-01T00:00:00Z)으로 보낸다 -
    // 그대로 저장하면 endsAt 기간 조회/정렬/지속시간 계산이 틀어지므로 null로 남긴다(실제 종료 시각은 RESOLVED 문서에 있음)
    private AlertSnapshot toSnapshot(Alert alert) {
        Instant endsAt = alert.isFiring() ? null : alert.endsAt();
        return new AlertSnapshot(
                alert.fingerprint(),
                alert.labels().get("alertname"),
                alert.labels().get("application"),
                alert.labels().get("severity"),
                alert.labels(),
                alert.annotations(),
                alert.startsAt(),
                endsAt
        );
    }

    private AnalysisSnapshot toSnapshot(AlertAnalysisResult result) {
        TokenUsage usage = result.tokenUsage();
        return new AnalysisSnapshot(
                result.response(),
                toSnapshot(result.structuredAnalysis()),
                result.validationErrors(),
                result.systemPrompt(),
                result.userPrompt(),
                result.model(),
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                usage == null ? null : usage.totalTokens(),
                result.latencyMs()
        );
    }

    // 응답 타입 -> 이력용 타입. 받은 그대로 남기는 게 목적이라 null 목록/null 원소도 거르지 않고 그대로 옮긴다
    private StructuredAnalysisSnapshot toSnapshot(StructuredAnalysis analysis) {
        if (analysis == null) {
            return null;
        }
        return new StructuredAnalysisSnapshot(
                analysis.observations(),
                mapAll(analysis.candidates(), candidate -> new CandidateSnapshot(
                        candidate.component(),
                        name(candidate.verdict()),
                        name(candidate.category()),
                        mapAll(candidate.evidence(), evidence -> new EvidenceSnapshot(evidence.metric(), evidence.value())),
                        candidate.reasoning()
                )),
                mapAll(analysis.topCauses(), cause -> new RankedCauseSnapshot(
                        cause.component(),
                        name(cause.category()),
                        cause.reasoning()
                )),
                analysis.nextChecks()
        );
    }

    // List.stream().map().toList()는 null 원소에서 매퍼가 NPE를 내므로 직접 돈다
    private static <T, R> List<R> mapAll(List<T> items, Function<T, R> mapper) {
        if (items == null) {
            return null;
        }
        List<R> mapped = new ArrayList<>(items.size());
        for (T item : items) {
            mapped.add(item == null ? null : mapper.apply(item));
        }
        return mapped;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
