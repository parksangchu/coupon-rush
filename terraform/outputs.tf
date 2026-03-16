output "app_server_public_ip" {
  description = "앱 서버 퍼블릭 IP"
  value       = aws_instance.app.public_ip
}

output "k6_server_public_ip" {
  description = "k6 부하 생성기 퍼블릭 IP"
  value       = aws_instance.k6.public_ip
}

output "rds_endpoint" {
  description = "RDS MySQL 엔드포인트"
  value       = aws_db_instance.mysql.endpoint
}
