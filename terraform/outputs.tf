output "app_server_public_ip" {
  description = "앱 서버 퍼블릭 IP"
  value       = aws_instance.app.public_ip
}

output "app_server_private_ip" {
  description = "앱 서버 프라이빗 IP (Prometheus 스크랩용)"
  value       = aws_instance.app.private_ip
}

output "test_server_public_ip" {
  description = "테스트 서버 퍼블릭 IP (k6 + 모니터링)"
  value       = aws_instance.test.public_ip
}

output "rds_endpoint" {
  description = "RDS MySQL 엔드포인트"
  value       = aws_db_instance.mysql.endpoint
}

output "redis_endpoint" {
  description = "ElastiCache Redis 엔드포인트"
  value       = aws_elasticache_cluster.redis.cache_nodes[0].address
}

output "kafka_private_ip" {
  description = "Kafka EC2 프라이빗 IP"
  value       = aws_instance.kafka.private_ip
}

output "kafka_public_ip" {
  description = "Kafka EC2 퍼블릭 IP (SSH용)"
  value       = aws_instance.kafka.public_ip
}
