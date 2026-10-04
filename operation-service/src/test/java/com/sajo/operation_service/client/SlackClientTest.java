package com.sajo.operation_service.client;

import com.sajo.operation_service.client.dto.request.SlackMessageRequest;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// RestClient 체인을 mock하면 실제로 나가는 JSON(thread_ts 이름, null 필드 생략)과 헤더를 검증할 수 없어서,
// JDK 내장 HttpServer로 가짜 Slack API를 띄우고 api-base-url을 그쪽으로 향하게 해서 실제 요청/응답을 주고받는다.
class SlackClientTest {

    private static final String BOT_TOKEN = "xoxb-test-token";
    private static final String CHANNEL_ID = "C0TEST";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private HttpServer server;
    private SimpleMeterRegistry meterRegistry;

    // 가짜 서버가 받은 마지막 요청과, 돌려줄 응답
    private volatile String receivedPath;
    private volatile String receivedAuthorization;
    private volatile String receivedContentType;
    private volatile String receivedBody;
    private volatile int responseStatus;
    private volatile String responseBody;

    @BeforeEach
    void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath = exchange.getRequestURI().getPath();
            receivedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            receivedContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            // 길이 -1 = 본문 없음
            exchange.sendResponseHeaders(responseStatus, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();

        meterRegistry = new SimpleMeterRegistry();
        respondWith(200, "{\"ok\":true,\"channel\":\"C0TEST\",\"ts\":\"1728000000.000100\"}");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("send는 chat.postMessage로 channel/attachments를 보내고 thread_ts는 생략하며, 응답의 ts를 반환한다")
    void send_success_returnsTsWithoutThreadTs() {
        Optional<String> ts = slackClient().send(SlackMessageRequest.of("danger", "본문"));

        assertThat(ts).contains("1728000000.000100");
        assertThat(receivedPath).isEqualTo("/chat.postMessage");

        JsonNode body = objectMapper.readTree(receivedBody);
        assertThat(body.get("channel").asString()).isEqualTo(CHANNEL_ID);
        assertThat(body.has("thread_ts")).isFalse();
        assertThat(body.get("attachments").get(0).get("color").asString()).isEqualTo("danger");
        assertThat(body.get("attachments").get(0).get("text").asString()).isEqualTo("본문");
        assertThat(failureCount()).isZero();
    }

    @Test
    @DisplayName("reply는 thread_ts에 원본 메시지 ts를 담아 보낸다")
    void reply_success_sendsThreadTs() {
        Optional<String> ts = slackClient().reply("1728000000.000001", SlackMessageRequest.of("good", "답글"));

        assertThat(ts).isPresent();
        JsonNode body = objectMapper.readTree(receivedBody);
        assertThat(body.get("thread_ts").asString()).isEqualTo("1728000000.000001");
        assertThat(body.get("channel").asString()).isEqualTo(CHANNEL_ID);
    }

    @Test
    @DisplayName("Bot 토큰을 Bearer 헤더로, Content-Type은 UTF-8 charset을 포함해 보낸다")
    void send_sendsBearerTokenAndUtf8ContentType() {
        slackClient().send(SlackMessageRequest.of("danger", "본문"));

        assertThat(receivedAuthorization).isEqualTo("Bearer " + BOT_TOKEN);
        assertThat(receivedContentType).startsWith("application/json").containsIgnoringCase("charset=UTF-8");
    }

    @Test
    @DisplayName("HTTP 200이어도 ok=false면 실패로 보고 빈 값을 반환하며 실패 카운터를 증가시킨다")
    void send_okFalse_returnsEmptyAndIncrementsCounter() {
        respondWith(200, "{\"ok\":false,\"error\":\"not_in_channel\"}");

        Optional<String> ts = slackClient().send(SlackMessageRequest.of("danger", "본문"));

        assertThat(ts).isEmpty();
        assertThat(failureCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("응답 본문이 비어 있으면 빈 값을 반환하고 실패 카운터를 증가시킨다")
    void send_emptyBody_returnsEmptyAndIncrementsCounter() {
        respondWith(200, "");

        Optional<String> ts = slackClient().send(SlackMessageRequest.of("danger", "본문"));

        assertThat(ts).isEmpty();
        assertThat(failureCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Slack이 5xx로 응답해도 예외를 던지지 않고 빈 값 반환 + 실패 카운터를 증가시킨다")
    void send_serverError_returnsEmptyAndIncrementsCounter() {
        respondWith(500, "boom");

        Optional<String> ts = slackClient().send(SlackMessageRequest.of("danger", "본문"));

        assertThat(ts).isEmpty();
        assertThat(failureCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Slack이 429(rate limit)로 응답해도 예외를 던지지 않고 빈 값 반환 + 실패 카운터를 증가시킨다")
    void send_rateLimited_returnsEmptyAndIncrementsCounter() {
        respondWith(429, "");

        Optional<String> ts = slackClient().send(SlackMessageRequest.of("danger", "본문"));

        assertThat(ts).isEmpty();
        assertThat(failureCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("연결 자체가 안 돼도 예외를 던지지 않고 빈 값 반환 + 실패 카운터를 증가시킨다")
    void send_connectionFailure_returnsEmptyAndIncrementsCounter() {
        SlackClient client = slackClient();
        server.stop(0);

        Optional<String> ts = client.send(SlackMessageRequest.of("danger", "본문"));

        assertThat(ts).isEmpty();
        assertThat(failureCount()).isEqualTo(1.0);
    }

    private SlackClient slackClient() {
        String apiBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new SlackClient(RestClient.builder(), meterRegistry, BOT_TOKEN, CHANNEL_ID, apiBaseUrl, 2000, 5000);
    }

    private void respondWith(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    private double failureCount() {
        return meterRegistry.get("slack_send_failures_total").counter().count();
    }
}
