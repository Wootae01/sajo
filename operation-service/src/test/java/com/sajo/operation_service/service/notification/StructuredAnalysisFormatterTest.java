package com.sajo.operation_service.service.notification;

import com.sajo.operation_service.service.analysis.CauseCategory;
import com.sajo.operation_service.service.analysis.StructuredAnalysis;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.CandidateVerdict;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.Evidence;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.RankedCause;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredAnalysisFormatterTest {

    private static CandidateVerdict candidate(String component, Verdict verdict, CauseCategory category) {
        return new CandidateVerdict(component, verdict, category, List.of(new Evidence("지표", "1")), component + " 판정 이유");
    }

    @Test
    @DisplayName("관찰 -> 유력 원인(순위, 판정) -> 배제/데이터 없음(이름만) -> 다음 확인 순서로 조립한다")
    void format_fullAnalysis() {
        StructuredAnalysis analysis = new StructuredAnalysis(
                List.of("p99 지연시간 3.2초", "HikariCP 커넥션 대기 12"),
                List.of(
                        candidate("market-service", Verdict.POSSIBLE, CauseCategory.CPU),
                        candidate("postgres", Verdict.LIKELY, CauseCategory.CONNECTION_EXHAUSTED),
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

        assertThat(StructuredAnalysisFormatter.format(analysis)).isEqualTo("""
                *관찰*
                • p99 지연시간 3.2초
                • HikariCP 커넥션 대기 12

                *유력 원인*
                1. postgres / CONNECTION_EXHAUSTED (유력)
                    커넥션 대기 12
                2. market-service / CPU (가능)
                    CPU 사용률 상승

                *배제*: redis, kafka
                *데이터 없음*: mongo

                *다음 확인*
                • 장기 실행 트랜잭션 확인""");
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

        assertThat(StructuredAnalysisFormatter.format(analysis))
                .contains("*유력 원인*\n_유력 원인 없음_")
                .contains("*배제*: redis")
                .doesNotContain("*다음 확인*")
                .doesNotContain("*데이터 없음*");
    }

    @Test
    @DisplayName("LLM이 필드를 빠뜨려 null이어도 예외 없이 그린다 - 형식 검증 전에도 Slack 발송이 깨지지 않게")
    void format_nullFields() {
        StructuredAnalysis analysis = new StructuredAnalysis(null, null, null, null);

        assertThat(StructuredAnalysisFormatter.format(analysis)).isEqualTo("*유력 원인*\n_유력 원인 없음_");
        assertThat(StructuredAnalysisFormatter.format(null)).isEqualTo("_분석 결과 없음_");
    }

    @Test
    @DisplayName("LLM 문장의 <, >, &는 Slack 링크/제어 문자로 해석되지 않게 이스케이프한다")
    void format_escapesSlackControlCharacters() {
        StructuredAnalysis analysis = new StructuredAnalysis(
                List.of("p99 < 1s & 에러율 > 5%"),
                List.of(),
                List.of(),
                List.of()
        );

        assertThat(StructuredAnalysisFormatter.format(analysis)).contains("• p99 &lt; 1s &amp; 에러율 &gt; 5%");
    }
}
