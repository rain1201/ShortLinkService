package com.test.shortlink;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Disabled("Requires Redis and RabbitMQ to be running")
class ShortlinkApplicationTests {

	@Test
	void contextLoads() {
	}

}
