import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../lib/config.js';

const RATE = parseInt(__ENV.RATE || '5000');

export const options = {
  scenarios: {
    spike: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 200,
      maxVUs: 1000,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(99)<500'],
  },
};

const COUPON_ID = 1;

export default function () {
  const userId = `user-${__VU}-${__ITER}`;
  const payload = JSON.stringify({ userId });
  const params = { headers: { 'Content-Type': 'application/json' } };

  const res = http.post(`${BASE_URL}/api/v1/coupons/${COUPON_ID}/issue`, payload, params);

  check(res, {
    'status is 200 or 409': (r) => r.status === 200 || r.status === 409,
  });
}
