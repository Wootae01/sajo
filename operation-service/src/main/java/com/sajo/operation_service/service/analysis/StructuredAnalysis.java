package com.sajo.operation_service.service.analysis;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

// LLM이 돌려주는 구조화된 분석 결과 - 자유 텍스트로는 "맞았는지"를 코드로 채점할 수 없어서
// 원인을 component(코드가 정한 후보 중 하나) + category(enum)로 고정한다.
// 후보 목록은 코드가 확정하고 LLM은 후보마다 판정만 한다 - LLM이 후보를 빠뜨리거나 지어내는 걸 막고,
// 배제한 후보도 근거와 함께 남겨서 "왜 아니라고 봤는지"까지 검토할 수 있게 하기 위함.
// 필드 순서 = LLM이 쓰는 순서. 지정하지 않으면 스키마가 알파벳순(candidates가 observations보다 먼저)이라
// 관찰을 정리하기 전에 판정부터 쓰게 된다 - 관찰 -> 판정 -> 순위 순서로 근거를 먼저 쓰게 한다.
@JsonPropertyOrder({"observations", "candidates", "topCauses", "nextChecks"})
public record StructuredAnalysis(
        List<String> observations,
        List<CandidateVerdict> candidates,
        List<RankedCause> topCauses,     // 순서 = 순위(0번이 가장 유력), 최대 3개
        List<String> nextChecks
) {

    // 근거와 이유를 먼저 쓰고 판정을 마지막에 쓰게 한다 - 판정을 먼저 쓰면 근거를 판정에 끼워 맞추게 된다
    @JsonPropertyOrder({"component", "evidence", "reasoning", "verdict", "category"})
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

    // 확신도(confidence) 필드는 두지 않는다 - verdict(LIKELY/POSSIBLE)와 순위가 같은 정보를 담고 있어서
    // 따로 두면 "LIKELY인데 확신 낮음"처럼 서로 어긋나는 조합만 생긴다
    public record RankedCause(
            String component,
            CauseCategory category,
            String reasoning
    ) {
    }

    public enum Verdict {
        LIKELY,             // 원인일 가능성 높음
        POSSIBLE,           // 원인일 수 있음
        RULED_OUT,          // 지표상 정상이라 배제
        INSUFFICIENT_DATA   // 판단할 지표가 없거나 조회 실패
    }
}
