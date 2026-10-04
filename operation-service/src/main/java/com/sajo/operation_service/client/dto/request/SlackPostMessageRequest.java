package com.sajo.operation_service.client.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// Slack chat.postMessage 요청 본문 - 메시지 내용(SlackMessageRequest)에 채널/스레드 정보를 SlackClient가 덧붙여 만든다.
// threadTs가 null이면 필드 자체를 빼서 새 메시지로, 값이 있으면 그 메시지의 스레드 답글로 발송된다.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SlackPostMessageRequest(
        String channel,
        @JsonProperty("thread_ts") String threadTs,
        List<SlackMessageRequest.Attachment> attachments
) {

    public static SlackPostMessageRequest of(String channel, String threadTs, SlackMessageRequest message) {
        return new SlackPostMessageRequest(channel, threadTs, message.attachments());
    }
}
