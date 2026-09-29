package com.sajo.operation_service.service.diagnostics.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppDiagnosticsQueriesTest {

    @Test
    @DisplayName("p99Latency 쿼리에 application 필터와 actuator 제외 조건이 들어간다")
    void p99Latency_containsApplicationFilterAndExcludesActuator() {
        String query = AppDiagnosticsQueries.p99Latency("trading-service");

        assertThat(query)
                .contains("application=\"trading-service\"")
                .contains("uri!~\"/actuator.*\"")
                .contains("histogram_quantile(0.99");
    }

    @Test
    @DisplayName("errorRate 쿼리는 5xx 비율을 application 기준으로 계산한다")
    void errorRate_containsStatus5xxFilter() {
        String query = AppDiagnosticsQueries.errorRate("trading-service");

        assertThat(query)
                .contains("application=\"trading-service\"")
                .contains("status=~\"5..\"");
    }

    @Test
    @DisplayName("cpuUsage 쿼리는 process_cpu_usage를 application으로 필터링한다")
    void cpuUsage_filtersProcessCpuUsageByApplication() {
        String query = AppDiagnosticsQueries.cpuUsage("trading-service");

        assertThat(query).isEqualTo("process_cpu_usage{application=\"trading-service\"}");
    }

    @Test
    @DisplayName("heapUsage 쿼리는 GC live data 대비 max data 비율을 계산한다")
    void heapUsage_usesGcLiveDataRatio() {
        String query = AppDiagnosticsQueries.heapUsage("trading-service");

        assertThat(query)
                .contains("jvm_gc_live_data_size_bytes{application=\"trading-service\"}")
                .contains("jvm_gc_max_data_size_bytes{application=\"trading-service\"}");
    }

    @Test
    @DisplayName("hikariPoolPending 쿼리는 hikaricp_connections_pending을 application으로 필터링한다")
    void hikariPoolPending_filtersByApplication() {
        String query = AppDiagnosticsQueries.hikariPoolPending("trading-service");

        assertThat(query).isEqualTo("hikaricp_connections_pending{application=\"trading-service\"}");
    }

    @Test
    @DisplayName("gcOverhead 쿼리는 rules.yml의 HighGcOverhead와 동일한 공식을 쓴다")
    void gcOverhead_reusesHighGcOverheadFormula() {
        String query = AppDiagnosticsQueries.gcOverhead("trading-service");

        assertThat(query)
                .contains("jvm_gc_pause_seconds_sum{application=\"trading-service\"}")
                .contains("sum by (application, instance)");
    }

    @Test
    @DisplayName("outboundAvgLatency 쿼리는 호출 대상별 평균을 구하고 Eureka 호출과 트래픽 없는 대상은 제외한다")
    void outboundAvgLatency_groupsByClientNameAndExcludesDiscovery() {
        String query = AppDiagnosticsQueries.outboundAvgLatency("trading-service");

        assertThat(query)
                .contains("http_client_requests_seconds_sum{application=\"trading-service\", client_name!=\"discovery-service\"}")
                .contains("http_client_requests_seconds_count{application=\"trading-service\", client_name!=\"discovery-service\"}")
                .contains("sum by (client_name)")
                .contains("> 0)");
    }

    @Test
    @DisplayName("outboundMaxLatency 쿼리는 호출 대상별 5분 최댓값을 구한다")
    void outboundMaxLatency_usesMaxOverTimeByClientName() {
        String query = AppDiagnosticsQueries.outboundMaxLatency("trading-service");

        assertThat(query)
                .contains("max by (client_name)")
                .contains("max_over_time(http_client_requests_seconds_max{application=\"trading-service\", client_name!=\"discovery-service\"}[5m])");
    }

    @Test
    @DisplayName("outboundFailureRate 쿼리는 5xx와 무응답을 실패로 보고, 실패 0건인 대상도 0으로 채운다")
    void outboundFailureRate_countsServerAndNoResponseErrorsAndFillsZero() {
        String query = AppDiagnosticsQueries.outboundFailureRate("trading-service");

        assertThat(query)
                .contains("status=~\"5..|IO_ERROR|CLIENT_ERROR\"")
                .contains("or")
                .contains("* 0")
                .contains("sum by (client_name)")
                .contains("client_name!=\"discovery-service\"");
    }
}
