package com.sajo.operation_service.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// LLM이 판정해야 할 원인 후보 목록을 코드가 확정한다 - LLM에게 후보를 스스로 떠올리게 하면 진단 데이터에
// 있는 대상도 빠뜨리거나 없는 대상을 지어낼 수 있어서, 후보는 빠짐없이 코드가 뽑고 LLM은 판정만 하게 한다.
// 후보 = 알람 대상 자신 + 인프라 의존관계(DependencyMappingService와 같은 표) + 호출 대상(내부 서비스, 외부 API) + 호스트
final class AnalysisCandidates {

    static final String HOST = "host";
    static final String EXTERNAL_API = "external-api";

    // 서비스가 호출하는 대상(호출하는 쪽 -> 호출 대상). 호출 대상이 느리거나 실패하면 호출하는 쪽의 지연/에러 원인이 될 수 있다.
    // 근거는 앱 진단의 아웃바운드 지표(client_name)로 확인한다. external-api는 KIS다 - 내부 서비스와 대응이 달라서
    // (KIS 상태 확인·타임아웃/서킷브레이커 조정 vs 우리 서비스 조사) 별도 후보로 둔다.
    // 자기 자신 호출(market-service -> market-service)은 대상 자신이 이미 후보라 넣지 않는다.
    // 새 Feign 클라이언트나 KIS 호출이 생기면 여기도 같이 고쳐야 한다.
    private static final Map<String, List<String>> OUTBOUND_CALL_MAP = Map.of(
            "user-service", List.of("trading-service", EXTERNAL_API),
            "market-service", List.of("user-service", EXTERNAL_API),
            "trading-service", List.of("market-service", "user-service", EXTERNAL_API)
    );

    // node는 호스트 알람의 application 라벨이라 대상이 아니라 host 후보로 다룬다
    private static final String NODE_LABEL = "node";

    private AnalysisCandidates() {
    }

    // target이 null이면(application 라벨 없는 호스트 알람) 호스트만 후보가 된다
    static List<String> of(String target, List<String> infraDependencies) {
        Set<String> candidates = new LinkedHashSet<>();
        if (target != null && !NODE_LABEL.equals(target)) {
            candidates.add(target);
            candidates.addAll(infraDependencies);
            candidates.addAll(OUTBOUND_CALL_MAP.getOrDefault(target, List.of()));
        }
        candidates.add(HOST);
        return List.copyOf(candidates);
    }
}
