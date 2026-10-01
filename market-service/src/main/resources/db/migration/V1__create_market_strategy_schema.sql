-- market_strategy 초기 스키마 (빈 DB 부트스트랩용).
--
-- market 테이블은 원래 Hibernate ddl-auto로 생성됐고, V44~V106은 그 위에 얹는 변경 스크립트라
-- 테이블을 처음 만드는 마이그레이션이 없었다. 그래서 prod 프로필(ddl-auto: validate)로 빈 DB에
-- 띄우면 V44에서 "relation m_market_stocks_price does not exist"로 기동에 실패했다.
--
-- 이 파일은 V106까지 반영된 최종 컬럼 구조의 테이블(PK, CHECK 포함)만 만든다. unique 인덱스는
-- 넣지 않는다 - V44/V52/V103/V104/V106이 각자 만들기 때문이다. 특히 V52/V103의 "기존 unique
-- 인덱스 존재 확인"은 pg_index.indkey(int2vector, 하한 0)를 ARRAY[...](하한 1)와 비교해서 항상
-- 불일치로 판정하므로, 여기서 같은 이름의 인덱스를 미리 만들면 V52/V103이 중복 생성으로 실패한다.
-- 컬럼 추가/삭제 스크립트(V44/V53/V104/V105)는 IF [NOT] EXISTS라 이 구조 위에서 그대로 통과한다.
--
-- 기존 환경(baseline-version: 105로 Flyway를 도입한 DB)에서는 이 파일이 baseline 이하 버전이라
-- 실행되지 않는다.

CREATE TABLE market_strategy.m_market_stocks (
    id            UUID                        NOT NULL,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_by    UUID,
    deleted_at    TIMESTAMP(6) WITH TIME ZONE,
    deleted_by    UUID,
    updated_at    TIMESTAMP(6) WITH TIME ZONE,
    updated_by    UUID,
    industry_code VARCHAR(20),
    listed_shares BIGINT,
    market_cap    NUMERIC(20, 0),
    market_type   VARCHAR(20)                 NOT NULL,
    stock_code    VARCHAR(20)                 NOT NULL,
    stock_name    VARCHAR(100)                NOT NULL,
    CONSTRAINT m_market_stocks_pkey PRIMARY KEY (id)
);

CREATE TABLE market_strategy.m_market_stocks_indicator (
    id                             UUID                        NOT NULL,
    created_at                     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_by                     UUID,
    -- eps/bps는 V105가 지우는 컬럼이지만, V105의 사전 확인 쿼리가 이 컬럼을 참조하므로 만들어 둔다.
    eps                            NUMERIC(15, 2),
    bps                            NUMERIC(15, 2),
    pbr                            NUMERIC(10, 4),
    per                            NUMERIC(10, 4),
    reference_date                 DATE,
    roe                            NUMERIC(10, 4),
    stock_id                       UUID                        NOT NULL,
    updated_at                     TIMESTAMP(6) WITH TIME ZONE,
    financial_fetched_at           TIMESTAMP(6) WITH TIME ZONE,
    financial_period_type          VARCHAR(20),
    financial_reference_year_month VARCHAR(7),
    valuation_fetched_at           TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT m_market_stocks_indicator_pkey PRIMARY KEY (id),
    CONSTRAINT m_market_stocks_indicator_financial_period_type_check
        CHECK (financial_period_type = 'QUARTER')
);

CREATE TABLE market_strategy.m_market_stocks_price (
    id                       UUID                        NOT NULL,
    created_at               TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_by               UUID,
    accumulated_trade_amount BIGINT,
    accumulated_volume       BIGINT,
    change_price             BIGINT,
    change_rate              NUMERIC(10, 4),
    close_price              BIGINT,
    current_price            BIGINT,
    date                     DATE                        NOT NULL,
    foreign_ownership_rate   NUMERIC(10, 4),
    high_price               BIGINT,
    low_price                BIGINT,
    open_price               BIGINT,
    prev_close_price         BIGINT,
    source                   VARCHAR(20)                 NOT NULL,
    stock_id                 UUID                        NOT NULL,
    time                     TIME(0) WITHOUT TIME ZONE,
    volume                   BIGINT,
    CONSTRAINT m_market_stocks_price_pkey PRIMARY KEY (id),
    CONSTRAINT m_market_stocks_price_source_check CHECK (source IN ('REST', 'WEBSOCKET'))
);

CREATE TABLE market_strategy.p_strategies (
    id                  UUID                        NOT NULL,
    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_by          UUID,
    deleted_at          TIMESTAMP(6) WITH TIME ZONE,
    deleted_by          UUID,
    updated_at          TIMESTAMP(6) WITH TIME ZONE,
    updated_by          UUID,
    activated_at        TIMESTAMP(6) WITH TIME ZONE,
    allocated_amount    BIGINT                      NOT NULL,
    buy_condition_price BIGINT                      NOT NULL,
    order_amount        BIGINT,
    pbr_condition       NUMERIC(10, 4),
    per_condition       NUMERIC(10, 4),
    roe_condition       NUMERIC(10, 4),
    sell_condition_price BIGINT                     NOT NULL,
    status              VARCHAR(20)                 NOT NULL,
    stock_code          VARCHAR(6)                  NOT NULL,
    stock_id            UUID                        NOT NULL,
    stop_loss_rate      NUMERIC(10, 4)              NOT NULL,
    strategy_name       VARCHAR(100)                NOT NULL,
    target_return_rate  NUMERIC(10, 4),
    user_id             UUID                        NOT NULL,
    CONSTRAINT p_strategies_pkey PRIMARY KEY (id),
    CONSTRAINT p_strategies_status_check CHECK (status IN ('ACTIVE', 'INACTIVE', 'DELETED'))
);

CREATE TABLE market_strategy.p_strategy_backtests (
    id                     UUID                        NOT NULL,
    created_at             TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_by             UUID,
    deleted_at             TIMESTAMP(6) WITH TIME ZONE,
    deleted_by             UUID,
    updated_at             TIMESTAMP(6) WITH TIME ZONE,
    updated_by             UUID,
    end_date               DATE                        NOT NULL,
    initial_cash           BIGINT                      NOT NULL,
    max_consecutive_losses INTEGER,
    mdd                    NUMERIC(10, 4),
    requested_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    start_date             DATE                        NOT NULL,
    backtest_status        VARCHAR(20)                 NOT NULL,
    stock_code             VARCHAR(6)                  NOT NULL,
    strategy_id            UUID                        NOT NULL,
    total_return_rate      NUMERIC(10, 4),
    trade_count            INTEGER,
    user_id                UUID                        NOT NULL,
    win_rate               NUMERIC(10, 4),
    CONSTRAINT p_strategy_backtests_pkey PRIMARY KEY (id),
    CONSTRAINT p_strategy_backtests_backtest_status_check
        CHECK (backtest_status IN ('REQUESTED', 'RUNNING', 'COMPLETED', 'FAILED'))
);
