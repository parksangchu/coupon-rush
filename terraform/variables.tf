variable "aws_region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "project_name" {
  description = "프로젝트 이름 (리소스 태깅용)"
  type        = string
  default     = "coupon-rush"
}

variable "db_username" {
  description = "RDS 마스터 유저명"
  type        = string
  default     = "coupon"
}

variable "db_password" {
  description = "RDS 마스터 비밀번호"
  type        = string
  sensitive   = true
}

variable "key_pair_name" {
  description = "EC2 SSH 키페어 이름"
  type        = string
}
