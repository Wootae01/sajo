package com.sajo.operation_service.service;

import com.sajo.operation_service.config.AsyncConfig;
import com.sajo.operation_service.controller.dto.request.AlertManagerWebhookRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// @Async 프록시가 실제로 적용돼야 웹훅 요청 스레드가 Slack 응답을 기다리지 않는다 - 단위 테스트(직접 new)로는
// 확인할 수 없어서 AsyncConfig와 함께 스프링 컨텍스트를 띄워 검증한다.
@SpringJUnitConfig({AsyncConfig.class, AlertReceiveService.class})
class AlertReceiveServiceAsyncTest {

    @Autowired
    private AlertReceiveService alertReceiveService;

    @MockitoBean
    private SlackNotifier slackNotifier;

    @MockitoBean
    private AlertAnalysisAsyncProcessor alertAnalysisAsyncProcessor;

    @Test
    @DisplayName("Slack 원본 발송이 멈춰 있어도 receive는 바로 반환하고, 발송은 alert-notify 스레드에서 실행된다")
    void receive_doesNotWaitForSlack_andRunsOnNotifyExecutor() throws Exception {
        CountDownLatch slackBlocked = new CountDownLatch(1);
        AtomicReference<String> sendingThread = new AtomicReference<>();
        when(slackNotifier.postOriginal(any())).thenAnswer(invocation -> {
            sendingThread.set(Thread.currentThread().getName());
            slackBlocked.await(5, TimeUnit.SECONDS);  // Slack이 응답하지 않는 상황
            return Optional.of("1728000000.000100");
        });
        AlertManagerWebhookRequest.Alert firing = new AlertManagerWebhookRequest.Alert(
                "firing",
                Map.of("alertname", "HighCpuUsage", "application", "trading-service"),
                Map.of(),
                Instant.parse("2026-09-17T03:00:00Z"),
                Instant.parse("2026-09-17T03:05:00Z")
        );

        long start = System.nanoTime();
        alertReceiveService.receive(new AlertManagerWebhookRequest("firing", List.of(firing)));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMillis).isLessThan(1000);

        slackBlocked.countDown();
        verify(alertAnalysisAsyncProcessor, timeout(3000)).analyze(firing, "1728000000.000100");
        assertThat(sendingThread.get()).startsWith("alert-notify-");
    }
}
