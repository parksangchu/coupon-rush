import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { BASE_URL } from '../lib/config.js';

const RATE = parseInt(__ENV.RATE || '500');
const TOTAL_QUANTITY = parseInt(__ENV.TOTAL_QUANTITY || '10000');

const issuedDuration = new Trend('duration_issued');
const rejectedDuration = new Trend('duration_rejected');
const issuedCount = new Counter('count_issued');
const rejectedCount = new Counter('count_rejected');

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

  if (res.status === 200) {
    issuedDuration.add(res.timings.duration);
    issuedCount.add(1);
  } else if (res.status === 409) {
    rejectedDuration.add(res.timings.duration);
    rejectedCount.add(1);
  }
}

export function teardown(data) {
  const res = http.get(`${BASE_URL}/api/v1/coupons/${data.couponId}/status`);
  const status = res.json();
  console.log(`=== Result: total=${status.total}, issued=${status.issued}, remaining=${status.remaining} ===`);

  check(status, {
    'no over-issuance': (s) => s.issued <= s.total,
  });

  const verifyRes = http.get(`${BASE_URL}/api/v1/coupons/${data.couponId}/verify`);
  const verify = verifyRes.json();
  console.log(`=== Verify: strategyCount=${verify.strategyCount}, dbCount=${verify.dbCount}, duplicates=${verify.duplicateCount}, consistent=${verify.consistent} ===`);

  check(verify, {
    'strategy-db consistent': (v) => v.consistent === true,
    'no duplicates': (v) => v.duplicateCount === 0,
  });
}
