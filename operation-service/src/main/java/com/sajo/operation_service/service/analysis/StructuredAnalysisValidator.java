package com.sajo.operation_service.service.analysis;

import com.sajo.operation_service.service.analysis.StructuredAnalysis.CandidateVerdict;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.RankedCause;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.Verdict;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// structured output이 강제하지 못하는 "내용" 규칙을 검사한다 - 스키마는 후보 이름이 문자열이라는 것까지만 보장해서
// 후보 누락/목록 밖 후보/모순된 순위는 막지 못한다.
// 위반이 있어도 재요청하지 않고 기록만 한다 - 재요청으로 덮으면 LLM이 지시를 얼마나 어기는지 측정할 수 없게 되고,
// 위반이 있어도 나머지 판정은 읽을 만해서 Slack 발송은 그대로 한다.
// 채점에 영향을 주는 규칙만 검사한다(카테고리/근거 형식 같은 세부 규칙은 보지 않는다).
final class StructuredAnalysisValidator {

    static final int MAX_TOP_CAUSES = 3;

    private StructuredAnalysisValidator() {
    }

    // 빈 목록 = 위반 없음
    static List<String> validate(StructuredAnalysis analysis, List<String> expectedCandidates) {
        List<String> violations = new ArrayList<>();
        List<CandidateVerdict> candidates = analysis.candidates() == null ? List.of() : analysis.candidates();
        List<RankedCause> topCauses = analysis.topCauses() == null ? List.of() : analysis.topCauses();

        Map<String, Verdict> verdictByComponent = new HashMap<>();
        Set<String> duplicated = new LinkedHashSet<>();
        for (CandidateVerdict candidate : candidates) {
            if (verdictByComponent.containsKey(candidate.component())) {
                duplicated.add(candidate.component());
            }
            verdictByComponent.put(candidate.component(), candidate.verdict());
        }

        List<String> missing = expectedCandidates.stream()
                .filter(expected -> !verdictByComponent.containsKey(expected))
                .toList();
        if (!missing.isEmpty()) {
            violations.add("후보 누락: " + String.join(", ", missing));
        }

        List<String> unexpected = verdictByComponent.keySet().stream()
                .filter(component -> !expectedCandidates.contains(component))
                .sorted()
                .toList();
        if (!unexpected.isEmpty()) {
            violations.add("목록에 없는 후보: " + String.join(", ", unexpected));
        }

        if (!duplicated.isEmpty()) {
            violations.add("중복 판정: " + String.join(", ", duplicated));
        }

        if (topCauses.size() > MAX_TOP_CAUSES) {
            violations.add("topCauses " + topCauses.size() + "개 (최대 " + MAX_TOP_CAUSES + "개)");
        }

        for (RankedCause cause : topCauses) {
            Verdict verdict = verdictByComponent.get(cause.component());
            if (verdict == null) {
                violations.add("topCauses에 판정 안 된 후보: " + cause.component());
            } else if (verdict != Verdict.LIKELY && verdict != Verdict.POSSIBLE) {
                violations.add("topCauses에 " + verdict + "로 판정한 후보: " + cause.component());
            }
        }

        return violations;
    }
}
