// OSIV 커넥션 풀 고갈 부하 테스트 (k6)
//
// 예수금 조회(KIS inquire-balance 1회 호출)를 일정 속도로 보내면서, KIS와 무관한 계좌 조회(/accounts/me, DB만 조회)를
// 같이 호출해 KIS 지연이 DB 커넥션 풀을 통해 다른 API로 전파되는지 본다.
// KIS 지연은 mock-kis-server 장애 주입으로 거는데, 그 처리와 측정 조건 기록은 run.sh가 담당한다 - 직접 실행보다 run.sh 사용 권장.
//
// 환경변수:
//   RATE        예수금 조회 초당 요청 수 (기본 40 - 팀 market 부하 테스트(#275) 개장 몰림 구간 도착률 약 42건/s 수준)
//   DURATION    부하 유지 시간 (기본 90s - Hikari 커넥션 대기 한도 30s를 넘겨 실패가 드러나는지까지 보기 위함)
//   PROBE_RATE  /accounts/me 초당 요청 수 (기본 1)
//   BASE_URL    user-service 주소 (기본 http://localhost:8081)

import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const RATE = Number(__ENV.RATE || 40);
const DURATION = __ENV.DURATION || '90s';
const PROBE_RATE = Number(__ENV.PROBE_RATE || 1);

// create-users.sh가 만든 userId 목록 (첫 줄은 헤더)
const userIds = new SharedArray('userIds', () =>
    open('./users.csv').split(/\r?\n/).slice(1).filter((line) => line.trim() !== '')
);

export const options = {
    scenarios: {
        // 응답이 느려져도 요청 속도를 유지하는 open model - 느려지면 덜 보내는 방식이면 풀 고갈이 가려진다
        deposit: {
            executor: 'constant-arrival-rate',
            exec: 'deposit',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            // KIS 3s 지연 + 커넥션 대기 30s까지 겹치면 동시에 매달린 요청이 1,000개를 넘을 수 있음
            preAllocatedVUs: 200,
            maxVUs: 2000,
        },
        probe: {
            executor: 'constant-arrival-rate',
            exec: 'probe',
            rate: PROBE_RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 20,
            maxVUs: 200,
        },
    },
    // 판정용이 아니라 시나리오별 지표를 요약에 따로 출력하기 위한 threshold (항상 통과)
    thresholds: {
        'http_req_duration{scenario:deposit}': ['max>=0'],
        'http_req_duration{scenario:probe}': ['max>=0'],
        'http_req_failed{scenario:deposit}': ['rate>=0'],
        'http_req_failed{scenario:probe}': ['rate>=0'],
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function headersFor(userId) {
    return { headers: { 'X-User-Id': userId, 'X-User-Role': 'USER' }, timeout: '60s' };
}

function randomUserId() {
    return userIds[Math.floor(Math.random() * userIds.length)];
}

export function deposit() {
    const res = http.get(`${BASE_URL}/api/v1/accounts/me/deposit`, headersFor(randomUserId()));
    check(res, { 'deposit 200': (r) => r.status === 200 });
}

export function probe() {
    const res = http.get(`${BASE_URL}/api/v1/accounts/me`, headersFor(randomUserId()));
    check(res, { 'accounts/me 200': (r) => r.status === 200 });
}
