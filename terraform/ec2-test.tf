resource "aws_instance" "test" {
  ami                    = data.aws_ami.amazon_linux.id
  instance_type          = "t3.small"
  key_name               = var.key_pair_name
  subnet_id              = aws_subnet.public_a.id
  vpc_security_group_ids = [aws_security_group.test.id]

  root_block_device {
    volume_size = 30
    volume_type = "gp3"
  }

  user_data = <<-EOF
    #!/bin/bash
    # k6 설치
    cat <<'REPO' > /etc/yum.repos.d/k6.repo
    [k6]
    name=k6
    baseurl=https://dl.k6.io/rpm
    enabled=1
    gpgcheck=0
    REPO
    dnf install -y k6

    # Docker 설치
    dnf install -y docker
    systemctl enable docker
    systemctl start docker
    usermod -aG docker ec2-user

    # Docker Compose 플러그인 설치
    mkdir -p /usr/local/lib/docker/cli-plugins
    curl -SL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-$(uname -m)" \
      -o /usr/local/lib/docker/cli-plugins/docker-compose
    chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
  EOF

  tags = {
    Name    = "${var.project_name}-test"
    Project = var.project_name
  }
}
