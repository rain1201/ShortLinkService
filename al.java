package com.test.shortlink.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.test.shortlink.service.ShortlinkService;
import com.test.shortlink.util.Util;


@RestController
public class ShortlinkController {
    @Autowired
    private ShortlinkService shortlinkService;
    @Value("${app.redirect-code:302}")
    private int redirectCode;
    private static final Logger logger = LoggerFactory.getLogger(ShortlinkController.class);
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception e) {
        return ResponseEntity.status(500).body("Internal Server Error: " + e.getMessage()+e.getStackTrace().toString());
    }
    @GetMapping("/index")
    public String index() {
        return "index";
    }
    @PostMapping("/shorten")
    public String shorten(@RequestParam("url") String url,
                        @RequestParam(value="expireAfter",defaultValue="1000000000") long expireAfter,
                        @RequestParam(value="updateCode",defaultValue="") String updateCode) {
        return shortlinkService.shorten(url,expireAfter,updateCode);
    }
    //@Operation(summary = "重定向", description = "返回注册页面的HTML视图")
    @GetMapping("/{id}")
    public ResponseEntity<String> redirect(@PathVariable String id) {
        // 这里可以实现根据短链接ID重定向到原始URL的逻辑
        ResponseEntity<String> response = ResponseEntity.status(redirectCode)
                                            .header("Location", shortlinkService.redirect(Util.strToId(id),true))
                                            .build();
        return response;
    }

    @GetMapping("/getInfo/{id}")
    public String getViewCount(@PathVariable String id) {
        return shortlinkService.getInfo(id)+"";
    }
    @PostMapping("/update/{id}")
    public String update(@PathVariable String id,@RequestParam(value = "url",defaultValue = "") String url,
                         @RequestParam(value = "expireAfter",defaultValue = "-1") long expireAfter,
                         @RequestParam("updateCode") String updateCode) {
        return shortlinkService.update(id, url, expireAfter, updateCode);
    }
    @PostMapping("/delete/{id}")
    public String delete(@PathVariable String id,@RequestParam("updateCode") String updateCode) {
        return shortlinkService.delete(id, updateCode);
    }
    @Profile("dev")
    @PostMapping("/calcUpdateCode/{id}")
    public String autoupdate(@PathVariable String id,@RequestParam(value = "url",defaultValue = "") String url,
                         @RequestParam(value = "expireAfter",defaultValue = "-1") long expireAfter,
                         @RequestParam("updateCode") String updateCode) {
        return Util.generateUpdateCode(updateCode,Util.strToId(id)+url+expireAfter);
    }

}
package com.test.shortlink.entity;

public class RedisKeys {
    public static final String URL_KEY_PREFIX = "shortlink:url:";
    public static final String URL_VIEW_COUNT_KEY_PREFIX = "shortlink:viewCount:";
    public static final String URL_EXPIRE_KEY_PREFIX = "shortlink:expire:"; 
}
package com.test.shortlink.entity;

public class Shortlink {
    long idx;
    String originalUrl;
    long viewCount;
    long createdAt;
    long expireAfter;  
    String updateCode; 
    public long getIdx() {
        return idx;
    }
    public void setIdx(long idx) {
        this.idx = idx;
    }
    public String getOriginalUrl() {
        return originalUrl;
    }
    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }
    public long getViewCount() {
        return viewCount;
    }
    public void setViewCount(long viewCount) {
        this.viewCount = viewCount;
    }
    public long getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
    public long getExpireAfter() {
        return expireAfter;
    }
    public void setExpireAfter(long expireAfter) {
        this.expireAfter = expireAfter;
    }
    public String getUpdateCode() {
        return updateCode;
    }
    public void setUpdateCode(String updateCode) {
        this.updateCode = updateCode;
    }
    @Override
    public String toString() {
        return "Shortlink [idx=" + idx + ", originalUrl=" + originalUrl + ", viewCount=" + viewCount + ", createdAt="
                + createdAt + ", expireAfter=" + expireAfter + "]";
    }
}
package com.test.shortlink.service;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@AutoConfiguration
@Component
public class DBConf {
    @Value("${spring.datasource.url}")
    private String url;
    @Value("${spring.datasource.username}")
    private String username;
    @Value("${spring.datasource.password}")
    private String password;
    @Bean
    public DataSource dSource() {
        HikariConfig config = new HikariConfig();   
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);  
        return new HikariDataSource(config); 
    }
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
package com.test.shortlink.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.lettuce.core.RedisClient;

@Component
public class RedisConf {
    @Value("${spring.redis.host}")
    String host;
    @Value("${spring.redis.port}")
    int port;
    @Value("${spring.redis.database}")
    int database;
    @Value("${spring.redis.password:}")
    String password;
    @Value("${spring.redis.ssl:false}")
    boolean ssl;
    final Logger log = LoggerFactory.getLogger(RedisConf.class);
    @Bean
    public RedisConnectionFactory redisConnectionFactory(){
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName(host);
        serverConfig.setPort(port);
        serverConfig.setDatabase(database);
        if(password != null && !password.isEmpty()) {     
            serverConfig.setPassword(password);
        }
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                                                .commandTimeout(Duration.ofSeconds(2))
                                                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(serverConfig, clientConfig);
        factory.afterPropertiesSet();
        return factory;
    }
    @Bean
    public RedisClient redisClient(RedisConnectionFactory redisConnectionFactory) {
        return (RedisClient)((LettuceConnectionFactory) redisConnectionFactory).getNativeClient();
    }
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory redisConnectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        return container;
    }
}
package com.test.shortlink.service;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import com.test.shortlink.entity.RedisKeys;

import io.lettuce.core.RedisClient;

import javax.sql.DataSource;

@Component
public class RedisExpireListener extends KeyExpirationEventMessageListener{
    RedisMessageListenerContainer redisMessageListenerContainer;
    final Logger logger = LoggerFactory.getLogger(RedisExpireListener.class);
    @Autowired
    DataSource dataSource;
    @Autowired
    RedisClient redisClient;
    public RedisExpireListener(RedisMessageListenerContainer listenerContainer) {
        super(listenerContainer);
        this.redisMessageListenerContainer = listenerContainer;
        logger.info("RedisExpireListener initialized and subscribed to key expiration events");
    }
    @Override
    public void doHandleMessage(Message message) {
        String expiredKey = new String(message.getBody());
        if(expiredKey.startsWith(RedisKeys.URL_KEY_PREFIX)) {
            String id = expiredKey.substring(RedisKeys.URL_KEY_PREFIX.length());
            if(id.isEmpty()) return;
            String viewCountKey = RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + id;
            String expireKey = RedisKeys.URL_EXPIRE_KEY_PREFIX + id;
            try(var redisConn = redisClient.connect()) {
                var redisCommands = redisConn.sync();
                String newView = redisCommands.getdel(viewCountKey);
                redisCommands.getdel(expireKey);
                logger.info("Updating view count for expired key: " + id + " with new view count: " + newView);
                try(var conn = dataSource.getConnection()) {
                    var stmt = conn.prepareStatement("UPDATE urls SET viewCount = ? WHERE idx = ? AND viewCount < ?");
                    stmt.setString(1, newView != null ? newView : "0");
                    stmt.setString(2, id);
                    stmt.setString(3, newView != null ? newView : "0");
                    stmt.executeUpdate();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}package com.test.shortlink.service;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.test.shortlink.entity.RedisKeys;
import com.test.shortlink.entity.Shortlink;
import com.test.shortlink.util.Util;

import io.lettuce.core.RedisClient;

@Service
public class ShortlinkService {
    @Autowired
    RedisClient redisClient;
    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Value("${app.expire-seconds:15}")
    private int expireSeconds;
    private static final Logger logger = LoggerFactory.getLogger(ShortlinkService.class);
    public String shorten(String url,long expireAfter,String updateCode) {
        // 这里可以实现短链接生成的逻辑，例如使用哈希算法或者随机字符串
        if(!Util.isValidUrl(url)) {
            throw new IllegalArgumentException("Invalid URL");
        }
        if(updateCode.length()>0 && !updateCode.matches("^[a-zA-Z0-9]{4,16}$")) {
            throw new IllegalArgumentException("Invalid update code format");
        }
        long idx;
        try(var conn = dataSource.getConnection()) {
            /*var stmt = conn.prepareStatement("SELECT idx FROM urls WHERE originalUrl = ?");
            stmt.setString(1, url);
            var rs = stmt.executeQuery();
            if(rs.next()) return rs.getString("idx");*/
            var stmt = conn.prepareStatement("INSERT INTO urls (idx, originalUrl, viewCount, createdAt, expireAfter, updateCode) VALUES (?, ?, 0, UNIX_TIMESTAMP(), ?, ?)");
            idx=Util.generateLinkId();
            stmt.setLong(1, idx);
            stmt.setString(2, url);
            stmt.setLong(3, expireAfter);
            stmt.setString(4, updateCode);
            stmt.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        redirect(idx,false);
        return Util.idToStr(idx);
    }
    
    public String redirect(long id,boolean updateViewCount) {
        String cacheKey = RedisKeys.URL_KEY_PREFIX + id;
        String cacheViewCountKey = RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + id;
        String cacheExpireKey = RedisKeys.URL_EXPIRE_KEY_PREFIX + id;
        long currentTime = System.currentTimeMillis()/1000; 
        long expireTime = (long)1e10;
        byte updateViewCountByte = (byte)(updateViewCount?1:0);
        try(var redisConn = redisClient.connect()) {
            var redisCommands = redisConn.async();
            var cachedUrl = redisCommands.get(cacheKey).get();
            if(cachedUrl != null){
                redisCommands.get(cacheExpireKey).thenAcceptAsync((res)->{
                    var cachedExpireTime = Long.parseLong(res!=null?res:currentTime+"");
                    redisCommands.expire(id+"", Long.min(cachedExpireTime-currentTime,expireSeconds));
                    if(updateViewCount)redisCommands.incr(cacheViewCountKey);
                });
                return cachedUrl;
            } 
            try(var conn = dataSource.getConnection()) {
                Shortlink link = jdbcTemplate.queryForObject("SELECT * FROM urls WHERE idx = ?",
                                                        new BeanPropertyRowMapper<Shortlink>(Shortlink.class),id);
                var redisCommandsAsync = redisConn.async();
                if(link!=null) {
                    if(link.getExpireAfter()>0) {
                        if(link.getCreatedAt()+link.getExpireAfter()<currentTime) {
                            throw new IllegalArgumentException("Shortlink has expired");
                        }
                        expireTime=link.getCreatedAt()+link.getExpireAfter();
                    }
                    String url = link.getOriginalUrl();
                    redisCommandsAsync.setex(cacheKey, Long.min(expireTime-currentTime,expireSeconds), url);
                    redisCommandsAsync.set(cacheViewCountKey, link.getViewCount()+updateViewCountByte+"");
                    redisCommandsAsync.set(cacheExpireKey, expireTime+"");
                    return url;
                }else {
                    redisCommandsAsync.setex(cacheKey, expireSeconds, "");
                    redisCommandsAsync.set(cacheViewCountKey, "-9999");
                    redisCommandsAsync.set(cacheExpireKey, currentTime+expireSeconds+"");
                    throw new IllegalArgumentException("Shortlink not found");
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public String getInfo(String idu) {
        if(idu==null || idu.isEmpty()) {
            throw new IllegalArgumentException("Shortlink ID cannot be empty");
        }
        long id = Util.strToId(idu);
        if(redirect(id,false)==null) {
            throw new IllegalArgumentException("Shortlink not found");
        }
        Shortlink sl=jdbcTemplate.queryForObject("SELECT * FROM urls WHERE idx = ?", 
                                                new BeanPropertyRowMapper<Shortlink>(Shortlink.class),id);
        if(sl==null) {
            throw new IllegalArgumentException("Shortlink not found");
        }
        return sl.toString();
    }

    public String update(String idu, String url, long expireAfter, String updateCode) {
        long id = Util.strToId(idu);
        Shortlink sl=jdbcTemplate.queryForObject("SELECT * FROM urls WHERE idx = ?", 
                                                new BeanPropertyRowMapper<Shortlink>(Shortlink.class),id);
        if(sl==null) {
            throw new IllegalArgumentException("Shortlink not found");
        }
        if(!Util.isValidUpdateCode(sl.getUpdateCode(), id+url+expireAfter, updateCode)) {
            throw new IllegalArgumentException("Invalid update code");
        }
        logger.info("Updating shortlink {} with url {} and expireAfter {}", id, url, expireAfter);
        if(!url.isEmpty() && !Util.isValidUrl(url)) {
            throw new IllegalArgumentException("Invalid URL");
        }
        if(!url.isEmpty()) {
            sl.setOriginalUrl(url);
        }
        if(expireAfter>0) {
            sl.setExpireAfter(expireAfter+System.currentTimeMillis()/1000-sl.getCreatedAt());
        }
        jdbcTemplate.update("UPDATE urls SET originalUrl=?, expireAfter=? WHERE idx=?", sl.getOriginalUrl(), sl.getExpireAfter(), id);
        try(var redisConn = redisClient.connect()) {
            var redisCommands = redisConn.async();
            redisCommands.expire(RedisKeys.URL_KEY_PREFIX + id,0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return "Shortlink updated successfully";
    }

    public String delete(String idu, String updateCode) {
        updateCode = updateCode.trim();
        long id = Util.strToId(idu);
        try(var conn = dataSource.getConnection()) {
            var stmt = conn.prepareStatement("SELECT updateCode FROM urls WHERE idx = ?");
            stmt.setLong(1, id);
            var rs = stmt.executeQuery();
            if(rs.next()) {
                String realUpdateCode = rs.getString("updateCode").trim();
                logger.info("Real update code: {}, provided update code: {}", realUpdateCode, updateCode);
                if(realUpdateCode==null || !realUpdateCode.equals(updateCode)|| realUpdateCode.isEmpty()) {
                    throw new IllegalArgumentException("Invalid update code");
                }
                stmt = conn.prepareStatement("DELETE FROM urls WHERE idx = ?");
                stmt.setLong(1, id);
                stmt.executeUpdate();
                try(var redisConn = redisClient.connect()) {
                    var redisCommands = redisConn.async();
                    redisCommands.del(RedisKeys.URL_KEY_PREFIX + id);
                    redisCommands.del(RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + id);
                    redisCommands.del(RedisKeys.URL_EXPIRE_KEY_PREFIX + id);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return "Shortlink deleted successfully";
            }else {
                throw new IllegalArgumentException("Shortlink not found");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

package com.test.shortlink;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

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
			// add example data
			stmt.execute("INSERT INTO urls (idx, originalUrl, viewCount, createdAt, expireAfter) VALUES (1, 'http://example.com', 0, UNIX_TIMESTAMP(), 100);");
			stmt.execute("INSERT INTO urls (idx, originalUrl, viewCount, createdAt, expireAfter) VALUES (2, 'http://test.com', 0, UNIX_TIMESTAMP(), 1000000);");
		} catch (Exception e) {
			//throw new RuntimeException(e);
		}
	}

}
package com.test.shortlink.util;

public class SnowFlakeId {
    private long workerId;
    private long datacenterId;
    private final long epoch = 1609459200000L; // 2021-01-01 00:00:00 UTC
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowFlakeId(long workerId, long datacenterId) {
        if(workerId < 0 || workerId > 31) {
            throw new IllegalArgumentException("Worker ID must be between 0 and 31");
        }
        if(datacenterId < 0 || datacenterId > 31) {
            throw new IllegalArgumentException("Datacenter ID must be between 0 and 31");
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("Clock moved backwards. Refusing to generate id");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & 4095; // 12 bits for sequence
            if (sequence == 0) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - epoch) << 22) | (datacenterId << 17) | (workerId << 12) | sequence;
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
} package com.test.shortlink.util;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.stereotype.Component;

@AutoConfiguration
@Component
public class Util {
    @Value("${app.check-code-length:8}")
    private static int checkCodeLength=8;
    @Value("${app.worker-id:0}")
    private static long workerId=0;
    @Value("${app.datacenter-id:0}")
    private static long datacenterId=0;
    private static final Logger logger=LoggerFactory.getLogger(Util.class.getName());
    private static SnowFlakeId idGenerator = new SnowFlakeId(workerId, datacenterId);
    public static long generateLinkId() {
        return idGenerator.nextId();
    }   
    /*public static String generateShortlink(String url) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        Long id = idGenerator.nextId();
        logger.info("Generated ID: {}", id);
        return encoder.encodeToString(longToBytes(id));
    }*/
    public static boolean isValidUrl(String url){
        String re="\\b(?:https?)://"+
                "(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,6}"+
                "|(?:\\d{1,3}\\.){3}\\d{1,3}"+
                "(?::\\d+)?"+
                "(?:/[^\\s]*)?\\b"; 
        return url.matches(re);
    }
    public static boolean isValidUpdateCode(String realCode,String updateString,String providedCode) {
        String expectedCode=generateUpdateCode(realCode, updateString);
        return providedCode.equals(expectedCode);
    }
    public static String generateUpdateCode(String realCode,String updateString) {
        try{
            MessageDigest md=MessageDigest.getInstance("SHA-1");
            md.reset();
            realCode=realCode.trim();
            updateString=updateString.trim();
            md.update((realCode+updateString).trim().getBytes("UTF-8"));
            String expectedCode= HexFormat.of().formatHex(md.digest());
            logger.info("Generated update code: {},[{}]", expectedCode,(realCode+updateString).trim());
            return expectedCode.substring(0, checkCodeLength);
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }
    public static byte[] longToBytes(long x) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (x & 0xFF);
            x >>= 8;
        }
        return bytes;
    }
    public static long bytesToLong(byte[] bytes) {
        long x = 0;
        for (int i = 0; i < 8; i++) {
            x <<= 8;
            x |= (bytes[i] & 0xFF);
        }
        return x;
    }
    public static String idToStr(long id) {
        Base64.Encoder encoder = Base64.getEncoder();
        return encoder.encodeToString(longToBytes(id));
    }
    public static long strToId(String str) {
        Base64.Decoder decoder = Base64.getDecoder();
        return bytesToLong(decoder.decode(str));
    }
}
package com.test.shortlink.service;

import org.junit.jupiter.api.Test;

public class ShortlinkServiceTest {
    @Test
    void testDelete() {

    }
}
package com.test.shortlink;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ShortlinkApplicationTests {

	@Test
	void contextLoads() {
	}

}
