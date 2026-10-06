package com.sajo.operation_service.service.notification;

import com.sajo.operation_service.service.analysis.CauseCategory;
import com.sajo.operation_service.service.analysis.StructuredAnalysis;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.CandidateVerdict;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.Evidence;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.RankedCause;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredAnalysisFormatterTest {

    private static CandidateVerdict candidate(String component, Verdict verdict, CauseCategory category, Evidence... evidence) {
        return new CandidateVerdict(component, verdict, category, List.of(evidence), component + " 판정 이유");
    }

    @Test
    @DisplayName("유력 원인(순위, 판정, 이유, 근거) -> 배제/데이터 없음(이름만) -> 다음 확인 순서로 조립하고 관찰은 보여주지 않는다")
    void format_fullAnalysis() {
        StructuredAnalysis analysis = new StructuredAnalysis(
                List.of("관찰은 Slack에 안 나간다"),
                List.of(
                        candidate("market-service", Verdict.POSSIBLE, CauseCategory.CPU,
                                new Evidence("[CPU 사용률(0~1)]", "0.71")),
                        candidate("postgres", Verdict.LIKELY, CauseCategory.CONNECTION_EXHAUSTED,
                                new Evidence("[HikariCP 커넥션 대기(pending)]", "12"),
                                new Evidence("[[의존 대상: postgres] Postgres 커넥션 사용률(0~1)]", "0.9512345")),
                        candidate("redis", Verdict.RULED_OUT, CauseCategory.UNKNOWN),
                        candidate("kafka", Verdict.RULED_OUT, CauseCategory.UNKNOWN),
                        candidate("mongo", Verdict.INSUFFICIENT_DATA, CauseCategory.UNKNOWN)
                ),
                List.of(
                        new RankedCause("postgres", CauseCategory.CONNECTION_EXHAUSTED, "커넥션 대기 12"),
                        new RankedCause("market-service", CauseCategory.CPU, "CPU 사용률 상승")
                ),
                List.of("장기 실행 트랜잭션 확인")
        );

        assertThat(StructuredAnalysisFormatter.format(analysis, List.of())).isEqualTo("""
                *유력 원인*
                1. postgres / CONNECTION_EXHAUSTED (유력)
                    커넥션 대기 12
                    근거: HikariCP 커넥션 대기 12 · Postgres 커넥션 사용률 0.951
                2. market-service / CPU (가능)
                    CPU 사용률 상승
                    근거: CPU 사용률 0.71

                *배제*: redis, kafka
                *데이터 없음*: mongo

                *다음 확인*
                • 장기 실행 트랜잭션 확인""");
    }

    @Test
    @DisplayName("근거는 원인마다 최대 3개, 다음 확인은 최대 4개만 보여준다 - Slack에서만 자르고 이력에는 전부 남는다")
    void format_limitsEvidenceAndNextChecks() {
        StructuredAnalysis analysis = new StructuredAnalysis(
                List.of(),
                List.of(candidate("external-api", Verdict.LIKELY, CauseCategory.ERROR,
                        new Evidence("[a]", "1"), new Evidence("[b]", "2"), new Evidence("[c]", "3"), new Evidence("[d]", "4"))),
                List.of(new RankedCause("external-api", CauseCategory.ERROR, "이유")),
                List.of("확인1", "확인2", "확인3", "확인4", "확인5")
        );

        String text = StructuredAnalysisFormatter.format(analysis, List.of());

        assertThat(text).contains("근거: a 1 · b 2 · c 3").doesNotContain("d 4");
        assertThat(text).contains("• 확인4").doesNotContain("확인5");
    }

    @Test
    @DisplayName("근거 지표 이름은 의존 대상 접두어/대괄호/단위 설명을 지우고, 값은 유효숫자 3자리로 줄인다")
    void shortMetricNameAndValue() {
        assertThat(StructuredAnalysisFormatter.shortMetricName(
                "[아웃바운드 호출 대상별 실패율(0~1, 5xx/무응답)] openapivts.koreainvestment.com"))
                .isEqualTo("아웃바운드 호출 대상별 실패율 openapivts.koreainvestment.com");
        assertThat(StructuredAnalysisFormatter.shortMetricName("[[의존 대상: redis] Redis 메모리 사용률(0~1)]"))
                .isEqualTo("Redis 메모리 사용률");
        assertThat(StructuredAnalysisFormatter.shortMetricName("[Heap(Old Gen) 사용률(0~1)]")).isEqualTo("Heap 사용률");

        assertThat(StructuredAnalysisFormatter.shortValue("0.3333333333333333")).isEqualTo("0.333");
        assertThat(StructuredAnalysisFormatter.shortValue("0.03637626953333329")).isEqualTo("0.0364");
        assertThat(StructuredAnalysisFormatter.shortValue("1500")).isEqualTo("1500");
        assertThat(StructuredAnalysisFormatter.shortValue("0")).isEqualTo("0");
        assertThat(StructuredAnalysisFormatter.shortValue("데이터 없음")).isEqualTo("데이터 없음");
    }

    @Test
    @DisplayName("유력 원인이 없으면 섹션을 지우지 않고 '유력 원인 없음'으로 표시한다")
    void format_noTopCauses() {
        StructuredAnalysis analysis = new StructuredAnalysis(
                List.of("관찰"),
                List.of(candidate("redis", Verdict.RULED_OUT, CauseCategory.UNKNOWN)),
                List.of(),
                List.of()
        );

        assertThat(StructuredAnalysisFormatter.format(analysis, List.of())).isEqualTo("""
                *유력 원인*
                _유력 원인 없음_

                *배제*: redis""");
    }

    @Test
    @DisplayName("LLM이 필드를 빠뜨려 null이어도 예외 없이 그린다")
    void format_nullFields() {
        StructuredAnalysis analysis = new StructuredAnalysis(null, null, null, null);

        assertThat(StructuredAnalysisFormatter.format(analysis, List.of())).isEqualTo("*유력 원인*\n_유력 원인 없음_");
        assertThat(StructuredAnalysisFormatter.format(null, List.of())).isEqualTo("_분석 결과 없음_");
    }

    @Test
    @DisplayName("판정 규칙 위반이 있으면 맨 아래에 경고 한 줄을 붙인다 - 후보 누락처럼 본문에 안 드러나는 위반을 정상 분석으로 오해하지 않게")
    void format_withValidationErrors_appendsWarningAtBottom() {
        StructuredAnalysis analysis = new StructuredAnalysis(
                List.of(),
                List.of(candidate("redis", Verdict.RULED_OUT, CauseCategory.UNKNOWN)),
                List.of(),
                List.of("확인1")
        );

        assertThat(StructuredAnalysisFormatter.format(analysis, List.of("후보 누락: host", "중복 판정: redis")))
                .isEqualTo("""
                        *유력 원인*
                        _유력 원인 없음_

                        *배제*: redis

                        *다음 확인*
                        • 확인1

                        ⚠ 분석 검증 위반: 후보 누락: host / 중복 판정: redis""");
    }

    @Test
    @DisplayName("위반이 없으면(빈 목록/null) 경고를 붙이지 않는다")
    void format_withoutValidationErrors_noWarning() {
        StructuredAnalysis analysis = new StructuredAnalysis(List.of(), List.of(), List.of(), List.of());

        assertThat(StructuredAnalysisFormatter.format(analysis, List.of())).doesNotContain("⚠");
        assertThat(StructuredAnalysisFormatter.format(analysis, null)).doesNotContain("⚠");
    }

    @Test
    @DisplayName("LLM 문장의 <, >, &는 Slack 링크/제어 문자로 해석되지 않게 이스케이프한다")
    void format_escapesSlackControlCharacters() {
        StructuredAnalysis analysis = new StructuredAnalysis(
                List.of(),
                List.of(),
                List.of(),
                List.of("p99 < 1s & 에러율 > 5% 확인")
        );

        assertThat(StructuredAnalysisFormatter.format(analysis, List.of())).contains("• p99 &lt; 1s &amp; 에러율 &gt; 5% 확인");
    }

    @Test
    @DisplayName("후보/유력 원인/근거 목록에 null 원소가 섞여 있어도 예외 없이 나머지만 그린다")
    void format_nullElements() {
        StructuredAnalysis analysis = new StructuredAnalysis(
                List.of(),
                Arrays.asList(
                        null,
                        new CandidateVerdict("postgres", Verdict.LIKELY, CauseCategory.LOCK,
                                Arrays.asList(null, new Evidence("[락 대기]", "3")), "락 대기")
                ),
                Arrays.asList(null, new RankedCause("postgres", CauseCategory.LOCK, "락 대기 3")),
                List.of()
        );

        assertThat(StructuredAnalysisFormatter.format(analysis, List.of())).isEqualTo("""
                *유력 원인*
                1. postgres / LOCK (유력)
                    락 대기 3
                    근거: 락 대기 3""");
    }
}
