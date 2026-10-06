package com.sajo.operation_service.service.history;

import com.sajo.operation_service.config.AsyncConfig;
import com.sajo.operation_service.controller.dto.request.AlertManagerWebhookRequest;
import com.sajo.operation_service.document.AlertHistory;
import com.sajo.operation_service.repository.AlertHistoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// @Async 프록시가 실제로 적용돼야 Mongo 대기가 호출한 알람 처리 스레드를 붙잡지 않는다 - 직접 new로는 확인할 수 없어서
// AsyncConfig와 함께 스프링 컨텍스트를 띄워 검증한다. 테스트마다 풀 스레드를 막아 두므로 컨텍스트를 새로 띄운다.
@SpringJUnitConfig({AsyncConfig.class, AlertHistoryService.class, SimpleMeterRegistry.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AlertHistoryServiceAsyncTest {

    @Autowired
    private AlertHistoryService alertHistoryService;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private AlertHistoryRepository alertHistoryRepository;

    // Mongo가 응답하지 않는 상황 - 테스트가 끝나면 풀어 줘서 풀 종료가 막히지 않게 한다
    private final CountDownLatch mongoBlocked = new CountDownLatch(1);

    @AfterEach
    void releaseMongo() {
        mongoBlocked.countDown();
    }

    private AlertManagerWebhookRequest.Alert resolvedAlert() {
        return new AlertManagerWebhookRequest.Alert(
                "resolved",
                Map.of("alertname", "RedisConnectionDown", "application", "redis"),
                Map.of(),
                Instant.parse("2026-10-05T02:39:36Z"),
                Instant.parse("2026-10-05T02:41:06Z"),
                "fp-123"
        );
    }

    @Test
    @DisplayName("Mongo 저장이 멈춰 있어도 record는 바로 반환하고, 저장은 alert-history 스레드에서 실행된다")
    void record_doesNotWaitForMongo_andRunsOnHistoryExecutor() {
        AtomicReference<String> savingThread = new AtomicReference<>();
        when(alertHistoryRepository.insert(any(AlertHistory.class))).thenAnswer(invocation -> {
            savingThread.set(Thread.currentThread().getName());
            mongoBlocked.await(5, TimeUnit.SECONDS);
            return invocation.getArgument(0);
        });

        long startedAt = System.nanoTime();
        alertHistoryService.recordResolved(resolvedAlert(), "1.3");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        verify(alertHistoryRepository, timeout(2000)).insert(any(AlertHistory.class));
        assertThat(savingThread.get()).startsWith("alert-history-");
    }

    @Test
    @DisplayName("저장 큐가 가득 차면 호출 스레드에서 대신 실행하지 않고 버린 뒤 alert_history_rejected_total을 올린다")
    void record_queueFull_discardsWithoutBlockingCaller() {
        CountDownLatch bothThreadsBusy = new CountDownLatch(2);
        when(alertHistoryRepository.insert(any(AlertHistory.class))).thenAnswer(invocation -> {
            bothThreadsBusy.countDown();
            mongoBlocked.await(5, TimeUnit.SECONDS);
            return invocation.getArgument(0);
        });

        // 스레드 2개를 Mongo 대기로 막고, 큐(200)를 채운 뒤 1건을 더 넣는다
        alertHistoryService.recordResolved(resolvedAlert(), "1.3");
        alertHistoryService.recordResolved(resolvedAlert(), "1.3");
        awaitQuietly(bothThreadsBusy);
        for (int i = 0; i < 200; i++) {
            alertHistoryService.recordResolved(resolvedAlert(), "1.3");
        }

        long startedAt = System.nanoTime();
        alertHistoryService.recordResolved(resolvedAlert(), "1.3");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        assertThat(meterRegistry.get("alert_history_rejected_total").counter().count()).isEqualTo(1.0);
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
