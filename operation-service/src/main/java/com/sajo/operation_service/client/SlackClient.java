package com.sajo.operation_service.client;

import com.sajo.operation_service.client.dto.request.SlackMessageRequest;
import com.sajo.operation_service.client.dto.request.SlackPostMessageRequest;
import com.sajo.operation_service.client.dto.response.SlackPostMessageResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Slf4j
@Component
public class SlackClient {

    // Slack Web API는 charset이 없는 JSON 요청에 missing_charset 경고를 붙여 응답한다
    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    private final RestClient restClient;
    private final String channelId;
    private final Counter slackSendFailureCounter;

    public SlackClient(
            RestClient.Builder restClientBuilder,
            MeterRegistry meterRegistry,
            @Value("${sajo.slack.bot-token}") String botToken,
            @Value("${sajo.slack.channel-id}") String channelId,
            @Value("${sajo.slack.api-base-url}") String apiBaseUrl,
            @Value("${sajo.slack.rest-client.connect-timeout-ms:2000}") int connectTimeoutMillis,
            @Value("${sajo.slack.rest-client.read-timeout-ms:5000}") int readTimeoutMillis
    ) {
        this.channelId = channelId;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMillis);
        requestFactory.setReadTimeout(readTimeoutMillis);

        // Bot 토큰은 Authorization 헤더에만 실려 uri 라벨/span에는 남지 않지만, 외부 SaaS인 Slack으로
        // traceId 헤더(traceparent 등)가 나가지 않도록 계측에서 제외한다.
        // 전송 실패 감시는 아래 slack_send_failures_total 카운터로 충분하다.
        this.restClient = restClientBuilder.clone()
                .baseUrl(apiBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + botToken)
                .requestFactory(requestFactory)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();

        this.slackSendFailureCounter = Counter.builder("slack_send_failures_total")
                .description("Slack 알림 전송 실패 횟수")
                .register(meterRegistry);
    }

    // 새 메시지로 발송하고, 성공하면 스레드 답글에 쓸 메시지 ts를 돌려준다
    public Optional<String> send(SlackMessageRequest message) {
        return postMessage(null, message);
    }

    // threadTs 메시지의 스레드 답글로 발송한다
    public Optional<String> reply(String threadTs, SlackMessageRequest message) {
        return postMessage(threadTs, message);
    }

    private Optional<String> postMessage(String threadTs, SlackMessageRequest message) {
        try {
            SlackPostMessageResponse response = restClient.post()
                    .uri("/chat.postMessage")
                    .contentType(JSON_UTF8)
                    .body(SlackPostMessageRequest.of(channelId, threadTs, message))
                    .retrieve()
                    .body(SlackPostMessageResponse.class);

            if (response == null) {
                slackSendFailureCounter.increment();
                log.error("Slack 전송 실패 - 응답 본문이 비어 있음");
                return Optional.empty();
            }
            // chat.postMessage는 실패해도 HTTP 200으로 응답하므로 ok 필드로 판정해야 한다
            // (예: invalid_auth=토큰 오류, not_in_channel=Bot 미초대, channel_not_found=채널 ID 오류)
            if (!response.ok()) {
                slackSendFailureCounter.increment();
                log.error("Slack 전송 실패 - ok=false, error={}", response.error());
                return Optional.empty();
            }
            return Optional.ofNullable(response.ts());
        } catch (RestClientResponseException e) {
            slackSendFailureCounter.increment();
            log.error("Slack 전송 실패 - HTTP {}: {}", e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            slackSendFailureCounter.increment();
            log.error("Slack 연결 실패", e);
        }
        return Optional.empty();
    }
}
