package com.sajo.operation_service.repository;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.sajo.operation_service.config.MongoIndexInitializer;
import com.sajo.operation_service.controller.dto.request.AlertManagerWebhookRequest;
import com.sajo.operation_service.document.AlertHistory;
import com.sajo.operation_service.document.AlertHistoryEventType;
import com.sajo.operation_service.document.AlertHistory.CandidateSnapshot;
import com.sajo.operation_service.document.AlertHistory.EvidenceSnapshot;
import com.sajo.operation_service.document.AlertHistory.RankedCauseSnapshot;
import com.sajo.operation_service.document.AlertHistory.StructuredAnalysisSnapshot;
import com.sajo.operation_service.service.analysis.AlertAnalysisResult;
import com.sajo.operation_service.service.analysis.CauseCategory;
import com.sajo.operation_service.service.analysis.StructuredAnalysis;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.CandidateVerdict;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.Evidence;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.RankedCause;
import com.sajo.operation_service.service.analysis.StructuredAnalysis.Verdict;
import com.sajo.operation_service.service.history.AlertHistoryService;
import com.sajo.operation_service.service.analysis.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// 목으로는 확인할 수 없는 것들 - 실제 BSON 매핑(중첩 record/맵/Instant), @CompoundIndex 생성, Mongo 장애 시 fail-fast - 을 실제 Mongo로 검증한다
@Testcontainers
@EnabledIfDockerAvailable
@DataMongoTest
@DisplayName("알람 이력 Mongo 통합 테스트")
class AlertHistoryMongoIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @Autowired
    private AlertHistoryRepository alertHistoryRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    private AlertHistoryService alertHistoryService;

    @BeforeEach
    void setUp() {
        alertHistoryRepository.deleteAll();
        alertHistoryService = new AlertHistoryService(alertHistoryRepository, new SimpleMeterRegistry());
    }

    private AlertManagerWebhookRequest.Alert alert(String status) {
        return new AlertManagerWebhookRequest.Alert(
                status,
                Map.of("alertname", "HighCpuUsage", "application", "trading-service", "severity", "warning"),
                Map.of("summary", "CPU 사용률 95% 초과", "description", "5분간 지속"),
                Instant.parse("2026-10-05T03:00:00Z"),
                Instant.parse("2026-10-05T03:05:00Z"),
                "fp-123"
        );
    }

    @Test
    @DisplayName("저장한 이력을 다시 읽으면 중첩 스냅샷(라벨 맵, Instant, 토큰, 구조화 분석)이 그대로 복원된다")
    void recordAnalyzed_roundTripsThroughMongo() {
        StructuredAnalysis structured = new StructuredAnalysis(
                List.of("CPU 사용률 0.92"),
                List.of(new CandidateVerdict("trading-service", Verdict.LIKELY, CauseCategory.CPU,
                        List.of(new Evidence("CPU 사용률(0~1)", "0.92")), "CPU 포화")),
                List.of(new RankedCause("trading-service", CauseCategory.CPU, "CPU 포화")),
                List.of("스케줄러 확인")
        );
        AlertAnalysisResult result = new AlertAnalysisResult(
                "분석 결과", structured, List.of("후보 누락: host"), "system", "user", "gpt-test", new TokenUsage(100, 20, 120), 1500L);

        alertHistoryService.recordAnalyzed(alert("firing"), result, "1.1", "1.2");

        List<AlertHistory> all = alertHistoryRepository.findAll();
        assertThat(all).hasSize(1);
        AlertHistory saved = all.getFirst();
        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getEventType()).isEqualTo(AlertHistoryEventType.ANALYZED);
        assertThat(saved.getAlert().labels()).containsEntry("severity", "warning");
        assertThat(saved.getAlert().startsAt()).isEqualTo(Instant.parse("2026-10-05T03:00:00Z"));
        assertThat(saved.getAnalysis().totalTokens()).isEqualTo(120);
        assertThat(saved.getAnalysis().structuredAnalysis()).isEqualTo(new StructuredAnalysisSnapshot(
                List.of("CPU 사용률 0.92"),
                List.of(new CandidateSnapshot("trading-service", "LIKELY", "CPU",
                        List.of(new EvidenceSnapshot("CPU 사용률(0~1)", "0.92")), "CPU 포화")),
                List.of(new RankedCauseSnapshot("trading-service", "CPU", "CPU 포화")),
                List.of("스케줄러 확인")
        ));
        assertThat(saved.getAnalysis().validationErrors()).containsExactly("후보 누락: host");
        assertThat(saved.getMessageTs()).isEqualTo("1.2");
    }

    @Test
    @DisplayName("firing과 resolved는 별도 문서로 쌓이고, fingerprint + startsAt으로 같은 발생 건만 함께 조회된다")
    void firingAndResolved_areSeparateDocumentsLinkedByFingerprintAndStartsAt() {
        alertHistoryService.recordAnalysisSkipped(alert("firing"), "1.1", "1.2");
        alertHistoryService.recordResolved(alert("resolved"), "1.3");
        // 같은 알람(같은 라벨 → 같은 fingerprint)이 다음 날 다시 울린 건 - 섞이면 안 됨
        alertHistoryService.recordAnalysisSkipped(new AlertManagerWebhookRequest.Alert(
                "firing", alert("firing").labels(), Map.of(),
                Instant.parse("2026-10-06T03:00:00Z"), Instant.parse("0001-01-01T00:00:00Z"), "fp-123"
        ), "2.1", "2.2");

        List<Document> docs = mongoTemplate.getCollection("p_alert_histories")
                .find(new Document("alert.fingerprint", "fp-123")
                        .append("alert.startsAt", Date.from(Instant.parse("2026-10-05T03:00:00Z"))))
                .into(new ArrayList<>());

        assertThat(docs).extracting(doc -> doc.getString("eventType"))
                .containsExactlyInAnyOrder("ANALYSIS_SKIPPED", "RESOLVED");
    }

    @Test
    @DisplayName("MongoIndexInitializer가 @CompoundIndex 정의대로 인덱스를 만든다")
    void indexInitializer_createsDeclaredIndexes() {
        new MongoIndexInitializer(mongoTemplate).createIndexes();

        List<String> indexNames = mongoTemplate.indexOps(AlertHistory.class).getIndexInfo().stream()
                .map(info -> info.getName())
                .toList();
        assertThat(indexNames).contains("idx_application_starts_at", "idx_event_type_created_at", "idx_fingerprint_starts_at");
    }

    @Test
    @DisplayName("Mongo에 연결할 수 없으면 serverSelectionTimeout 안에 포기하고 예외를 던지지 않는다 - 알람 처리 스레드를 오래 붙잡지 않음")
    void record_mongoUnreachable_failsFastWithoutThrowing() {
        // 아무것도 안 떠 있는 포트 - application.yaml과 같은 방식(URI 옵션)으로 타임아웃을 준다
        try (MongoClient unreachable = MongoClients.create(
                "mongodb://localhost:1/operation_db?serverSelectionTimeoutMS=500&connectTimeoutMS=500")) {
            MongoTemplate template = new MongoTemplate(unreachable, "operation_db");
            AlertHistoryRepository repository = new MongoRepositoryFactory(template).getRepository(AlertHistoryRepository.class);
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            AlertHistoryService service = new AlertHistoryService(repository, meterRegistry);

            long startedAt = System.nanoTime();
            assertThatCode(() -> service.recordResolved(alert("resolved"), "1.3")).doesNotThrowAnyException();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
            assertThat(meterRegistry.get("alert_history_save_failures_total").counter().count()).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("구조화 분석은 응답과 같은 필드 경로에 enum 이름 문자열로 저장된다 - 평가 쿼리 경로가 유지된다")
    void recordAnalyzed_storesStructuredAnalysisAtSamePaths() {
        StructuredAnalysis structured = new StructuredAnalysis(
                List.of(),
                List.of(new CandidateVerdict("postgres", Verdict.LIKELY, CauseCategory.LOCK, List.of(), "락 대기")),
                List.of(new RankedCause("postgres", CauseCategory.LOCK, "락 대기")),
                List.of()
        );
        alertHistoryService.recordAnalyzed(alert("firing"), new AlertAnalysisResult(
                "분석 결과", structured, List.of(), "system", "user", "gpt-test", new TokenUsage(1, 2, 3), 10L), "1.1", "1.2");

        List<Document> docs = mongoTemplate.getCollection("p_alert_histories")
                .find(new Document("analysis.structuredAnalysis.topCauses.0.component", "postgres")
                        .append("analysis.structuredAnalysis.candidates.0.verdict", "LIKELY")
                        .append("analysis.structuredAnalysis.topCauses.0.category", "LOCK"))
                .into(new ArrayList<>());

        assertThat(docs).hasSize(1);
    }

    @Test
    @DisplayName("지금 enum에 없는 verdict/category 값이 든 이전 문서도 예외 없이 읽힌다 - 응답 스키마가 바뀌어도 이력 조회가 깨지지 않는다")
    void legacyDocumentWithUnknownEnumValue_isReadable() {
        Document candidate = new Document("component", "postgres").append("verdict", "WEAK")
                .append("category", "REMOVED_CATEGORY").append("evidence", List.of()).append("reasoning", "이유");
        mongoTemplate.getCollection("p_alert_histories").insertOne(new Document("eventType", "ANALYZED")
                .append("analysis", new Document("response", "원문")
                        .append("structuredAnalysis", new Document("candidates", List.of(candidate))
                                .append("topCauses", List.of()))
                        .append("latencyMs", 10L)));

        List<AlertHistory> all = alertHistoryRepository.findAll();

        assertThat(all).hasSize(1);
        assertThat(all.getFirst().getAnalysis().structuredAnalysis().candidates().getFirst().verdict()).isEqualTo("WEAK");
    }
}
