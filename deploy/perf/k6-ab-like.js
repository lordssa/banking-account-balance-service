import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

/**
 * Apache Bench–style load: fixed concurrency + fixed total request count + URL.
 *
 * Required:
 *   URL           full request URL (e.g. http://localhost:8080/balances/<uuid>)
 *
 * Optional (ab-like):
 *   C / VUS       concurrency (default 1) — same idea as ab -c
 *   N / REQUESTS  total requests across all VUs (default 1) — same idea as ab -n
 *   METHOD        HTTP method (default GET)
 *   BODY          request body (for POST/PUT/PATCH)
 *   HEADER_*      extra headers, e.g. HEADER_Authorization="Bearer …"
 *   EXPECT_STATUS expected HTTP status (default 200); set 0 to skip status check
 *   MAX_DURATION  cap for the run (default 10m)
 *
 * Examples (Git Bash):
 *   URL='http://localhost:8080/balances/<uuid>' C=10 N=20 k6 run deploy/perf/k6-ab-like.js
 *   ./deploy/perf/k6-ab.sh -c 10 -n 20 'http://localhost:8080/internal/journal/accounts/<uuid>'
 */

const url = String(__ENV.URL || '').trim();
if (!url) {
  fail('URL is required (full URL to hit)');
}

const concurrency = Math.max(1, Number(__ENV.C || __ENV.VUS || 1));
const totalRequests = Math.max(1, Number(__ENV.N || __ENV.REQUESTS || 1));
const method = String(__ENV.METHOD || 'GET').toUpperCase();
const body = __ENV.BODY !== undefined && __ENV.BODY !== '' ? __ENV.BODY : null;
const expectStatus = Number(__ENV.EXPECT_STATUS !== undefined ? __ENV.EXPECT_STATUS : 200);
const maxDuration = __ENV.MAX_DURATION || '10m';

const statusOk = new Rate('ab_status_ok');
const http200 = new Counter('ab_http_2xx');
const httpOther = new Counter('ab_http_other');
const latency = new Trend('ab_latency_ms', true);

function extraHeaders() {
  const headers = { 'User-Agent': 'k6-ab-like/1.0' };
  for (const [key, value] of Object.entries(__ENV)) {
    if (key.startsWith('HEADER_') && value !== undefined) {
      headers[key.slice('HEADER_'.length)] = value;
    }
  }
  if (body != null && !headers['Content-Type'] && !headers['content-type']) {
    headers['Content-Type'] = 'application/json';
  }
  return headers;
}

export const options = {
  scenarios: {
    ab_like: {
      executor: 'shared-iterations',
      vus: concurrency,
      iterations: totalRequests,
      maxDuration,
    },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
  },
};

export function setup() {
  console.log(
    `ab-like: method=${method} c=${concurrency} n=${totalRequests} url=${url}` +
      (expectStatus > 0 ? ` expect=${expectStatus}` : ''),
  );
}

export default function () {
  const params = {
    headers: extraHeaders(),
    tags: { name: 'ab-like' },
  };

  const res =
    body != null && method !== 'GET' && method !== 'HEAD'
      ? http.request(method, url, body, params)
      : http.request(method, url, null, params);

  latency.add(res.timings.duration);

  const code = res.status;
  if (code >= 200 && code < 300) {
    http200.add(1);
  } else {
    httpOther.add(1);
  }

  const ok =
    expectStatus <= 0
      ? code > 0
      : check(res, {
          [`status is ${expectStatus}`]: (r) => r.status === expectStatus,
        });

  statusOk.add(ok);
}
