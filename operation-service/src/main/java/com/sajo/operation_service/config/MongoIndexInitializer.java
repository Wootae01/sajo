package com.sajo.operation_service.config;

import com.sajo.operation_service.document.AlertHistory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.stereotype.Component;

// operation-service에게 Mongo는 부가 의존성(알람 이력 저장)이라, Mongo가 죽어 있어도 앱은 떠서 알람을 Slack으로 보내야 한다.
// spring.data.mongodb.auto-index-creation은 매핑 초기화 중 인덱스를 만들다 실패하면 기동 자체를 깨뜨릴 수 있어서 쓰지 않고,
// 기동 완료 후 @CompoundIndex 정의대로 직접 만들되 실패는 로그만 남긴다(createIndex는 멱등이라 재기동 시 다시 시도됨).
@Slf4j
@Component
@RequiredArgsConstructor
public class MongoIndexInitializer {

    private final MongoTemplate mongoTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void createIndexes() {
        try {
            IndexOperations indexOps = mongoTemplate.indexOps(AlertHistory.class);
            IndexResolver resolver = IndexResolver.create(mongoTemplate.getConverter().getMappingContext());
            resolver.resolveIndexFor(AlertHistory.class).forEach(indexOps::createIndex);
        } catch (Exception e) {
            log.warn("알람 이력 인덱스 생성 실패 - 이력 저장은 계속 시도하며, 다음 기동 시 다시 생성한다", e);
        }
    }
}
