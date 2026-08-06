import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

/**
 * Balance query load against accounts that MUST already exist (HTTP 200).
 * Client http_req_duration is observational only — design-doc / SC-003 query SLO
 * is validated from server-side http.server.requests percentiles (see deploy/perf/README.md).
 *
 * Required env:
 *   ACCOUNT_IDS  comma-separated UUIDs that return 200 from GET /balances/{id}
 * Optional:
 *   BASE_URL     default http://localhost:8080
 *   VUS          default 50
 *   DURATION     default 2m
 */

const success200 = new Counter('balance_query_http_200');
const notFound404 = new Counter('balance_query_http_404');
const otherStatus = new Counter('balance_query_http_other');
const successRate = new Rate('balance_query_success');

const accounts = String(__ENV.ACCOUNT_IDS || '')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);

if (accounts.length === 0) {
  fail('ACCOUNT_IDS is required (comma-separated UUIDs that already have snapshots)');
}

export const options = {
  vus: Number(__ENV.VUS || 50),
  duration: __ENV.DURATION || '2m',
  thresholds: {
    checks: ['rate==1'],
    balance_query_success: ['rate==1'],
    http_req_failed: ['rate==0'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const accountId = accounts[Math.floor(Math.random() * accounts.length)];
  const res = http.get(`${BASE}/balances/${accountId}`, {
    tags: { name: 'GET /balances/{accountId}' },
  });

  if (res.status === 200) {
    success200.add(1);
  } else if (res.status === 404) {
    notFound404.add(1);
  } else {
    otherStatus.add(1);
  }

  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
    'body has balance.amount': (r) => {
      try {
        const body = r.json();
        return (
          body &&
          body.balance &&
          body.balance.amount !== undefined &&
          body.balance.amount !== null &&
          body.balance.currency
        );
      } catch (_) {
        return false;
      }
    },
  });
  successRate.add(ok);
  sleep(0.05);
}
