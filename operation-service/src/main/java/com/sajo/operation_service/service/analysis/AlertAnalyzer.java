package com.sajo.operation_service.service.analysis;

import com.sajo.operation_service.client.PrometheusQueryResult;
import com.sajo.operation_service.controller.dto.request.AlertManagerWebhookRequest;
import com.sajo.operation_service.service.diagnostics.dependency.DependencyMappingService;
import com.sajo.operation_service.service.diagnostics.host.HostDiagnosticsService;
import com.sajo.operation_service.service.strategy.AlertDiagnosisStrategy;
import com.sajo.operation_service.service.strategy.StrategyDiagnosis;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class AlertAnalyzer {

    // 응답 JSON -> StructuredAnalysis. 프롬프트에 붙는 스키마와 같은 타입에서 만든다(AlertPromptBuilder.SYSTEM_PROMPT).
    // ```json 코드블록으로 감싸 와도 벗겨내고 파싱한다. 상태가 없어서 여러 스레드가 같이 써도 된다.
    private static final BeanOutputConverter<StructuredAnalysis> OUTPUT_CONVERTER =
            new BeanOutputConverter<>(StructuredAnalysis.class);

    private final HostDiagnosticsService hostDiagnosticsService;
    private final DependencyMappingService dependencyMappingService;
    private final ChatClient chatClient;
    private final Map<String, AlertDiagnosisStrategy> alertnameStrategyRegistry;

    public AlertAnalyzer(
            HostDiagnosticsService hostDiagnosticsService,
            DependencyMappingService dependencyMappingService,
            ChatClient chatClient,
            List<AlertDiagnosisStrategy> strategies
    ) {
        this.hostDiagnosticsService = hostDiagnosticsService;
        this.dependencyMappingService = dependencyMappingService;
        this.chatClient = chatClient;

        Map<String, AlertDiagnosisStrategy> map = new HashMap<>();
        for (AlertDiagnosisStrategy strategy : strategies) {
            for (String alertname : strategy.alertnames()) {
                AlertDiagnosisStrategy previous = map.put(alertname, strategy);
                if (previous != null) {
                    throw new IllegalStateException(
                            "alertname '" + alertname + "'이 두 전략에 중복 등록됨: "
                                    + previous.getClass().getSimpleName() + ", " + strategy.getClass().getSimpleName());
                }
            }
        }
        this.alertnameStrategyRegistry = Map.copyOf(map);
    }

    // empty = 분석 대상이 아님(alertname 없음/전략 미등록), 예외 = 분석 시도했으나 실패
    public Optional<AlertAnalysisResult> analyze(AlertManagerWebhookRequest.Alert alert) {
        String alertname = alert.labels().get("alertname");

        if (alertname == null) {
            log.warn("alertname 라벨이 없는 알람이라 분석을 건너뜁니다. labels={}", alert.labels());
            return Optional.empty();
        }
        String target = alert.labels().get("application");

        AlertDiagnosisStrategy strategy = alertnameStrategyRegistry.get(alertname);
        if (strategy == null) {
            log.info("전략이 아직 없는 alertname이라 분석을 건너뜁니다. alertname={}, application={}", alertname, target);
            return Optional.empty();
        }

        Instant time = alert.startsAt();

        // 1. 알람 종류별 own snapshot - 알람을 실제로 울리게 한 지표. lookback을 적용하는 전략은
        // 실제 조회 시각이 time과 다를 수 있어서(diagnosis.queryTime()), 아래 2/3과 분리해서 다룬다.
        StrategyDiagnosis diagnosis = strategy.diagnose(alert, time);

        // 2. 호스트 스냅샷 - 항상 공통, 항상 time(발생 시각) 기준
        Map<String, PrometheusQueryResult> hostAndDependencyMetrics = new LinkedHashMap<>();
        hostAndDependencyMetrics.putAll(hostDiagnosticsService.collect(time));

        // 3. 의존관계 스냅샷 - 참고 정보, 항상 time(발생 시각) 기준
        if (target != null) {
            hostAndDependencyMetrics.putAll(dependencyMappingService.collect(target, time));
        }

        List<String> candidates = AnalysisCandidates.of(
                target, target == null ? List.of() : dependencyMappingService.relatedTargets(target));

        String userPrompt = AlertPromptBuilder.userPrompt(alert, diagnosis, hostAndDependencyMetrics, candidates);
        log.debug("LLM에 보낼 프롬프트. alertname={}\n{}", alertname, userPrompt);

        long startedAt = System.nanoTime();
        ChatResponse chatResponse = chatClient.prompt()
                .system(AlertPromptBuilder.SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .chatResponse();
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

        // 빈 응답은 "분석 대상 아님(empty)"이 아니라 분석 실패로 본다 - 이력에서 SKIPPED/FAILED를 구분하기 위함
        String response = extractText(chatResponse);
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("LLM 응답이 비어 있음. alertname=" + alertname);
        }

        StructuredAnalysis structuredAnalysis = parse(response, alertname);

        ChatResponseMetadata metadata = chatResponse.getMetadata();
        return Optional.of(new AlertAnalysisResult(
                response,
                structuredAnalysis,
                AlertPromptBuilder.SYSTEM_PROMPT,
                userPrompt,
                metadata.getModel(),
                TokenUsage.from(metadata.getUsage()),
                latencyMs
        ));
    }

    // 파싱 실패도 빈 응답과 같이 분석 실패로 본다. 실패 이력에는 예외 메시지만 남아서 원문 응답은 로그로 남긴다 -
    // 형식이 왜 틀렸는지 봐야 프롬프트를 고칠 수 있다. (형식 검증과 재요청은 다음 단계에서 추가)
    private StructuredAnalysis parse(String response, String alertname) {
        try {
            return OUTPUT_CONVERTER.convert(response);
        } catch (RuntimeException e) {
            log.warn("LLM 응답을 구조화 결과로 파싱하지 못했습니다. alertname={}\n{}", alertname, response);
            throw new IllegalStateException("LLM 응답 파싱 실패. alertname=" + alertname, e);
        }
    }

    private String extractText(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null) {
            return null;
        }
        return chatResponse.getResult().getOutput().getText();
    }
}
