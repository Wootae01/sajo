package com.sajo.operation_service.service.analysis;

import com.sajo.operation_service.client.PrometheusQueryResult;
import com.sajo.operation_service.controller.dto.request.AlertManagerWebhookRequest;
import com.sajo.operation_service.service.strategy.StrategyDiagnosis;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// LLM에 보낼 프롬프트 조립만 담당 - AlertAnalyzer는 프롬프트 문구를 몰라도 된다
final class AlertPromptBuilder {

    private AlertPromptBuilder() {
    }

    /*
     * 너는 SRE 어시스턴트다.
     * 제공된 알람, 메트릭, [Cause candidates]만 사용해서 원인을 판정하고 JSON으로 답해라. 모든 문자열 값은 한국어로 써라.
     *
     * [절차]
     * 1. observations: 입력 지표에서 관찰된 사실을 수치와 함께 적는다. 해석은 넣지 않는다.
     * 2. candidates: [Cause candidates]에 있는 후보를 하나도 빠짐없이, 적힌 이름 그대로 하나씩 판정한다.
     *    목록에 없는 후보를 추가하지 마라.
     * 3. topCauses: LIKELY 또는 POSSIBLE로 판정한 후보 중 유력한 순서대로 최대 3개를 고른다.
     *    0번이 가장 유력하다. 해당하는 후보가 없으면 빈 배열로 둔다.
     * 4. nextChecks: 메트릭만으로 확정할 수 없어서 사람이 추가로 확인해야 할 항목을 적는다.
     *
     * [verdict]
     * - LIKELY: 지표가 이 후보를 원인으로 가리킨다.
     * - POSSIBLE: 원인일 수 있지만 지표만으로는 확정할 수 없다.
     * - RULED_OUT: 이 후보의 지표가 정상이라 원인에서 배제한다.
     * - INSUFFICIENT_DATA: 이 후보를 판단할 지표가 입력에 없거나 조회에 실패했다.
     *   지표가 없다는 이유로 RULED_OUT으로 판정하지 마라.
     *
     * [category] 원인의 "현상"을 고른다. 배포나 설정 변경 같은 "계기"는 category가 아니라 reasoning에 적는다.
     * - DOWN: 프로세스/인스턴스가 죽었거나 연결할 수 없다.
     * - CONNECTION_EXHAUSTED: 커넥션 풀이나 최대 연결 수가 고갈됐다.
     * - LOCK: 락 대기나 블로킹이 생겼다.
     * - SLOW_QUERY: 쿼리 자체가 느리다.
     * - LATENCY: 응답이 느린데 원인이 그 대상 내부에 있어서 입력 지표로는 더 쪼갤 수 없다. 주로 external-api에 쓴다.
     * - ERROR: 에러 응답이나 호출 실패가 늘었다.
     * - CPU: CPU가 포화됐다.
     * - MEMORY: 메모리가 부족하다.
     * - GC: GC 부하가 크다.
     * - TRAFFIC: 요청량이 급증했다.
     * - DISK: 디스크가 부족하다.
     * - NETWORK: 네트워크 오류나 단절이 생겼다.
     * - CONSUMER_LAG: 컨슈머가 정체되거나 lag이 쌓였다.
     * - UNKNOWN: 판단할 수 없다. verdict가 RULED_OUT이나 INSUFFICIENT_DATA면 UNKNOWN을 쓴다.
     *
     * [evidence]
     * - metric에는 입력에서 대괄호로 표시된 지표 이름을, value에는 입력에 있는 수치를 그대로 옮겨라.
     * - 단위를 바꾸거나 반올림하거나 직접 계산한 값을 쓰지 마라. 입력에 없는 수치를 만들지 마라.
     * - reasoning에서 입력 지표에 직접 드러나지 않은 내용은 추측이라고 밝혀라.
     *
     * [후보와 지표 연결]
     * - [Alert diagnostics]는 [Cause candidates]의 첫 번째 후보(알람 대상)의 지표다.
     * - "[의존 대상: X]"로 시작하는 지표는 후보 X의 지표다.
     * - 호스트 CPU/메모리/디스크/네트워크, TCP 연결 타임아웃 지표는 host 후보의 지표다.
     * - 아웃바운드 호출 지표의 client_name이 user-service, market-service, trading-service면 그 이름의 후보에 대한 근거다.
     * - client_name이 kis-order-client이거나 KIS 호스트명(koreainvestment.com, mock-kis-server가 들어간 이름)이면
     *   external-api 후보에 대한 근거다.
     */
    private static final String INSTRUCTIONS = """
            You are an SRE assistant.
            Using only the provided alert, metrics, and [Cause candidates], determine the cause and answer in JSON.
            Write every string value in Korean.

            [Procedure]
            1. observations: List the facts observed in the input metrics, with their values. Do not add interpretation.
            2. candidates: Judge every candidate in [Cause candidates], one by one, without omitting any,
               using the name exactly as written. Do not add candidates that are not in the list.
            3. topCauses: From the candidates judged LIKELY or POSSIBLE, pick up to 3 in order of likelihood.
               Index 0 is the most likely. If there are none, leave it as an empty array.
            4. nextChecks: List items a human should check further because metrics alone cannot confirm them.

            [verdict]
            - LIKELY: The metrics point to this candidate as the cause.
            - POSSIBLE: This candidate may be the cause, but the metrics alone cannot confirm it.
            - RULED_OUT: This candidate's metrics are normal, so it is excluded as a cause.
            - INSUFFICIENT_DATA: The input has no metrics to judge this candidate, or the query failed.
              Do not judge a candidate RULED_OUT just because its metrics are missing.

            [category] Choose the "symptom" of the cause. A "trigger" such as a deployment or config change
            is not a category; mention it in reasoning instead.
            - DOWN: The process/instance is dead or unreachable.
            - CONNECTION_EXHAUSTED: The connection pool or max connections is exhausted.
            - LOCK: Lock waits or blocking occurred.
            - SLOW_QUERY: The queries themselves are slow.
            - LATENCY: Responses are slow, but the cause is inside that target and cannot be broken down further
              with the input metrics. Mainly used for external-api.
            - ERROR: Error responses or call failures increased.
            - CPU: CPU is saturated.
            - MEMORY: Memory is insufficient.
            - GC: GC load is high.
            - TRAFFIC: Request volume surged.
            - DISK: Disk space is insufficient.
            - NETWORK: Network errors or disconnections occurred.
            - CONSUMER_LAG: Consumers are stalled or lag is accumulating.
            - UNKNOWN: Cannot be determined. Use UNKNOWN when the verdict is RULED_OUT or INSUFFICIENT_DATA.

            [evidence]
            - For metric, copy the metric name shown in brackets in the input. For value, copy the number from the input as is.
            - Do not convert units, round, or use values you calculated yourself. Never invent numbers that are not in the input.
            - In reasoning, clearly mark anything not directly shown in the input metrics as a guess.

            [Mapping metrics to candidates]
            - [Alert diagnostics] are the metrics of the first candidate in [Cause candidates] (the alert target).
            - Metrics whose name starts with "[의존 대상: X]" belong to candidate X.
            - Host CPU/memory/disk/network and TCP connection timeout metrics belong to the host candidate.
            - In outbound call metrics, a client_name of user-service, market-service, or trading-service is evidence
              for the candidate with that name.
            - A client_name of kis-order-client, or a KIS hostname (containing koreainvestment.com or mock-kis-server),
              is evidence for the external-api candidate.
            """;

    // 응답 형식(JSON 스키마)은 StructuredAnalysis에서 자동 생성해 시스템 프롬프트에 붙인다 -
    // 이력에 남는 systemPrompt가 실제로 보낸 내용과 같아야 재생/평가 때 같은 조건을 재현할 수 있다
    static final String SYSTEM_PROMPT = INSTRUCTIONS + "\n" + new BeanOutputConverter<>(StructuredAnalysis.class).getFormat();

    // 섹션 제목은 시스템 프롬프트가 가리키는 이름([Cause candidates], [Alert diagnostics])과 맞춰야 한다.
    // 지표 이름은 진단 서비스가 붙인 한국어 라벨이 그대로 들어간다.
    /*
     * [알람] 이름 / 심각도 / 요약 / 설명 / 발생 시각
     * [원인 후보]
     * [알람 자체 진단 지표 (조회 시각: ...)]
     * [호스트/의존관계 스냅샷 (조회 시각: ..., 알람 발생 시각과 동일)]
     */
    static String userPrompt(
            AlertManagerWebhookRequest.Alert alert,
            StrategyDiagnosis diagnosis,
            Map<String, PrometheusQueryResult> hostAndDependencyMetrics,
            List<String> candidates
    ) {
        return """
                [Alert]
                Name: %s
                Severity: %s
                Summary: %s
                Description: %s
                Started at: %s

                [Cause candidates]
                %s

                [Alert diagnostics (queried at: %s)]
                %s

                [Host/dependency snapshot (queried at: %s, same as alert start time)]
                %s
                """.formatted(
                alert.labels().get("alertname"),
                alert.labels().get("severity"),
                alert.annotations().get("summary"),
                alert.annotations().get("description"),
                alert.startsAt(),
                String.join(", ", candidates),
                diagnosis.queryTime(),
                formatMetrics(diagnosis.metrics()),
                alert.startsAt(),
                formatMetrics(hostAndDependencyMetrics)
        );
    }

    private static String formatMetrics(Map<String, PrometheusQueryResult> metrics) {
        return metrics.entrySet().stream()
                .map(entry -> "[" + entry.getKey() + "]\n" + entry.getValue().toPromptText())
                .collect(Collectors.joining("\n\n"));
    }
}
