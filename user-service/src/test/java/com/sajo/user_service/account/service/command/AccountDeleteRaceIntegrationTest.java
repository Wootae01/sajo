package com.sajo.user_service.account.service.command;

import com.sajo.common.exception.BusinessException;
import com.sajo.user_service.account.client.feign.TradingFeignClient;
import com.sajo.user_service.account.client.feign.dto.response.TradingActiveStatusResponse;
import com.sajo.user_service.account.controller.internal.AccountInternalController;
import com.sajo.user_service.account.domain.Account;
import com.sajo.user_service.account.domain.AccountType;
import com.sajo.user_service.account.exception.AccountErrorCode;
import com.sajo.user_service.account.repository.command.AccountCommandRepository;
import com.sajo.user_service.account.service.query.KisTokenCacheQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

// 계좌 삭제의 "trading 활성 거래 확인 ~ 실제 삭제" 구간에 들어온 주문용 계좌 조회가
// 삭제 중인 계좌 정보를 가져가지 못하는지 검증한다.
//
// trading 확인 응답을 latch로 붙잡아 그 구간을 강제로 벌려 두고, 그동안 trading-service가
// 주문 직전에 호출하는 내부 API(/token, /order-info)를 실제 DB 상태 기준으로 호출한다.
// 상태 변경이 커밋돼야 다른 요청에서 보이므로 테스트 자체에는 @Transactional을 붙이지 않는다.
@SpringBootTest
class AccountDeleteRaceIntegrationTest {

    private static final int PROBE_COUNT = 10;

    @Autowired
    private AccountDeleteFacade accountDeleteFacade;

    @Autowired
    private AccountInternalController accountInternalController;

    @Autowired
    private AccountCommandRepository accountCommandRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private TradingFeignClient tradingFeignClient;

    // 실제 KIS/Redis 호출 없이 토큰 발급 경로를 끝까지 통과시키기 위한 mock
    @MockitoBean
    private KisTokenCacheQueryService kisTokenCacheQueryService;

    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM p_accounts WHERE user_id = ?", userId);
    }

    @Test
    @DisplayName("활성 거래 확인 ~ 삭제 사이에 들어온 주문용 계좌 조회(/token, /order-info)는 전부 거부된다")
    void rejectsOrderLookupsWhileDeletionIsInProgress() throws Exception {
        accountCommandRepository.saveAndFlush(Account.createAccount(
                userId, "race-app-key", "race-secret-key", "12345678-01",
                "race-hash-" + userId, AccountType.VIRTUAL));

        given(kisTokenCacheQueryService.getAccessToken(eq(userId), any(), any(), any(), any()))
                .willReturn("race-access-token");

        CountDownLatch checkEntered = new CountDownLatch(1);
        CountDownLatch releaseCheck = new CountDownLatch(1);
        given(tradingFeignClient.getActiveStatus(userId)).willAnswer(invocation -> {
            checkEntered.countDown();
            if (!releaseCheck.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트가 trading 확인 응답을 풀어주지 않음");
            }
            // trading이 확인한 시점에는 활성 거래가 없었다 - 그 뒤에 들어오는 주문은 이 판정에 잡히지 않는다.
            return new TradingActiveStatusResponse(false);
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> deletion = executor.submit(() -> accountDeleteFacade.deleteAccount(userId));
            assertThat(checkEntered.await(10, TimeUnit.SECONDS))
                    .as("삭제 흐름이 trading 확인 단계에 도달해야 함")
                    .isTrue();

            // 삭제가 trading 확인에서 멈춰 있는 동안 주문 경로의 계좌 조회를 호출
            int leakedTokens = countLeaks(id -> accountInternalController.getToken(id));
            int leakedOrderInfos = countLeaks(id -> accountInternalController.getAccountOrderInfo(id));

            releaseCheck.countDown();
            deletion.get(10, TimeUnit.SECONDS);

            assertSoftly(softly -> {
                softly.assertThat(leakedTokens)
                        .as("삭제 진행 중 /token이 자격증명(appKey/secretKey/토큰)을 반환한 횟수 (총 %d회 호출)", PROBE_COUNT)
                        .isZero();
                softly.assertThat(leakedOrderInfos)
                        .as("삭제 진행 중 /order-info가 계좌번호를 반환한 횟수 (총 %d회 호출)", PROBE_COUNT)
                        .isZero();
            });
        } finally {
            releaseCheck.countDown();
            executor.shutdownNow();
        }
    }

    // 성공 응답 = 노출로 센다. ACCOUNT_NOT_FOUND 거부만 정상 차단으로 인정하고,
    // 그 외 예외는 설정 문제일 수 있으므로 "거부"로 오인하지 않도록 그대로 던진다.
    private int countLeaks(Consumer<UUID> lookup) {
        int leaked = 0;
        for (int i = 0; i < PROBE_COUNT; i++) {
            try {
                lookup.accept(userId);
                leaked++;
            } catch (BusinessException e) {
                if (e.getErrorCode() != AccountErrorCode.ACCOUNT_NOT_FOUND) {
                    throw e;
                }
            }
        }
        return leaked;
    }
}
