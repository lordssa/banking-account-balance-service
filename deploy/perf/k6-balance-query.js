import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 50,
  duration: '2m',
  thresholds: {
    http_req_duration: ['p(95)<100', 'p(99)<250'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ACCOUNT = __ENV.ACCOUNT_ID || '00000000-0000-0000-0000-000000000001';

export default function () {
  const res = http.get(`${BASE}/balances/${ACCOUNT}`);
  check(res, {
    'status is 200 or 404': (r) => r.status === 200 || r.status === 404,
  });
  sleep(0.05);
}
