#!/bin/bash
set -euo pipefail

# ── 설정 ──
SSH_KEY="$HOME/.ssh/coupon-rush.pem"
JAR_NAME="coupon-rush-0.0.1-SNAPSHOT.jar"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# terraform output에서 IP/endpoint 읽기
APP_IP=$(terraform -chdir="$PROJECT_ROOT/terraform" output -raw app_server_public_ip)
RDS_ENDPOINT=$(terraform -chdir="$PROJECT_ROOT/terraform" output -raw rds_endpoint)

STRATEGY="${1:-pessimistic}"
DB_PASSWORD="${DB_PASSWORD:-CouponRush2026!}"

# Redis/Kafka endpoint 읽기
REDIS_OPTS=""
KAFKA_OPTS=""
if [[ "$STRATEGY" == "redis-lock" ]] || [[ "$STRATEGY" == "redis-counter" ]] || [[ "$STRATEGY" == "kafka" ]] || [[ "$STRATEGY" == "redis-streams" ]]; then
  REDIS_ENDPOINT=$(terraform -chdir="$PROJECT_ROOT/terraform" output -raw redis_endpoint)
  REDIS_OPTS="--spring.data.redis.host=${REDIS_ENDPOINT} --spring.data.redis.port=6379"
  echo "Redis endpoint: $REDIS_ENDPOINT"
fi
if [[ "$STRATEGY" == "kafka" ]]; then
  KAFKA_IP=$(terraform -chdir="$PROJECT_ROOT/terraform" output -raw kafka_private_ip)
  KAFKA_OPTS="--coupon.kafka.bootstrap-servers=${KAFKA_IP}:9092"
  echo "Kafka endpoint: $KAFKA_IP:9092"
fi

SSH_OPTS="-i $SSH_KEY -o StrictHostKeyChecking=no"

echo "=== 1. 빌드 ==="
cd "$PROJECT_ROOT"
./gradlew build -x test -q

echo "=== 2. jar 업로드 (app: $APP_IP) ==="
scp $SSH_OPTS "build/libs/$JAR_NAME" "ec2-user@$APP_IP:~/app.jar"

echo "=== 3. 기존 프로세스 종료 + 앱 실행 (strategy: $STRATEGY) ==="
ssh $SSH_OPTS "ec2-user@$APP_IP" << REMOTE
  # 기존 java 프로세스 종료 (sudo로 실행된 프로세스 대응)
  sudo pkill -f 'app.jar' || true
  sleep 2

  nohup sudo java -jar ~/app.jar \
    --spring.datasource.url='jdbc:mysql://${RDS_ENDPOINT}/coupon_rush?useSSL=false&allowPublicKeyRetrieval=true' \
    --spring.datasource.username=coupon \
    --spring.datasource.password='${DB_PASSWORD}' \
    --coupon.strategy=${STRATEGY} \
    ${REDIS_OPTS} \
    ${KAFKA_OPTS} \
    > ~/app.log 2>&1 &

  # 기동 대기
  echo "앱 기동 대기 중..."
  for i in {1..30}; do
    if curl -s http://localhost:9090/actuator/health | grep -q UP; then
      echo "앱 기동 완료!"
      exit 0
    fi
    sleep 2
  done
  echo "앱 기동 실패. 로그 확인: ssh ec2-user@$APP_IP 'tail -50 ~/app.log'"
  exit 1
REMOTE

echo "=== 완료 ==="
