package com.couponrush;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
class CouponRushApplicationTests {

	@ServiceConnection
	static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

	static {
		mysql.start();
	}

	@Test
	void contextLoads() {
	}

}
