package com.test.shortlink;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

import com.test.shortlink.conf.DBConf;
import com.test.shortlink.conf.ExecutorConf;
import com.test.shortlink.conf.MQServiceConf;
import com.test.shortlink.conf.RedisConf;
import com.test.shortlink.service.MQService;
import com.test.shortlink.util.Util;

import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;


@SpringBootApplication
@Import({ Util.class, DBConf.class, RedisConf.class , ExecutorConf.class, MQServiceConf.class})
public class ShortlinkApplication {
	@Autowired 
	DataSource dataSource;
	public static void main(String[] args) {
		SpringApplication.run(ShortlinkApplication.class, args);
	}

	@PostConstruct
	@Profile("dev")
	public void init() {
		try(var conn = dataSource.getConnection()) {
			var stmt = conn.createStatement();
			stmt.execute("CREATE TABLE IF NOT EXISTS urls (" + //
								"    idx BIGINT NOT NULL," + //
								"    originalUrl VARCHAR(255) NOT NULL," + //
								"    viewCount BIGINT DEFAULT 0 NOT NULL ," + //
								"    createdAt BIGINT DEFAULT 0 NOT NULL," + //
								"    expireAfter BIGINT DEFAULT -1 NOT NULL," + //
								"    updateCode CHAR(16)," + //
								"    PRIMARY KEY (idx)" + //
								");");
			stmt.execute("CREATE TABLE IF NOT EXISTS views (" + //
								"    id BIGINT NOT NULL AUTO_INCREMENT," + //
								"    idx BIGINT NOT NULL," + //
								"    ip VARCHAR(255) NULL," + //
								"    userAgent VARCHAR(255) NULL," + //
								"    ts BIGINT NULL," + //
								"    PRIMARY KEY (id)" + //
								");");
			// add example data
			stmt.execute("INSERT INTO urls (idx, originalUrl, viewCount, createdAt, expireAfter) VALUES (1, 'http://example.com', 0, UNIX_TIMESTAMP(), 100);");
			stmt.execute("INSERT INTO urls (idx, originalUrl, viewCount, createdAt, expireAfter) VALUES (2, 'http://test.com', 0, UNIX_TIMESTAMP(), 1000000);");
		} catch (Exception e) {
			//throw new RuntimeException(e);
		}
	}

}
