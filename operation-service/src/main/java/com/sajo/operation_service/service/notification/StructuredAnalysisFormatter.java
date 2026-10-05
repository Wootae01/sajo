package com.sajo.operation_service.service.notification;

import com.sajo.operation_service.service.analysis.StructuredAnalysis;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.CandidateVerdict;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.RankedCause;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.Verdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

// StructuredAnalysis -> Slack 텍스트(mrkdwn). LLM 응답 원문(JSON)은 Slack에 보내지 않고 이력에만 남긴다.
// 운영자가 바로 볼 것만 남긴다 - 유력 원인만 이유를 보여주고, 배제/데이터 없음 후보는 이름만 나열한다
// (후보별 근거와 배제 이유는 이력의 원문에 있다).
public final class StructuredAnalysisFormatter {

    private static final Map<Verdict, String> VERDICT_LABELS = Map.of(
            Verdict.LIKELY, "유력",
            Verdict.POSSIBLE, "가능",
            Verdict.RULED_OUT, "배제",
            Verdict.INSUFFICIENT_DATA, "데이터 없음"
    );

    private StructuredAnalysisFormatter() {
    }

    // 필드가 빠진 응답(null)도 빈 목록으로 보고 그린다 - 형식 검증 전이라도 Slack 발송이 NPE로 깨지지 않게 하기 위함
    public static String format(StructuredAnalysis analysis) {
        if (analysis == null) {
            return "_분석 결과 없음_";
        }
        List<CandidateVerdict> candidates = orEmpty(analysis.candidates());
        Map<String, Verdict> verdictByComponent = candidates.stream()
                .filter(candidate -> candidate.component() != null && candidate.verdict() != null)
                .collect(Collectors.toMap(CandidateVerdict::component, CandidateVerdict::verdict, (first, second) -> first));

        List<String> sections = new ArrayList<>();
        bulletSection("관찰", orEmpty(analysis.observations())).ifPresent(sections::add);
        sections.add(topCausesSection(orEmpty(analysis.topCauses()), verdictByComponent));

        List<String> verdictLines = new ArrayList<>();
        namesLine("배제", candidates, Verdict.RULED_OUT).ifPresent(verdictLines::add);
        namesLine("데이터 없음", candidates, Verdict.INSUFFICIENT_DATA).ifPresent(verdictLines::add);
        if (!verdictLines.isEmpty()) {
            sections.add(String.join("\n", verdictLines));
        }

        bulletSection("다음 확인", orEmpty(analysis.nextChecks())).ifPresent(sections::add);
        return String.join("\n\n", sections);
    }

    // 유력 원인은 비어 있어도 섹션을 남긴다 - "원인을 못 찾았다"는 것도 운영자에게 필요한 정보라서
    private static String topCausesSection(List<RankedCause> topCauses, Map<String, Verdict> verdictByComponent) {
        if (topCauses.isEmpty()) {
            return "*유력 원인*\n_유력 원인 없음_";
        }
        StringBuilder section = new StringBuilder("*유력 원인*");
        for (int i = 0; i < topCauses.size(); i++) {
            RankedCause cause = topCauses.get(i);
            Verdict verdict = verdictByComponent.get(cause.component());
            section.append("\n").append(i + 1).append(". ")
                    .append(escape(cause.component())).append(" / ").append(cause.category());
            if (verdict != null) {
                section.append(" (").append(VERDICT_LABELS.get(verdict)).append(")");
            }
            if (cause.reasoning() != null && !cause.reasoning().isBlank()) {
                section.append("\n    ").append(escape(cause.reasoning()));
            }
        }
        return section.toString();
    }

    private static Optional<String> bulletSection(String title, List<String> items) {
        List<String> nonBlank = items.stream().filter(Objects::nonNull).filter(item -> !item.isBlank()).toList();
        if (nonBlank.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("*" + title + "*\n" + nonBlank.stream()
                .map(item -> "• " + escape(item))
                .collect(Collectors.joining("\n")));
    }

    private static Optional<String> namesLine(String title, List<CandidateVerdict> candidates, Verdict verdict) {
        List<String> names = candidates.stream()
                .filter(candidate -> candidate.verdict() == verdict && candidate.component() != null)
                .map(CandidateVerdict::component)
                .map(StructuredAnalysisFormatter::escape)
                .toList();
        if (names.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("*" + title + "*: " + String.join(", ", names));
    }

    // Slack mrkdwn은 &, <, >를 제어 문자로 쓴다(<url|text> 링크 등) - LLM 문장의 "p99 < 1s" 같은 표현이 깨지지 않게 이스케이프한다
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
