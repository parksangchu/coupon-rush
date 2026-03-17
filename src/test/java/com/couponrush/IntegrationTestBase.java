package com.couponrush;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
public abstract class IntegrationTestBase {

    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    static {
        mysql.start();
    }
}
