package com.sajo.operation_service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisCandidatesTest {

    @Test
    @DisplayName("서비스 알람이면 자신 + 인프라 의존 + 호출하는 내부 서비스 + 외부 API + 호스트 순으로 후보를 만든다")
    void of_serviceTarget_includesSelfInfraCalledServicesExternalApiAndHost() {
        List<String> candidates = AnalysisCandidates.of("market-service", List.of("postgres", "redis", "kafka"));

        assertThat(candidates).containsExactly(
                "market-service", "postgres", "redis", "kafka", "user-service", "external-api", "host"
        );
    }

    @Test
    @DisplayName("여러 내부 서비스를 호출하는 서비스(trading-service)는 호출 대상을 모두 후보에 넣는다")
    void of_serviceCallingMultipleServices_includesAllCalledServices() {
        List<String> candidates = AnalysisCandidates.of("trading-service", List.of("postgres", "mongo", "kafka"));

        assertThat(candidates).containsExactly(
                "trading-service", "postgres", "mongo", "kafka", "market-service", "user-service", "external-api", "host"
        );
    }

    @Test
    @DisplayName("인프라 알람이면 그 인프라에 의존하는 서비스들이 후보가 되고 외부 API는 넣지 않는다")
    void of_infraTarget_includesDependentServicesWithoutExternalApi() {
        List<String> candidates = AnalysisCandidates.of(
                "postgres", List.of("user-service", "market-service", "trading-service"));

        assertThat(candidates).containsExactly(
                "postgres", "user-service", "market-service", "trading-service", "host"
        );
    }

    @Test
    @DisplayName("application 라벨이 없거나 node인 호스트 알람이면 호스트만 후보가 된다")
    void of_hostAlert_onlyHost() {
        assertThat(AnalysisCandidates.of(null, List.of())).containsExactly("host");
        assertThat(AnalysisCandidates.of("node", List.of())).containsExactly("host");
    }

    @Test
    @DisplayName("같은 대상이 여러 경로로 들어와도 한 번만 넣는다")
    void of_duplicateCandidates_deduplicated() {
        List<String> candidates = AnalysisCandidates.of("market-service", List.of("postgres", "user-service"));

        assertThat(candidates).containsExactly(
                "market-service", "postgres", "user-service", "external-api", "host"
        );
    }
}
