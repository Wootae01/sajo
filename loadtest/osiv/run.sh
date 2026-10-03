#!/usr/bin/env bash
# OSIV 부하 테스트 1회 실행 래퍼.
#
# 1) mock KIS 잔고조회(balance)에 지연을 건다 (끝나거나 중단되면 자동 해제)
# 2) 측정 조건(시각 / user-service 이미지 / OSIV 설정 / KIS 지연 / 속도)을 출력한다
# 3) k6(deposit-osiv.js)를 실행하면서 user-service CPU/메모리 사용량을 수집한다
# 4) 끝나면 최대 CPU/메모리를 요약 출력하고 결과를 results/에 남긴다 (커넥션 풀 등 서버 내부 지표는 Grafana에서 확인)
#
# 사용법:
#   bash loadtest/osiv/run.sh <KIS 지연 ms> [초당 요청 수(기본 40)] [유지 시간(기본 90s)]
#   (측정 전 지연 없이 워밍업을 먼저 돌린다 - 길이는 WARMUP 환경변수, 기본 20s, 0이면 생략)
#   예) bash loadtest/osiv/run.sh 100
#       bash loadtest/osiv/run.sh 500
#       bash loadtest/osiv/run.sh 1000
#
# 사전 준비: create-users.sh로 users.csv 생성 + 아래처럼 user-service/mock-kis-server 기동
#   수정 후(OSIV 꺼짐, application.yaml 기본):
#     docker compose -f docker-compose.yaml -f docker-compose.override.yaml -f docker-compose.mock.yaml \
#       -f loadtest/docker-compose.loadtest.yaml up -d --build user-service
#   수정 전 재현(OSIV 켜짐): 위 명령 앞에 SPRING_JPA_OPEN_IN_VIEW=true 를 붙여 실행

set -euo pipefail

DELAY_MS="${1:?KIS 지연(ms)을 첫 번째 인자로 지정하세요. 예) bash loadtest/osiv/run.sh 1000}"
RATE="${2:-40}"
DURATION="${3:-90s}"
USER_SERVICE_URL="${USER_SERVICE_URL:-http://localhost:8081}"
MOCK_ADMIN_URL="${MOCK_ADMIN_URL:-http://localhost:8095/mock/admin}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULT_DIR="$SCRIPT_DIR/results"
mkdir -p "$RESULT_DIR"

# k6 실패는 아래에서 무시(|| true)하므로, 없으면 빈 결과만 찍히지 않게 시작 전에 막는다
if ! command -v k6 >/dev/null 2>&1; then
    echo "k6를 찾을 수 없습니다. k6가 설치된 셸에서 실행하세요(Windows k6.exe는 Git Bash에서는 보이지만 WSL에서는 보이지 않음)." >&2
    exit 1
fi

if [[ ! -s "$SCRIPT_DIR/users.csv" ]]; then
    echo "users.csv가 없습니다. 먼저 bash loadtest/osiv/create-users.sh 를 실행하세요." >&2
    exit 1
fi

# 측정 조건
USER_SERVICE_IMAGE="$(docker inspect user-service --format '{{.Config.Image}}' 2>/dev/null || echo unknown)"
# OSIV 판단 순서: ① 환경변수로 덮어썼으면(SPRING_JPA_OPEN_IN_VIEW=true docker compose ...) 그 값
# ② 아니면 기동 로그의 Spring 경고("open-in-view is enabled by default")로 판단 - 설정이 없는 옛 이미지면 이 경고가 찍힌다
# ③ 둘 다 아니면 application.yaml의 open-in-view: false
OSIV_ENV="$(docker inspect user-service --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
    | grep '^SPRING_JPA_OPEN_IN_VIEW=' | cut -d= -f2- || true)"
if [[ -n "$OSIV_ENV" ]]; then
    OSIV="${OSIV_ENV}(환경변수)"
elif docker logs user-service 2>&1 | grep -q 'open-in-view is enabled by default'; then
    OSIV="true(Spring 기본값)"
else
    OSIV="false(application.yaml)"
fi
OSIV_LABEL="$([[ "$OSIV" == false* ]] && echo off || echo on)"
# 자원 제한 (0이면 제한 없음) - loadtest/docker-compose.loadtest.yaml로 띄웠는지 확인용
CPU_LIMIT="$(docker inspect user-service --format '{{.HostConfig.NanoCpus}}' 2>/dev/null \
    | awk '{print ($1>0) ? $1/1e9 "코어" : "제한없음"}')"
MEM_LIMIT="$(docker inspect user-service --format '{{.HostConfig.Memory}}' 2>/dev/null \
    | awk '{print ($1>0) ? $1/1024/1024 "MiB" : "제한없음"}')"

RUN_NAME="osiv-${OSIV_LABEL}_delay-${DELAY_MS}ms_rate-${RATE}_$(date '+%Y%m%d-%H%M%S')"
STATS_LOG="$RESULT_DIR/${RUN_NAME}_stats.tsv"

STATS_PID=""
cleanup() {
    [[ -n "$STATS_PID" ]] && kill "$STATS_PID" 2>/dev/null || true
    # 다음 측정이나 평소 사용에 지연이 남지 않도록 항상 해제
    curl -s -o /dev/null -X DELETE "$MOCK_ADMIN_URL/faults" || true
}
trap cleanup EXIT

# 이전에 걸린 장애가 쌓여 있지 않도록 먼저 전부 해제
curl -sf -o /dev/null -X DELETE "$MOCK_ADMIN_URL/faults"

# 워밍업 - OSIV 전환 시 user-service를 재시작하므로, JIT 컴파일/클래스 로딩/L1 캐시 적재 같은
# 콜드 스타트 비용이 측정 앞부분에 섞이지 않게 지연 없이 먼저 돌리고 결과는 버린다
WARMUP="${WARMUP:-20s}"
if [[ "$WARMUP" != 0 ]]; then
    echo "워밍업 ${WARMUP} (KIS 지연 없음, 결과 미집계)..."
    k6 run --quiet --no-summary -e RATE="$RATE" -e DURATION="$WARMUP" -e BASE_URL="$USER_SERVICE_URL" \
        "$SCRIPT_DIR/deposit-osiv.js" > /dev/null 2>&1 || true
fi

# 지연 주입
if [[ "$DELAY_MS" -gt 0 ]]; then
    curl -sf -o /dev/null -X POST "$MOCK_ADMIN_URL/faults" -H 'Content-Type: application/json' \
        -d "{\"api\":\"balance\",\"type\":\"DELAY\",\"delayMs\":$DELAY_MS}"
fi

echo "[조건] $(date '+%Y-%m-%d %H:%M:%S') / user-service=${USER_SERVICE_IMAGE} (CPU ${CPU_LIMIT}, 메모리 ${MEM_LIMIT}) / OSIV=${OSIV} / KIS 지연=${DELAY_MS}ms / 예수금 ${RATE}건/s × ${DURATION}"

# user-service CPU/메모리 사용량 수집 (docker stats 1회 호출이 1초 이상 걸려 간격은 대략적)
(
    echo -e "cpu_percent\tmem_mib"
    while true; do
        docker stats --no-stream --format '{{.CPUPerc}}\t{{.MemUsage}}' user-service 2>/dev/null \
            | awk -F'\t' '{
                gsub("%", "", $1); split($2, m, " / "); v = m[1];
                if (v ~ /GiB/) { sub("GiB", "", v); v = v * 1024 } else { sub("MiB", "", v) }
                printf "%s\t%.0f\n", $1, v }'
    done
) > "$STATS_LOG" &
STATS_PID=$!

k6 run --quiet \
    -e RATE="$RATE" -e DURATION="$DURATION" -e BASE_URL="$USER_SERVICE_URL" \
    --summary-export "$RESULT_DIR/${RUN_NAME}_k6.json" \
    "$SCRIPT_DIR/deposit-osiv.js" || true

kill "$STATS_PID" 2>/dev/null || true
STATS_PID=""
MAX_CPU="$(tail -n +2 "$STATS_LOG" | awk -F'\t' '{if($1+0>m)m=$1+0} END{print m+0}')"
MAX_MEM="$(tail -n +2 "$STATS_LOG" | awk -F'\t' '{if($2+0>m)m=$2+0} END{print m+0}')"

echo "----------------------------------------"
echo "[자원] 최대 CPU ${MAX_CPU}% (200%=2코어) / 최대 메모리 ${MAX_MEM}MiB (제한 ${MEM_LIMIT})"
echo "결과: $RESULT_DIR/${RUN_NAME}_*"
echo "----------------------------------------"
