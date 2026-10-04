package com.sajo.operation_service.client.dto.response;

// Slack chat.postMessage 응답 - 실패해도 HTTP 200으로 오고 ok=false + error(예: not_in_channel)로 구분된다.
// ts는 발송된 메시지의 ID로, 스레드 답글을 달 때 thread_ts로 쓴다.
public record SlackPostMessageResponse(
        boolean ok,
        String ts,
        String error
) {
}
