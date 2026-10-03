#!/usr/bin/env bash
# KIS 토큰 캐시(L1/L2) 동시 요청 검증 스크립트.
#
# 같은 accountUserId로 N개의 동시 요청을 internal token 엔드포인트에 쏴서
# HTTP 성공/실패 건수를 측정한다.
#
# 사전 준비: docker-compose로 전체 스택 기동 + 계좌 연동 완료된 유저(accountUserId) 필요
# (http/account-api.http 00-01~01-01로 만들 수 있음)
#
# 사용법:
#   ./loadtest/kis-token-cache-concurrency.sh [동시요청수(기본 20)]                 # 기본 userId 사용
#   ./loadtest/kis-token-cache-concurrency.sh <accountUserId> [동시요청수(기본 20)]
#
# 시나리오별 실행 순서 예시:
#   A) Redis 정상 상태
#     ./loadtest/kis-token-cache-concurrency.sh <userId> 20
#
#   B) Redis 장애 + L1 비어있음 (user-service 재시작 직후 상태)
#     docker stop sajo-redis
#     docker restart user-service   # L1은 인스턴스 로컬 캐시라 재시작하면 비워짐
#     (user-service 헬스체크 통과할 때까지 대기)
#     ./loadtest/kis-token-cache-concurrency.sh <userId> 20
#
#   C) Redis 장애 + L1에 토큰 있음 (warm)
#     docker start sajo-redis
#     ./loadtest/kis-token-cache-concurrency.sh <userId> 1   # 먼저 1건 호출해 L1을 채움
#     docker stop sajo-redis
#     ./loadtest/kis-token-cache-concurrency.sh <userId> 20  # 본 측정
#
#   끝나면: docker start sajo-redis

# 에러/미정의 변수/파이프 실패 시 즉시 중단
set -euo pipefail

# 로컬 시스템 유저(실제 KIS 모의투자 계좌 연동) - userId 생략 시 사용
DEFAULT_ACCOUNT_USER_ID="2a5c064c-94be-402e-905d-f3e36e66a039"

# 첫 인자가 숫자면 동시요청수로 본다: ./script 10  /  ./script <userId> 10  둘 다 허용
if [[ "${1:-}" =~ ^[0-9]+$ ]]; then
    REQUEST_COUNT="$1"
    ACCOUNT_USER_ID="${2:-$DEFAULT_ACCOUNT_USER_ID}"
else
    ACCOUNT_USER_ID="${1:-$DEFAULT_ACCOUNT_USER_ID}"
    REQUEST_COUNT="${2:-20}"
fi

# 스크립트 위치 기준으로 레포 루트와 .env 경로 계산
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$REPO_ROOT/.env"
# 내부 API 호출용 시크릿을 .env에서 읽음 (.env가 CRLF면 \r이 헤더에 섞여 톰캣이 거부하므로 제거)
INTERNAL_SECRET="$(grep '^INTERNAL_API_SECRET=' "$ENV_FILE" | cut -d= -f2- | tr -d '\r')"

# gateway를 거치지 않고 user-service 내부 토큰 API로 직접 호출
USER_SERVICE_URL="http://localhost:8081/internal/v1/accounts/${ACCOUNT_USER_ID}/token"

# 시크릿이 없으면 전부 인증 실패라 측정 의미가 없으므로 중단
if [ -z "$INTERNAL_SECRET" ]; then
    echo "INTERNAL_API_SECRET을 .env에서 찾지 못했습니다." >&2
    exit 1
fi

# 측정 조건을 실제 컨테이너 상태에서 읽어 출력 (스크린샷만으로 수정 전/후, Redis 장애 여부를 알 수 있게)
USER_SERVICE_IMAGE="$(docker inspect user-service --format '{{.Config.Image}}' 2>/dev/null || echo unknown)"
REDIS_STATE="$(docker inspect sajo-redis --format '{{.State.Status}}' 2>/dev/null || echo unknown)"
echo "[조건] $(date '+%Y-%m-%d %H:%M:%S') / user-service=${USER_SERVICE_IMAGE} / Redis=${REDIS_STATE}"
echo "accountUserId=${ACCOUNT_USER_ID} 동시요청 ${REQUEST_COUNT}건 발사..."

# 같은 URL을 N번 나열 (요청마다 응답 본문은 버림)
URL_ARGS=()
for _ in $(seq 1 "$REQUEST_COUNT"); do
    URL_ARGS+=(-o /dev/null "$USER_SERVICE_URL")
done

# curl 프로세스 하나가 N건을 한꺼번에 전송 - 프로세스를 N개 띄우는 방식보다 시작 시각 편차가 작음
mapfile -t CODES < <(
    curl -s --parallel --parallel-immediate --parallel-max "$REQUEST_COUNT" \
        -X POST -H "X-Internal-Secret: ${INTERNAL_SECRET}" \
        -w "%{http_code}\n" \
        "${URL_ARGS[@]}"
)

# 응답코드 집계: 200이면 성공, 나머지는 실패로 출력
SUCCESS_COUNT=0
FAIL_COUNT=0
for code in "${CODES[@]}"; do
    if [ "$code" = "200" ]; then
        SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
    else
        FAIL_COUNT=$((FAIL_COUNT + 1))
        echo "  실패 응답: HTTP ${code}"
    fi
done

echo "----------------------------------------"
echo "동시 요청 수         : ${REQUEST_COUNT}"
echo "HTTP 200 성공        : ${SUCCESS_COUNT}"
echo "HTTP 실패            : ${FAIL_COUNT}"
echo "----------------------------------------"
