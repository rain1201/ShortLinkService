package com.test.shortlink;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

import com.test.shortlink.conf.DBConf;
import com.test.shortlink.conf.ExecutorConf;
import com.test.shortlink.conf.JacksonConf;
import com.test.shortlink.conf.MQServiceConf;
import com.test.shortlink.conf.RedisConf;
import com.test.shortlink.util.Util;

import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;


@SpringBootApplication
@Import({ Util.class, DBConf.class, RedisConf.class , ExecutorConf.class, MQServiceConf.class,JacksonConf.class})
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
								"    idx BIGINT NOT NULL," + //
								"    original_url VARCHAR(255) NOT NULL," + //
								"    view_count BIGINT DEFAULT 0 NOT NULL ," + //
								"    created_at BIGINT DEFAULT 0 NOT NULL," + //
								"    expire_after BIGINT DEFAULT -1 NOT NULL," + //
								"    update_code CHAR(16)," + //
								"    PRIMARY KEY (idx)" + //
								");");
			stmt.execute("CREATE TABLE IF NOT EXISTS views (" + //
								"    id BIGINT NOT NULL AUTO_INCREMENT," + //
								"    idx BIGINT NOT NULL," + //
								"    ip VARCHAR(255) NULL," + //
								"    user_agent VARCHAR(255) NULL," + //
								"    ts BIGINT NULL," + //
								"    PRIMARY KEY (id)" + //
								");");
		} catch (Exception e) {
			//throw new RuntimeException(e);
		}
	}

}
