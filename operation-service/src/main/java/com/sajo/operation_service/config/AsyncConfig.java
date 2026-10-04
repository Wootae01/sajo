package com.sajo.operation_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

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
}
