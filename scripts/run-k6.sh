#!/bin/bash
set -euo pipefail

# ── 설정 ──
SSH_KEY="$HOME/.ssh/coupon-rush.pem"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

APP_PRIVATE_IP=$(terraform -chdir="$PROJECT_ROOT/terraform" output -raw app_server_private_ip)
TEST_IP=$(terraform -chdir="$PROJECT_ROOT/terraform" output -raw test_server_public_ip)

RATE="${1:-500}"
TOTAL_QUANTITY="${2:-100}"

SSH_OPTS="-i $SSH_KEY -o StrictHostKeyChecking=no"

echo "=== 1. k6 설치 확인 ==="
ssh $SSH_OPTS "ec2-user@$TEST_IP" << 'REMOTE'
  if ! command -v k6 &> /dev/null; then
    echo "k6 설치 중..."
    curl -sL https://github.com/grafana/k6/releases/download/v0.56.0/k6-v0.56.0-linux-amd64.tar.gz | tar xz
    sudo mv k6-v0.56.0-linux-amd64/k6 /usr/local/bin/
    rm -rf k6-v0.56.0-linux-amd64
    echo "k6 설치 완료: $(k6 version)"
  else
    echo "k6 이미 설치됨: $(k6 version)"
  fi
REMOTE

echo "=== 2. k6 시나리오 업로드 ==="
scp -r $SSH_OPTS "$PROJECT_ROOT/k6" "ec2-user@$TEST_IP:~/"

echo "=== 3. k6 실행 (rate=$RATE, quantity=$TOTAL_QUANTITY) ==="
ssh $SSH_OPTS "ec2-user@$TEST_IP" \
  "cd ~/k6 && k6 run -e BASE_URL=http://$APP_PRIVATE_IP:8080 -e RATE=$RATE -e TOTAL_QUANTITY=$TOTAL_QUANTITY scenarios/spike.js"
