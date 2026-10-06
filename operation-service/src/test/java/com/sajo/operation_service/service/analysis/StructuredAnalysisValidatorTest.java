package com.sajo.operation_service.service.analysis;

import com.sajo.operation_service.service.analysis.StructuredAnalysis.CandidateVerdict;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.RankedCause;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredAnalysisValidatorTest {

    private static final List<String> EXPECTED = List.of("market-service", "postgres", "redis", "host");

    private static CandidateVerdict judged(String component, Verdict verdict) {
        return new CandidateVerdict(component, verdict, CauseCategory.UNKNOWN, List.of(), "이유");
    }

    private static RankedCause ranked(String component) {
        return new RankedCause(component, CauseCategory.CPU, "이유");
    }

    private static StructuredAnalysis analysis(List<CandidateVerdict> candidates, List<RankedCause> topCauses) {
        return new StructuredAnalysis(List.of("관찰"), candidates, topCauses, List.of());
    }

    private static List<CandidateVerdict> allJudged() {
        return List.of(
                judged("market-service", Verdict.LIKELY),
                judged("postgres", Verdict.POSSIBLE),
                judged("redis", Verdict.RULED_OUT),
                judged("host", Verdict.INSUFFICIENT_DATA)
        );
    }

    @Test
    @DisplayName("후보를 전부 한 번씩 판정하고 topCauses가 LIKELY/POSSIBLE 후보로만 3개 이하면 위반 없음")
    void validate_valid() {
        StructuredAnalysis analysis = analysis(allJudged(), List.of(ranked("market-service"), ranked("postgres")));

        assertThat(StructuredAnalysisValidator.validate(analysis, EXPECTED)).isEmpty();
    }

    @Test
    @DisplayName("유력 원인이 없어 topCauses가 비어 있는 것은 위반이 아니다")
    void validate_emptyTopCauses_isValid() {
        assertThat(StructuredAnalysisValidator.validate(analysis(allJudged(), List.of()), EXPECTED)).isEmpty();
    }

    @Test
    @DisplayName("판정하지 않은 후보를 후보 목록 순서대로 알려준다")
    void validate_missingCandidates() {
        StructuredAnalysis analysis = analysis(
                List.of(judged("market-service", Verdict.LIKELY), judged("redis", Verdict.RULED_OUT)),
                List.of(ranked("market-service"))
        );

        assertThat(StructuredAnalysisValidator.validate(analysis, EXPECTED)).containsExactly("후보 누락: postgres, host");
    }

    @Test
    @DisplayName("목록에 없는 후보를 판정하면 위반이다")
    void validate_unexpectedCandidate() {
        List<CandidateVerdict> candidates = new ArrayList<>(allJudged());
        candidates.add(judged("nginx", Verdict.RULED_OUT));

        assertThat(StructuredAnalysisValidator.validate(analysis(candidates, List.of()), EXPECTED))
                .containsExactly("목록에 없는 후보: nginx");
    }

    @Test
    @DisplayName("같은 후보를 두 번 판정하면 위반이다")
    void validate_duplicatedCandidate() {
        List<CandidateVerdict> candidates = new ArrayList<>(allJudged());
        candidates.add(judged("postgres", Verdict.RULED_OUT));

        assertThat(StructuredAnalysisValidator.validate(analysis(candidates, List.of()), EXPECTED))
                .containsExactly("중복 판정: postgres");
    }

    @Test
    @DisplayName("topCauses가 3개를 넘으면 위반이다")
    void validate_tooManyTopCauses() {
        List<CandidateVerdict> candidates = List.of(
                judged("market-service", Verdict.LIKELY),
                judged("postgres", Verdict.POSSIBLE),
                judged("redis", Verdict.POSSIBLE),
                judged("host", Verdict.POSSIBLE)
        );
        List<RankedCause> topCauses = List.of(ranked("market-service"), ranked("postgres"), ranked("redis"), ranked("host"));

        assertThat(StructuredAnalysisValidator.validate(analysis(candidates, topCauses), EXPECTED))
                .containsExactly("topCauses 4개 (최대 3개)");
    }

    @Test
    @DisplayName("topCauses에 배제/데이터 없음으로 판정한 후보나 판정하지 않은 후보가 있으면 위반이다")
    void validate_inconsistentTopCauses() {
        StructuredAnalysis analysis = analysis(allJudged(), List.of(ranked("redis"), ranked("host"), ranked("kafka")));

        assertThat(StructuredAnalysisValidator.validate(analysis, EXPECTED)).containsExactly(
                "topCauses에 RULED_OUT로 판정한 후보: redis",
                "topCauses에 INSUFFICIENT_DATA로 판정한 후보: host",
                "topCauses에 판정 안 된 후보: kafka"
        );
    }

    @Test
    @DisplayName("null 원소나 component 없는 판정은 예외 없이 빼고 위반으로 남긴다 - 나머지 판정은 그대로 검사한다")
    void validate_nullElements_recordedAsViolations() {
        List<CandidateVerdict> candidates = new ArrayList<>(allJudged());
        candidates.add(null);
        candidates.add(judged(null, Verdict.LIKELY));
        List<RankedCause> topCauses = Arrays.asList(ranked("market-service"), null);

        assertThat(StructuredAnalysisValidator.validate(analysis(candidates, topCauses), EXPECTED)).containsExactly(
                "null이거나 component 없는 후보 판정 2개",
                "topCauses에 null 1개"
        );
    }
}
