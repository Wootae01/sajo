package com.sajo.operation_service.service.analysis;

// 원인 유형 분류 - 평가 시 정답 라벨과 그대로 비교하는 값이라, 항목을 추가/변경하면 기존 정답 라벨도 같이 맞춰야 한다.
// 항목은 "현상" 기준이다. 배포/설정 변경 같은 "계기"는 섞지 않는다(배포 후 커넥션 고갈이면 CONNECTION_EXHAUSTED) -
// 기준이 다른 값을 한 enum에 섞으면 정답 라벨이 애매해진다.
// 추가 기준: (1) 구분하면 운영자의 조치가 달라지고 (2) 수집하는 지표로 구분할 수 있을 때만 추가한다.
// 예외로 TRAFFIC/SLOW_QUERY는 아직 구분할 지표가 없지만 남겨둔다 - 기준 측정에서 "못 맞힘"을 확인한 뒤
// 지표를 추가해 개선을 측정할 계획이라, enum에 없으면 LLM이 정답을 고를 수조차 없어 전후 비교가 안 된다.
public enum CauseCategory {
    DOWN,                   // 프로세스/인스턴스가 죽었거나 연결 불가
    CONNECTION_EXHAUSTED,   // 커넥션 풀/최대 연결 수 고갈
    LOCK,                   // 락 대기/블로킹
    SLOW_QUERY,             // 쿼리 자체가 느림
    LATENCY,                // 응답 지연인데 원인이 그 대상 내부라 우리 지표로는 더 쪼갤 수 없음 - 주로 external-api(예: KIS 응답 지연)
    ERROR,                  // 에러 응답/호출 실패 증가
    CPU,                    // CPU 포화
    MEMORY,                 // 메모리 부족
    GC,                     // GC 부하
    TRAFFIC,                // 요청량 급증
    DISK,                   // 디스크 부족
    NETWORK,                // 네트워크 오류/단절
    CONSUMER_LAG,           // 컨슈머 정체/lag 누적
    UNKNOWN                 // 판단 불가
}
