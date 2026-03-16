# 0006 - k6 EC2에 모니터링(Prometheus + Grafana) 통합 배치

| 항목 | 내용 |
|------|------|
| 상태 | accepted |
| 날짜 | 2026-03-16 |
| 분류 | infra |
| 모듈 | terraform, monitoring |
| 기술 | EC2, Prometheus, Grafana, Docker Compose, k6 |

## 배경 (Context)

k6 부하 생성기의 위치(로컬 vs EC2)와 모니터링(Prometheus + Grafana) 배치를 함께 검토했다.

로컬(M2, 16GB)에서 k6 + Prometheus + Grafana + IDE + 브라우저를 동시에 돌리면 메모리 5~7GB를 소모하며, 고RPS 시나리오에서 로컬 리소스가 병목이 될 수 있다. 또한 인터넷을 경유하면 네트워크 jitter가 부하 테스트 결과에 노이즈를 추가한다.

## 검토한 선택지 (Options Considered)

### A. 로컬에서 k6 + 모니터링 실행
- 장점: 별도 인프라 불필요
- 단점: 메모리 부담(5~7GB), 인터넷 jitter, 고RPS에서 로컬이 병목

### B. 앱 서버에 모니터링 통합
- 장점: 서버 1대로 운영
- 단점: 모니터링(Prometheus scrape, Grafana 렌더링)이 앱 서버 성능에 영향 → 측정 결과 왜곡

### C. k6 EC2에 모니터링 통합 (채택)
- 장점: 로컬은 브라우저만 → 부담 최소화, VPC 내부 통신(<1ms)으로 k6→app·Prometheus→app 모두 안정적
- 단점: t3.small 업그레이드 필요 ($0.026/hr, 여전히 저렴)

## 결정 (Decision)

k6와 모니터링(Prometheus + Grafana)을 같은 EC2 인스턴스에 배치한다. 인스턴스를 t3.small(2GB)로 업그레이드하고 디스크를 20GB로 확장한다. 역할 확장을 반영하여 리소스 이름을 `k6` → `test`로 변경한다.

## 구현 요약 (Implementation Summary)

- `terraform/ec2-k6.tf`: `aws_instance.k6` → `aws_instance.test`, t3.small, 디스크 20GB, user_data에 Docker + Docker Compose 설치 추가
- `terraform/ec2-app.tf`: t3.micro → t3.small (JVM + OS + 커넥션 풀 OOM 방지)
- `terraform/vpc.tf`: `aws_security_group.k6` → `aws_security_group.test`, Grafana(3000) 인바운드 추가
- `terraform/outputs.tf`: `k6_server_public_ip` → `test_server_public_ip`, `app_server_private_ip` 추가
- `monitoring/prometheus-aws.yml`: AWS용 Prometheus 설정 신규 생성 (app private IP 스크랩)

## 결과 (Consequences)

- 로컬 머신은 Grafana 대시보드 접속(브라우저)만 담당하여 리소스 부담 최소화
- VPC 내부 통신으로 네트워크 jitter 제거 → 부하 테스트 결과 신뢰도 향상
- terraform apply마다 app 서버 IP가 바뀔 수 있으므로, prometheus-aws.yml의 타겟 IP를 수동 교체하거나 sed로 치환 필요
- 월 비용 증가: t3.micro 2대 → t3.small 2대 (약 +$19/월, 테스트 시에만 켜두면 무시 가능)

## 성과 및 지표 (Impact & Metrics)

- 부하 테스트 시 로컬 메모리 사용량: ~5GB → 브라우저만(~1GB)
- k6→app 네트워크 레이턴시: 인터넷 경유(10~50ms jitter) → VPC 내부(<1ms)
- Prometheus 스크랩 안정성: VPC 내부로 패킷 손실 없음

## 관련 문서 (References)

- [0005 - 부하 테스트를 점진적 증가 방식으로 수행](0005-load-test-target-rps.md)
- `monitoring/prometheus-aws.yml` (AWS용 Prometheus 설정)
- `monitoring/prometheus.yml` (로컬용 Prometheus 설정)
