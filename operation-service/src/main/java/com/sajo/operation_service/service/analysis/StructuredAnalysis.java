package com.sajo.operation_service.service.analysis;

import java.util.List;

// LLM이 돌려주는 구조화된 분석 결과 - 자유 텍스트로는 "맞았는지"를 코드로 채점할 수 없어서
// 원인을 component(코드가 정한 후보 중 하나) + category(enum)로 고정한다.
// 후보 목록은 코드가 확정하고 LLM은 후보마다 판정만 한다 - LLM이 후보를 빠뜨리거나 지어내는 걸 막고,
// 배제한 후보도 근거와 함께 남겨서 "왜 아니라고 봤는지"까지 검토할 수 있게 하기 위함.
public record StructuredAnalysis(
        List<String> observations,
        List<CandidateVerdict> candidates,
        List<RankedCause> topCauses,     // 순서 = 순위(0번이 가장 유력), 최대 3개
        List<String> nextChecks
) {

    public record CandidateVerdict(
            String component,
            Verdict verdict,
            CauseCategory category,      // RULED_OUT/INSUFFICIENT_DATA면 의미가 없어서 UNKNOWN 허용
            List<Evidence> evidence,
            String reasoning
    ) {
    }

    // 근거로 인용한 지표 - value는 입력 프롬프트에 있는 수치를 그대로 옮겨야 한다(환각 여부를 코드로 대조하기 위함)
    public record Evidence(String metric, String value) {
    }

    public record RankedCause(
            String component,
            CauseCategory category,
            Confidence confidence,
            String reasoning
    ) {
    }

    public enum Verdict {
        LIKELY,             // 원인일 가능성 높음
        POSSIBLE,           // 원인일 수 있음
        RULED_OUT,          // 지표상 정상이라 배제
        INSUFFICIENT_DATA   // 판단할 지표가 없거나 조회 실패
    }

    public enum Confidence {
        HIGH, MEDIUM, LOW
    }
}
