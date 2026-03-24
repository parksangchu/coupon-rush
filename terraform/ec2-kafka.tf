resource "aws_instance" "kafka" {
  ami                    = data.aws_ami.amazon_linux.id
  instance_type          = "t3.small"
  key_name               = var.key_pair_name
  subnet_id              = aws_subnet.public_a.id
  vpc_security_group_ids = [aws_security_group.kafka.id]

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
  }

  user_data = <<-EOF
    #!/bin/bash
    dnf install -y java-21-amazon-corretto-headless

    # Kafka 3.8.0 설치
    cd /opt
    curl -sL https://downloads.apache.org/kafka/3.8.0/kafka_2.13-3.8.0.tgz | tar xz
    ln -s kafka_2.13-3.8.0 kafka

    # KRaft 모드 설정 (ZooKeeper 불필요)
    KAFKA_HOME=/opt/kafka
    KAFKA_CLUSTER_ID=$($KAFKA_HOME/bin/kafka-storage.sh random-uuid)

    cat > $KAFKA_HOME/config/kraft/server.properties << 'PROPS'
    process.roles=broker,controller
    node.id=1
    controller.quorum.voters=1@localhost:9093
    listeners=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
    inter.broker.listener.name=PLAINTEXT
    controller.listener.names=CONTROLLER
    listener.security.protocol.map=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
    log.dirs=/var/kafka-logs
    num.partitions=3
    offsets.topic.replication.factor=1
    transaction.state.log.replication.factor=1
    transaction.state.log.min.isr=1
    PROPS

    # advertised.listeners는 프라이빗 IP 사용
    PRIVATE_IP=$(ec2-metadata --local-ipv4 | awk '{print $2}')
    echo "advertised.listeners=PLAINTEXT://$PRIVATE_IP:9092" >> $KAFKA_HOME/config/kraft/server.properties

    mkdir -p /var/kafka-logs
    $KAFKA_HOME/bin/kafka-storage.sh format -t $KAFKA_CLUSTER_ID -c $KAFKA_HOME/config/kraft/server.properties

    # systemd 서비스 등록
    cat > /etc/systemd/system/kafka.service << 'SVC'
    [Unit]
    Description=Apache Kafka
    After=network.target

    [Service]
    Type=simple
    User=root
    ExecStart=/opt/kafka/bin/kafka-server-start.sh /opt/kafka/config/kraft/server.properties
    ExecStop=/opt/kafka/bin/kafka-server-stop.sh
    Restart=on-failure

    [Install]
    WantedBy=multi-user.target
    SVC

    systemctl daemon-reload
    systemctl enable kafka
    systemctl start kafka
  EOF

  tags = {
    Name    = "${var.project_name}-kafka"
    Project = var.project_name
  }
}
