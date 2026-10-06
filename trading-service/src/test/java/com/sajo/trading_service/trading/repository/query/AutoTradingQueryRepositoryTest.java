package com.sajo.trading_service.trading.repository.query;

import com.sajo.common.config.CommonJpaAuditingAutoConfiguration;
import com.sajo.trading_service.trading.domain.AutoTrading;
import com.sajo.trading_service.trading.domain.enums.AutoTradingDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest
@Import(CommonJpaAuditingAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AutoTradingQueryRepositoryTest {

    @Autowired
    private AutoTradingQueryRepository autoTradingQueryRepository;

    @Test
    @DisplayName("관리자는 userId와 direction, enabled 조건으로 AutoTrading을 조회할 수 있다")
    void findAllForAdminWithConditions() {

        // given
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();

        AutoTrading autoTrading1 = AutoTrading.create(
                userId1,
                UUID.randomUUID(),
                AutoTradingDirection.SELL_ONLY
        );

        autoTrading1.update(
                true,
                null
        );

        AutoTrading autoTrading2 = AutoTrading.create(
                userId2,
                UUID.randomUUID(),
                AutoTradingDirection.BUY_ONLY
        );

        autoTradingQueryRepository.saveAllAndFlush(
                List.of(
                        autoTrading1,
                        autoTrading2
                )
        );

        // when
        Page<AutoTrading> result =
                autoTradingQueryRepository.findAllForAdmin(
                        userId1,
                        null,
                        AutoTradingDirection.SELL_ONLY,
                        true,
                        PageRequest.of(0, 10)
                );

        // then
        assertThat(result.getTotalElements())
                .isEqualTo(1);

        AutoTrading found =
                result.getContent().getFirst();

        assertThat(found.getUserId())
                .isEqualTo(userId1);

        assertThat(found.getDirection())
                .isEqualTo(AutoTradingDirection.SELL_ONLY);

        assertThat(found.getEnabled())
                .isTrue();
    }

    // existsByUserIdAndEnabledTrueAndDeletedAtIsNull은 user-service의 계좌 삭제 전 활성 거래 확인(active-status)에 쓰인다.
    @Test
    @DisplayName("사용자에게 켜진 자동매매가 있으면 활성 자동매매가 있는 것으로 판단한다")
    void existsEnabledAutoTradingByUserId() {
        // given
        UUID userId = UUID.randomUUID();

        AutoTrading autoTrading = AutoTrading.create(
                userId,
                UUID.randomUUID(),
                AutoTradingDirection.BOTH
        );

        autoTrading.update(
                true,
                null
        );

        autoTradingQueryRepository.saveAndFlush(autoTrading);

        // when
        boolean result =
                autoTradingQueryRepository
                        .existsByUserIdAndEnabledTrueAndDeletedAtIsNull(userId);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("사용자의 자동매매가 꺼져 있으면 활성 자동매매가 없는 것으로 판단한다")
    void doesNotExistEnabledAutoTradingWhenDisabled() {
        // given
        UUID userId = UUID.randomUUID();

        // AutoTrading.create() 직후 enabled = false
        autoTradingQueryRepository.saveAndFlush(
                AutoTrading.create(
                        userId,
                        UUID.randomUUID(),
                        AutoTradingDirection.BOTH
                )
        );

        // when
        boolean result =
                autoTradingQueryRepository
                        .existsByUserIdAndEnabledTrueAndDeletedAtIsNull(userId);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("논리 삭제된 켜진 자동매매는 활성 자동매매로 판단하지 않는다")
    void doesNotExistEnabledAutoTradingWhenDeleted() {
        // given
        UUID userId = UUID.randomUUID();

        AutoTrading autoTrading = AutoTrading.create(
                userId,
                UUID.randomUUID(),
                AutoTradingDirection.BOTH
        );

        autoTrading.update(
                true,
                null
        );
        autoTrading.softDelete(userId);

        autoTradingQueryRepository.saveAndFlush(autoTrading);

        // when
        boolean result =
                autoTradingQueryRepository
                        .existsByUserIdAndEnabledTrueAndDeletedAtIsNull(userId);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("다른 사용자의 켜진 자동매매는 활성 자동매매로 판단하지 않는다")
    void doesNotExistEnabledAutoTradingForOtherUser() {
        // given
        UUID targetUserId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();

        AutoTrading autoTrading = AutoTrading.create(
                otherUserId,
                UUID.randomUUID(),
                AutoTradingDirection.BOTH
        );

        autoTrading.update(
                true,
                null
        );

        autoTradingQueryRepository.saveAndFlush(autoTrading);

        // when
        boolean result =
                autoTradingQueryRepository
                        .existsByUserIdAndEnabledTrueAndDeletedAtIsNull(targetUserId);

        // then
        assertThat(result).isFalse();
    }
}