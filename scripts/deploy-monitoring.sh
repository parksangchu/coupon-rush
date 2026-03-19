#!/bin/bash
set -euo pipefail

# ── 설정 ──
SSH_KEY="$HOME/.ssh/coupon-rush.pem"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# terraform output에서 IP 읽기
APP_PRIVATE_IP=$(terraform -chdir="$PROJECT_ROOT/terraform" output -raw app_server_private_ip)
TEST_IP=$(terraform -chdir="$PROJECT_ROOT/terraform" output -raw test_server_public_ip)

SSH_OPTS="-i $SSH_KEY -o StrictHostKeyChecking=no"

echo "=== 1. monitoring 파일 업로드 (test: $TEST_IP) ==="
scp -r $SSH_OPTS "$PROJECT_ROOT/monitoring" "ec2-user@$TEST_IP:~/"
scp $SSH_OPTS "$PROJECT_ROOT/docker-compose.yml" "ec2-user@$TEST_IP:~/"

echo "=== 2. prometheus 설정에 app IP 치환 ($APP_PRIVATE_IP) ==="
ssh $SSH_OPTS "ec2-user@$TEST_IP" << REMOTE
  # prometheus-aws.yml에서 실제 IP로 치환 → prometheus.yml로 복사
  sed "s/APP_PRIVATE_IP/$APP_PRIVATE_IP/g" ~/monitoring/prometheus-aws.yml > ~/monitoring/prometheus.yml

  echo "=== 3. Docker Compose 실행 ==="
  cd ~
  docker compose --profile monitoring up -d

  echo "=== 컨테이너 상태 ==="
  docker ps
REMOTE

echo "=== 완료 ==="
echo "Grafana: http://$TEST_IP:13000 (admin/admin)"
echo "Prometheus: http://$TEST_IP:19090"
