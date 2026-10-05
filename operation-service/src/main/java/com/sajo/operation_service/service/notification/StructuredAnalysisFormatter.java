package com.sajo.operation_service.service.notification;

import com.sajo.operation_service.service.analysis.StructuredAnalysis;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.CandidateVerdict;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.RankedCause;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.Verdict;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// StructuredAnalysis -> Slack 텍스트(mrkdwn). LLM 응답 원문(JSON)은 Slack에 보내지 않고 이력에만 남긴다.
// 운영자가 "뭐가 문제고 뭘 먼저 보면 되나"를 바로 알 수 있게 결론부터 짧게 보여준다 -
// 관찰(observations)은 보여주지 않고(지표가 많아 핵심이 묻힘), 유력 원인마다 그 원인의 근거 지표만 붙인다.
// 전체 관찰, 후보별 근거, 배제 이유는 이력의 원문에 있다.
public final class StructuredAnalysisFormatter {

    // Slack에서만 자르는 개수 - LLM 응답(이력)에는 전부 남아 있다
    static final int MAX_EVIDENCE_PER_CAUSE = 3;
    // 프롬프트상 다음 확인은 유력 원인(최대 3)마다 1개 + 데이터 없음 확인 1개 = 최대 4개.
    // 정상 출력은 자르지 않고, LLM이 지시를 어겼을 때 Slack이 길어지지 않게 막는 안전장치
    static final int MAX_NEXT_CHECKS = 4;

    private static final Map<Verdict, String> VERDICT_LABELS = Map.of(
            Verdict.LIKELY, "유력",
            Verdict.POSSIBLE, "가능",
            Verdict.RULED_OUT, "배제",
            Verdict.INSUFFICIENT_DATA, "데이터 없음"
    );

    // 근거 지표 이름 줄이기 - 진단 라벨의 "[의존 대상: X] " 접두어, 대괄호, "(0~1)" 같은 단위 설명을 지운다
    private static final Pattern DEPENDENCY_PREFIX = Pattern.compile("\\[의존 대상: [^]]*]\\s*");
    private static final Pattern PARENTHESES = Pattern.compile("\\([^)]*\\)");
    private static final MathContext SIGNIFICANT_DIGITS = new MathContext(3);

    private StructuredAnalysisFormatter() {
    }

    // 필드가 빠진 응답(null)도 빈 목록으로 보고 그린다 - Slack 발송이 NPE로 깨지지 않게 하기 위함
    public static String format(StructuredAnalysis analysis) {
        if (analysis == null) {
            return "_분석 결과 없음_";
        }
        List<CandidateVerdict> candidates = orEmpty(analysis.candidates()).stream()
                .filter(candidate -> candidate.component() != null)
                .toList();
        Map<String, CandidateVerdict> candidateByComponent = candidates.stream()
                .collect(Collectors.toMap(CandidateVerdict::component, candidate -> candidate, (first, second) -> first));

        List<String> sections = new ArrayList<>();
        sections.add(topCausesSection(orEmpty(analysis.topCauses()), candidateByComponent));

        List<String> verdictLines = new ArrayList<>();
        namesLine("배제", candidates, Verdict.RULED_OUT).ifPresent(verdictLines::add);
        namesLine("데이터 없음", candidates, Verdict.INSUFFICIENT_DATA).ifPresent(verdictLines::add);
        if (!verdictLines.isEmpty()) {
            sections.add(String.join("\n", verdictLines));
        }

        bulletSection("다음 확인", orEmpty(analysis.nextChecks()), MAX_NEXT_CHECKS).ifPresent(sections::add);
        return String.join("\n\n", sections);
    }

    // 유력 원인은 비어 있어도 섹션을 남긴다 - "원인을 못 찾았다"는 것도 운영자에게 필요한 정보라서
    private static String topCausesSection(List<RankedCause> topCauses, Map<String, CandidateVerdict> candidateByComponent) {
        if (topCauses.isEmpty()) {
            return "*유력 원인*\n_유력 원인 없음_";
        }
        StringBuilder section = new StringBuilder("*유력 원인*");
        for (int i = 0; i < topCauses.size(); i++) {
            RankedCause cause = topCauses.get(i);
            CandidateVerdict candidate = candidateByComponent.get(cause.component());
            section.append("\n").append(i + 1).append(". ")
                    .append(escape(String.valueOf(cause.component()))).append(" / ").append(cause.category());
            if (candidate != null && candidate.verdict() != null) {
                section.append(" (").append(VERDICT_LABELS.get(candidate.verdict())).append(")");
            }
            if (cause.reasoning() != null && !cause.reasoning().isBlank()) {
                section.append("\n    ").append(escape(cause.reasoning()));
            }
            evidenceLine(candidate).ifPresent(line -> section.append("\n    ").append(line));
        }
        return section.toString();
    }

    // 근거: 그 원인을 판정할 때 LLM이 고른 지표(evidence) 중 앞에서부터 최대 3개
    private static Optional<String> evidenceLine(CandidateVerdict candidate) {
        if (candidate == null) {
            return Optional.empty();
        }
        List<String> items = orEmpty(candidate.evidence()).stream()
                .filter(evidence -> evidence.metric() != null && evidence.value() != null)
                .limit(MAX_EVIDENCE_PER_CAUSE)
                .map(evidence -> shortMetricName(evidence.metric()) + " " + shortValue(evidence.value()))
                .map(StructuredAnalysisFormatter::escape)
                .toList();
        if (items.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("근거: " + String.join(" · ", items));
    }

    // "[[의존 대상: postgres] Postgres 커넥션 사용률(0~1)]" -> "Postgres 커넥션 사용률"
    // "[아웃바운드 호출 대상별 실패율(0~1, 5xx/무응답)] openapivts.koreainvestment.com" -> "아웃바운드 호출 대상별 실패율 openapivts.koreainvestment.com"
    static String shortMetricName(String metric) {
        String name = DEPENDENCY_PREFIX.matcher(metric).replaceAll("");
        name = PARENTHESES.matcher(name).replaceAll("");
        return name.replace("[", "").replace("]", "").replaceAll("\\s+", " ").trim();
    }

    // 숫자는 유효숫자 3자리로 줄이고(0.3333333333 -> 0.333), 숫자가 아니면("데이터 없음") 그대로 둔다
    static String shortValue(String value) {
        try {
            return new BigDecimal(value.trim()).round(SIGNIFICANT_DIGITS).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static Optional<String> bulletSection(String title, List<String> items, int limit) {
        List<String> nonBlank = items.stream()
                .filter(Objects::nonNull)
                .filter(item -> !item.isBlank())
                .limit(limit)
                .toList();
        if (nonBlank.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("*" + title + "*\n" + nonBlank.stream()
                .map(item -> "• " + escape(item))
                .collect(Collectors.joining("\n")));
    }

    private static Optional<String> namesLine(String title, List<CandidateVerdict> candidates, Verdict verdict) {
        List<String> names = candidates.stream()
                .filter(candidate -> candidate.verdict() == verdict)
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
