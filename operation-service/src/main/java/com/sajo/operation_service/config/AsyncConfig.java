package com.sajo.operation_service.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "alertAnalysisExecutor")
    public Executor alertAnalysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("alert-analysis-");

        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();

        return executor;
    }

    // 알람 원본/resolved Slack 발송 전용 - 웹훅 요청 스레드가 Slack 응답을 기다리지 않게 하되(Alertmanager
    // 타임아웃 시 그룹 전체 재전송 → 중복 방지), LLM 호출로 밀리는 alertAnalysisExecutor와는 분리해서
    // 분석이 쌓여 있어도 원본은 바로 나가게 한다. Slack 호출은 짧아서 작은 풀로 충분하다.
    @Bean(name = "alertNotifyExecutor")
    public Executor alertNotifyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("alert-notify-");

        // 큐가 가득 차면 웹훅 요청 스레드에서 직접 발송한다 - 느려질 뿐 알람을 버리지는 않는다
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();

        return executor;
    }

    // 알람 처리 이력(Mongo) 저장 전용 - Mongo 장애 시 저장 1건이 타임아웃(약 2초)까지 스레드를 붙잡으므로,
    // 알람 처리 스레드(특히 원본 발송 전용 alertNotifyExecutor)가 그 대기에 묶이지 않게 분리한다.
    // Mongo insert는 평소 수 ms라 작은 풀로 충분하고, 장애 중엔 스레드를 늘려도 다 같이 기다릴 뿐이라 늘리지 않는다.
    @Bean(name = "alertHistoryExecutor")
    public Executor alertHistoryExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("alert-history-");

        // 큐가 가득 차면 버린다 - CallerRuns면 Mongo 대기가 호출한 알람 처리 스레드로 되돌아와 분리한 의미가 없어진다.
        // 이력은 부가 기록이라 유실을 감수하고, 버린 건수는 메트릭으로 남긴다.
        Counter rejectedCounter = Counter.builder("alert_history_rejected_total")
                .description("알람 처리 이력 저장 큐가 가득 차 버린 횟수")
                .register(meterRegistry);
        executor.setRejectedExecutionHandler((task, pool) -> {
            rejectedCounter.increment();
            log.warn("알람 이력 저장 큐가 가득 차 이력 1건을 버립니다. queueSize={}", pool.getQueue().size());
        });

        // 종료 시 큐에 남은 이력을 잠깐 기다려 저장한다 - docker stop 유예(10초) 안에서 끝나도록 상한을 둔다
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);

        executor.initialize();

        return executor;
    }
}
