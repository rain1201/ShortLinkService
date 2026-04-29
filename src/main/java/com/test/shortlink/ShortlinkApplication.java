package com.test.shortlink;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.test.shortlink.service.DBConf;
import com.test.shortlink.service.RedisConf;

import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;


@SpringBootApplication
@Import({ RedisConf.class,DBConf.class })
public class ShortlinkApplication {
	@Autowired 
	DataSource dataSource;
	public static void main(String[] args) {
		SpringApplication.run(ShortlinkApplication.class, args);
	}

	@PostConstruct
	public void init() {
		try(var conn = dataSource.getConnection()) {
			var stmt = conn.createStatement();
			stmt.execute("CREATE TABLE IF NOT EXISTS urls (" + //
								"    idx CHAR(16) NOT NULL," + //
								"    originalUrl VARCHAR(255) NOT NULL," + //
								"    viewCount BIGINT DEFAULT 0 NOT NULL ," + //
								"    createdAt BIGINT DEFAULT 0 NOT NULL," + //
								"    expireAfter BIGINT DEFAULT -1 NOT NULL," + //
								"    updateCode CHAR(16)," + //
								"    PRIMARY KEY (idx)" + //
								");");
			// add example data
			stmt.execute("INSERT INTO urls (idx, originalUrl, viewCount, createdAt, expireAfter) VALUES ('example', 'http://example.com', 0, UNIX_TIMESTAMP(), 100);");
			stmt.execute("INSERT INTO urls (idx, originalUrl, viewCount, createdAt, expireAfter) VALUES ('test', 'http://test.com', 0, UNIX_TIMESTAMP(), 1000000);");
		} catch (Exception e) {
			//throw new RuntimeException(e);
		}
	}

}
