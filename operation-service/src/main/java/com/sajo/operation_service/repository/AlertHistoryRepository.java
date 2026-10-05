package com.sajo.operation_service.repository;

import com.sajo.operation_service.document.AlertHistory;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AlertHistoryRepository extends MongoRepository<AlertHistory, String> {
}
