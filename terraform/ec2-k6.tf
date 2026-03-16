resource "aws_instance" "k6" {
  ami                    = data.aws_ami.amazon_linux.id
  instance_type          = "t3.micro"
  key_name               = var.key_pair_name
  subnet_id              = aws_subnet.public_a.id
  vpc_security_group_ids = [aws_security_group.k6.id]

  root_block_device {
    volume_size = 10
    volume_type = "gp3"
  }

  user_data = <<-EOF
    #!/bin/bash
    dnf install -y https://dl.k6.io/rpm/repo.rpm || true
    cat <<'REPO' > /etc/yum.repos.d/k6.repo
    [k6]
    name=k6
    baseurl=https://dl.k6.io/rpm
    enabled=1
    gpgcheck=0
    REPO
    dnf install -y k6
  EOF

  tags = {
    Name    = "${var.project_name}-k6"
    Project = var.project_name
  }
}
