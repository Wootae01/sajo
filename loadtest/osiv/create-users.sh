#!/usr/bin/env bash
# OSIV 커넥션 풀 고갈 부하 테스트용 테스트 계정 생성 스크립트.
#
# 계정마다 회원가입 → mock 계좌 연동 → 예수금 1회 조회(KIS 토큰 캐시 워밍업)를 수행하고,
# 성공한 userId를 users.csv로 남긴다. k6 스크립트(deposit-osiv.js)가 이 파일을 읽어 사용자별로 요청한다.
#
# 반드시 mock KIS 환경에서만 실행한다 - 실제 KIS로 가짜 계좌 수백 개를 연동하면 검증/rate limit에 걸린다.
# (docker-compose.mock.yaml 오버레이로 띄우면 DB/Redis도 mock 전용 볼륨이라 평소 로컬 데이터와 섞이지 않음)
#
# 사용법:
#   bash loadtest/osiv/create-users.sh [계정수(기본 400)]
#
# 환경변수:
#   EMAIL_PREFIX     이메일 접두사 (기본 loadtest) - loadtest-0001@example.com 형태
#   CANO_PREFIX      계좌번호 앞 2자리 (기본 60) - 60000001-01 형태. 99로 시작하면 mock이 "없는 계좌"로 응답
#   PARALLEL         동시 생성 수 (기본 8)
#   USER_SERVICE_URL user-service 주소 (기본 http://localhost:8081)
#
# 같은 이메일/계좌번호로는 다시 만들 수 없으므로(409), 재생성하려면 EMAIL_PREFIX/CANO_PREFIX를 바꾸거나
# mock DB 볼륨(sajo_postgres-mock-data)을 초기화한다.

set -euo pipefail

COUNT="${1:-400}"
EMAIL_PREFIX="${EMAIL_PREFIX:-loadtest}"
CANO_PREFIX="${CANO_PREFIX:-60}"
PARALLEL="${PARALLEL:-8}"
USER_SERVICE_URL="${USER_SERVICE_URL:-http://localhost:8081}"
PASSWORD="loadtest1234"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_FILE="$SCRIPT_DIR/users.csv"

if [[ "$CANO_PREFIX" == 99 ]]; then
    echo "CANO_PREFIX가 99면 mock이 없는 계좌(OPSQ2000)로 응답합니다. 다른 값을 쓰세요." >&2
    exit 1
fi

# 실제 KIS에 연동되는 사고를 막기 위해 user-service가 mock을 바라보는지 먼저 확인
KIS_URL="$(docker inspect user-service --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
    | grep '^KIS_API_VIRTUAL_BASE_URL=' | cut -d= -f2- || true)"
if [[ "$KIS_URL" != *mock-kis-server* ]]; then
    echo "user-service가 mock KIS를 바라보고 있지 않습니다(KIS_API_VIRTUAL_BASE_URL=${KIS_URL:-없음})." >&2
    echo "docker-compose.mock.yaml 오버레이로 user-service를 띄운 뒤 다시 실행하세요." >&2
    exit 1
fi

if [[ -f "$OUT_FILE" ]]; then
    echo "$OUT_FILE 이 이미 있습니다. 덮어쓰지 않도록 중단합니다(기존 계정을 쓰거나 파일을 옮긴 뒤 다시 실행)." >&2
    exit 1
fi

# 계정 1개 생성 - 성공하면 stdout으로 userId 한 줄, 실패하면 stderr로 사유를 남기고 1 반환
create_one() {
    local i="$1"
    local seq email cano body http id

    seq="$(printf '%04d' "$i")"
    email="${EMAIL_PREFIX}-${seq}@example.com"
    cano="${CANO_PREFIX}$(printf '%06d' "$i")-01"

    body="$(curl -s -w $'\n%{http_code}' -X POST "$USER_SERVICE_URL/api/v1/users" \
        -H 'Content-Type: application/json' \
        -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\",\"name\":\"loadtest-$seq\"}")"
    http="${body##*$'\n'}"
    if [[ "$http" != 201 ]]; then
        echo "[$seq] 회원가입 실패 http=$http ${body%$'\n'*}" >&2
        return 1
    fi
    id="$(grep -oE '"id":"[0-9a-f-]{36}"' <<< "$body" | head -1 | cut -d'"' -f4)"

    body="$(curl -s -w $'\n%{http_code}' -X POST "$USER_SERVICE_URL/api/v1/accounts" \
        -H 'Content-Type: application/json' -H "X-User-Id: $id" -H 'X-User-Role: USER' \
        -d "{\"appKey\":\"mock-app-key-$seq\",\"secretKey\":\"mock-secret-$seq\",\"accountNo\":\"$cano\",\"accountType\":\"VIRTUAL\"}")"
    http="${body##*$'\n'}"
    if [[ "$http" != 201 ]]; then
        echo "[$seq] 계좌 연동 실패 userId=$id http=$http ${body%$'\n'*}" >&2
        return 1
    fi

    # 측정 중 첫 요청에 토큰 발급(KIS 호출 1회 추가)이 섞이지 않도록 미리 캐시를 채워둔다
    http="$(curl -s -o /dev/null -w '%{http_code}' "$USER_SERVICE_URL/api/v1/accounts/me/deposit" \
        -H "X-User-Id: $id" -H 'X-User-Role: USER')"
    if [[ "$http" != 200 ]]; then
        echo "[$seq] 예수금 워밍업 실패 userId=$id http=$http" >&2
        return 1
    fi

    echo "$id"
}
export -f create_one
export EMAIL_PREFIX CANO_PREFIX USER_SERVICE_URL PASSWORD

echo "계정 ${COUNT}개 생성 (동시 ${PARALLEL}, 이메일 ${EMAIL_PREFIX}-NNNN@example.com, 계좌 ${CANO_PREFIX}NNNNNN-01)"

TMP_FILE="$(mktemp)"
# 일부 실패해도 성공한 계정은 남기도록 xargs 실패 코드는 무시하고 건수로 판단
seq 1 "$COUNT" | xargs -P "$PARALLEL" -I{} bash -c 'create_one "$@"' _ {} > "$TMP_FILE" || true

CREATED="$(wc -l < "$TMP_FILE" | tr -d ' ')"
{ echo "userId"; cat "$TMP_FILE"; } > "$OUT_FILE"
rm -f "$TMP_FILE"

echo "----------------------------------------"
echo "생성 성공: ${CREATED} / ${COUNT}"
echo "결과 파일: $OUT_FILE"
echo "----------------------------------------"
if [[ "$CREATED" != "$COUNT" ]]; then
    exit 1
fi
