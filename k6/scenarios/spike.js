import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../lib/config.js';

const RATE = parseInt(__ENV.RATE || '500');
const TOTAL_QUANTITY = parseInt(__ENV.TOTAL_QUANTITY || '100');

export const options = {
  scenarios: {
    spike: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 100,
      maxVUs: 2000,
    },
  },
  thresholds: {
    'checks': ['rate==1'],
    'http_req_duration': ['p(99)<500'],
  },
};

export function setup() {
  const params = { headers: { 'Content-Type': 'application/json' } };
  const payload = JSON.stringify({
    code: `LOAD-TEST-${Date.now()}`,
    totalQuantity: TOTAL_QUANTITY,
  });

  const res = http.post(`${BASE_URL}/api/v1/coupons`, payload, params);

  check(res, {
    'coupon created': (r) => r.status === 201,
  });

  const coupon = res.json();
  console.log(`Created coupon: id=${coupon.id}, quantity=${TOTAL_QUANTITY}`);
  return { couponId: coupon.id };
}

export default function (data) {
  const userId = __VU * 1000000 + __ITER;
  const payload = JSON.stringify({ userId });
  const params = { headers: { 'Content-Type': 'application/json' } };

  const res = http.post(`${BASE_URL}/api/v1/coupons/${data.couponId}/issue`, payload, params);

  check(res, {
    'status is 200 or 409': (r) => r.status === 200 || r.status === 409,
  });
}

export function teardown(data) {
  const res = http.get(`${BASE_URL}/api/v1/coupons/${data.couponId}/status`);
  const status = res.json();
  console.log(`=== Result: total=${status.total}, issued=${status.issued}, remaining=${status.remaining} ===`);

  check(status, {
    'no over-issuance': (s) => s.issued <= s.total,
  });
}
