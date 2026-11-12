// k6 부하 시나리오. API 하나를 constant-vus로 DURATION 동안 반복 호출한다 (perf/run.sh가 실행).
//   API: order-create | order-get | order-list | inventory | erp-sync
//   VUS: 동시 가상 사용자 수, DURATION: 측정 시간, START_ID: erp-sync 대상 주문 시작 ID
//   USER_TOKEN, ADMIN_TOKEN: Keycloak Access Token (erp-sync만 ADMIN)
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://api-gateway:8080';
const API = __ENV.API;
const PRODUCTS = ['keyboard', 'mouse', 'monitor'];
const SEED_ORDERS = 100000; // perf/seed.sql

export const options = {
  scenarios: {
    load: { executor: 'constant-vus', vus: Number(__ENV.VUS || 1), duration: __ENV.DURATION || '30s' },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

const user = { headers: { Authorization: `Bearer ${__ENV.USER_TOKEN}`, 'Content-Type': 'application/json' } };
const admin = { headers: { Authorization: `Bearer ${__ENV.ADMIN_TOKEN}` } };
// URL에 ID가 들어가면 요청마다 다른 지표 시계열이 생겨 k6 메모리·CPU를 쓴다. name tag로 하나로 묶는다.
const named = (params, name) => Object.assign({}, params, { tags: { name } });
const pick = (values) => values[Math.floor(Math.random() * values.length)];
const randomInt = (max) => Math.floor(Math.random() * max);

const scenarios = {
  'order-create': {
    expected: 201,
    call: () => http.post(`${BASE_URL}/api/orders`, JSON.stringify({
      customerId: 'perf-customer',
      items: [{ productName: pick(PRODUCTS), unitPrice: 10000, quantity: 1 }],
    }), user),
  },
  'order-get': {
    expected: 200,
    call: () => http.get(`${BASE_URL}/api/orders/${1 + randomInt(SEED_ORDERS)}`, named(user, 'GET /api/orders/{id}')),
  },
  // 최근 주문 목록 (상태 필터 + 생성일 역순 + 페이징). 주문 1건당 상품 2개라 N+1 여부도 드러난다.
  'order-list': {
    expected: 200,
    call: () => http.get(`${BASE_URL}/api/orders?status=PAID&sort=createdAt,desc&size=20&page=${randomInt(10)}`, named(user, 'GET /api/orders?status')),
  },
  inventory: {
    expected: 200,
    call: () => http.get(`${BASE_URL}/api/inventory/${pick(PRODUCTS)}`, named(user, 'GET /api/inventory/{productName}')),
  },
  // 반복마다 아직 연계하지 않은 주문을 하나씩 사용한다 (이미 성공한 주문은 ERP를 호출하지 않으므로).
  'erp-sync': {
    expected: 201,
    call: () => http.post(
      `${BASE_URL}/api/integrations/orders/${Number(__ENV.START_ID || 50001) + exec.scenario.iterationInTest}/sync`,
      null, named(admin, 'POST /api/integrations/orders/{id}/sync')),
  },
};

const scenario = scenarios[API];
if (!scenario) {
  throw new Error(`unknown API: ${API}`);
}

export default function () {
  const res = scenario.call();
  check(res, { [`status ${scenario.expected}`]: (r) => r.status === scenario.expected });
}

export function handleSummary(data) {
  const duration = data.metrics.http_req_duration.values;
  const round = (value) => Math.round(value * 100) / 100;
  const result = {
    api: API,
    vus: Number(__ENV.VUS || 1),
    requests: data.metrics.http_reqs.values.count,
    rps: round(data.metrics.http_reqs.values.rate),
    errorRate: round(data.metrics.checks.values.fails / (data.metrics.checks.values.passes + data.metrics.checks.values.fails) * 100),
    avg: round(duration.avg),
    p50: round(duration.med),
    p95: round(duration['p(95)']),
    p99: round(duration['p(99)']),
    max: round(duration.max),
  };
  return __ENV.SUMMARY ? { [__ENV.SUMMARY]: JSON.stringify(result) + '\n' } : {};
}
