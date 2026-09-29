package com.sajo.operation_service.service.diagnostics.app;

// AppDiagnosticsSnapshot을 구성하는 PromQL 쿼리 조립만 담당 -
final class AppDiagnosticsQueries {

    private AppDiagnosticsQueries() {
    }

    static String p99Latency(String application) {
        return """
                histogram_quantile(0.99,
                  sum by (le) (
                    rate(http_server_requests_seconds_bucket{application="%s", uri!~"/actuator.*"}[5m])
                  )
                )
                """.formatted(application);
    }

    // rules.yml의 HighErrorRate 알람과 동일한 필터(actuator 제외)를 씀
    static String errorRate(String application) {
        return """
                sum(rate(http_server_requests_seconds_count{application="%s", status=~"5..", uri!~"/actuator.*"}[5m]))
                /
                sum(rate(http_server_requests_seconds_count{application="%s", uri!~"/actuator.*"}[5m]))
                """.formatted(application, application);
    }

    static String cpuUsage(String application) {
        return "process_cpu_usage{application=\"%s\"}".formatted(application);
    }

    // rules.yml의 HighMemoryUsage 알람과 동일한 지표(GC 후 Old Gen 사용률)
    static String heapUsage(String application) {
        return """
                jvm_gc_live_data_size_bytes{application="%s"}
                /
                (jvm_gc_max_data_size_bytes{application="%s"} > 0)
                """.formatted(application, application);
    }

    // rules.yml의 HikariPoolPending 알람과 동일한 지표
    static String hikariPoolPending(String application) {
        return "hikaricp_connections_pending{application=\"%s\"}".formatted(application);
    }

    // rules.yml의 HighGcOverhead 알람과 동일한 공식(최근 5분 중 GC pause에 쓴 시간 비율)
    static String gcOverhead(String application) {
        return """
                sum by (application, instance) (
                  rate(jvm_gc_pause_seconds_sum{application="%s"}[5m])
                )
                """.formatted(application);
    }

    // 아웃바운드 호출(Feign/RestClient) 대상별 평균 응답시간 - client 쪽은 히스토그램 bucket이 없어서
    // p99 대신 평균을 쓴다. client_name은 Feign이면 @FeignClient name, RestClient면 호스트명이라
    // 같은 KIS라도 서비스/환경마다 값이 달라서 특정 이름으로 필터링하지 않고 대상별로 전부 묶는다
    static String outboundAvgLatency(String application) {
        return """
                sum by (client_name) (rate(http_client_requests_seconds_sum{%1$s}[5m]))
                /
                (sum by (client_name) (rate(http_client_requests_seconds_count{%1$s}[5m])) > 0)
                """.formatted(outboundSelector(application));
    }

    // 평균에 묻히는 튀는 호출을 보기 위한 최댓값 - _max는 짧은 구간만 유지되는 gauge라 5분 창으로 맞춘다
    static String outboundMaxLatency(String application) {
        return """
                max by (client_name) (max_over_time(http_client_requests_seconds_max{%s}[5m]))
                """.formatted(outboundSelector(application));
    }

    // 실패 = 5xx 또는 응답 자체를 못 받은 경우(CLIENT_ERROR/IO_ERROR). 4xx는 비즈니스 응답일 수 있어 제외.
    // 실패가 0건인 대상은 분자 시계열이 아예 없어서 나눗셈 결과에서 빠지므로 "or ... * 0"으로 0을 채운다 -
    // "이 대상은 정상"이라는 것도 원인 배제 근거라 LLM에 보여줘야 함
    static String outboundFailureRate(String application) {
        String selector = outboundSelector(application);
        return """
                (
                  sum by (client_name) (rate(http_client_requests_seconds_count{%1$s, status=~"5..|IO_ERROR|CLIENT_ERROR"}[5m]))
                  or
                  sum by (client_name) (rate(http_client_requests_seconds_count{%1$s}[5m])) * 0
                )
                /
                (sum by (client_name) (rate(http_client_requests_seconds_count{%1$s}[5m])) > 0)
                """.formatted(selector);
    }

    // Eureka 레지스트리 폴링은 주기적으로 항상 발생해서 원인 분석에 노이즈만 됨
    private static String outboundSelector(String application) {
        return "application=\"%s\", client_name!=\"discovery-service\"".formatted(application);
    }
}
