resource "aws_db_subnet_group" "main" {
  name       = "${var.project_name}-db-subnet"
  subnet_ids = [aws_subnet.public_a.id, aws_subnet.public_b.id]

  tags = {
    Name    = "${var.project_name}-db-subnet"
    Project = var.project_name
  }
}

resource "aws_db_instance" "mysql" {
  identifier     = "${var.project_name}-mysql"
  engine         = "mysql"
  engine_version = "8.4"
  instance_class = "db.m6g.large"

  allocated_storage = 20
  storage_type      = "gp3"

  db_name  = "coupon_rush"
  username = var.db_username
  password = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  publicly_accessible = false
  skip_final_snapshot  = true

  parameter_group_name = aws_db_parameter_group.mysql.name

  tags = {
    Name    = "${var.project_name}-mysql"
    Project = var.project_name
  }
}

resource "aws_db_parameter_group" "mysql" {
  name   = "${var.project_name}-mysql-params"
  family = "mysql8.4"

  parameter {
    name  = "character_set_server"
    value = "utf8mb4"
  }

  parameter {
    name  = "collation_server"
    value = "utf8mb4_unicode_ci"
  }

  parameter {
    name  = "time_zone"
    value = "Asia/Seoul"
  }

  tags = {
    Project = var.project_name
  }
}
