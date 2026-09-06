package com.test.shortlink.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.Optional;
import java.util.Collections;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.shortlink.entity.RedisKeys;
import com.test.shortlink.entity.Shortlink;
import com.test.shortlink.entity.View;
import com.test.shortlink.repository.ShortlinkRepository;
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
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ShortlinkRepository shortlinkRepository;
    @Autowired
    @Qualifier("asyncExecutor")
    private Executor asyncExecutor;
    @Value("${app.expire-seconds:15}")
    private int expireSeconds;
    @Value("${app.lock-timeout-seconds:10}")
    private int lockTimeoutSeconds;
    @Value("${app.retry-count:5}")
    private int retryCount;
    @Value("${app.retry-sleep-ms:50}")
    private int retrySleepMs;
    @Value("${app.default-expire-time:10000000000}")
    private long defaultExpireTime;
    @Value("${app.sentinel-view-count:-9999}")
    private String sentinelViewCount;
    @Value("${app.update-code-regex:^[a-zA-Z0-9]{4,16}$}")
    private String updateCodeRegex;
    @Value("${app.mq-view-queue:view.queue}")
    private String viewQueue;
    @Autowired
    ObjectMapper objectMapper;
    private static final Logger logger = LoggerFactory.getLogger(ShortlinkService.class);
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    @Transactional
    public String shorten(String url,long expireAfter,String updateCode) {
        // 这里可以实现短链接生成的逻辑，例如使用哈希算法或者随机字符串
        if(!Util.isValidUrl(url)) {
            throw new IllegalArgumentException("Invalid URL");
        }
        if(updateCode.length()>0 && !updateCode.matches(updateCodeRegex)) {
            throw new IllegalArgumentException("Invalid update code format");
        }
        long idx;
        var createLockKey = RedisKeys.URL_CREATE_LOCK_KEY_PREFIX + url;
        if(!stringRedisTemplate.opsForValue().setIfAbsent(createLockKey, "1", java.time.Duration.ofSeconds(lockTimeoutSeconds))) {
            throw new IllegalArgumentException("Shortlink creation is too frequent for the same URL");
        }
        Shortlink shortlink = new Shortlink();
        idx=Util.generateLinkId();
        shortlink.setIdx(idx);
        shortlink.setOriginalUrl(url);
        shortlink.setCreatedAt(System.currentTimeMillis() / 1000);
        shortlink.setExpireAfter(expireAfter);
        shortlink.setUpdateCode(updateCode);
        try {
            shortlinkRepository.save(shortlink);
        }catch(Exception e) {
            throw e instanceof RuntimeException? (RuntimeException)e : new RuntimeException(e);
        }
        redirect(idx);
        return Util.idToStr(idx);
    }

    public CompletableFuture<Long> incrementViewCountAsync(long id,String ip, String userAgent) {
        return CompletableFuture.supplyAsync(() -> {
            //String cacheViewCountKey = RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + id;
            String cacheExpireKey = RedisKeys.URL_EXPIRE_KEY_PREFIX + id;
            long currentTime = System.currentTimeMillis()/1000;
            String res = stringRedisTemplate.opsForValue().get(cacheExpireKey);
            var cachedExpireTime = Long.parseLong(res!=null?res:currentTime+"");
            stringRedisTemplate.expire(id+"", java.time.Duration.ofSeconds(Long.min(cachedExpireTime-currentTime,expireSeconds)));
            //stringRedisTemplate.opsForValue().increment(cacheViewCountKey);
            View view = new View();
            view.setId(Util.generateLinkId());
            view.setIdx(id);
            view.setIp(ip);
            view.setTs(currentTime);
            view.setUserAgent(userAgent);
            try {
                stringRedisTemplate.opsForList().leftPush(RedisKeys.URL_VIEW_MQ, objectMapper.writeValueAsString(view));
            } catch (JsonProcessingException e) {
                e.printStackTrace();
                logger.error(e.getMessage());
            }
            //rabbitTemplate.convertAndSend(viewQueue, view);
            return 0L;
        }, asyncExecutor);
    }
    
    public String redirect(long id) {
        String cacheKey = RedisKeys.URL_KEY_PREFIX + id;
        String cacheViewCountKey = RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + id;
        String cacheExpireKey = RedisKeys.URL_EXPIRE_KEY_PREFIX + id;
        String dbLockKey = RedisKeys.URL_DB_LOCK_KEY_PREFIX + id;
        long currentTime = System.currentTimeMillis()/1000;
        long expireTime = defaultExpireTime;
        String lockToken = UUID.randomUUID().toString();
        boolean lockAcquired = false;
        try {
            for(int attempt = 0; attempt < retryCount; attempt++) {
                var cachedUrl = stringRedisTemplate.opsForValue().get(cacheKey);
                if(cachedUrl != null){
                    return cachedUrl;
                }
                if(stringRedisTemplate.opsForValue().setIfAbsent(dbLockKey, lockToken,
                        java.time.Duration.ofSeconds(lockTimeoutSeconds))) {
                    lockAcquired = true;
                    break;
                }
                if (attempt + 1 < retryCount) {
                    Thread.sleep(retrySleepMs);
                }
            }
            if(!lockAcquired) {
                throw new IllegalArgumentException("Shortlink is being accessed too frequently, please try again later");
            }
        
            Shortlink link = null;
            
            Optional<Shortlink> linkOptional = shortlinkRepository.findById(id);
            if(linkOptional.isPresent()) {
                link = linkOptional.get();
                if(link.getExpireAfter()>0) {
                    if(link.getCreatedAt()+link.getExpireAfter()<currentTime) {
                        throw new IllegalArgumentException("Shortlink has expired");
                    }
                    expireTime=link.getCreatedAt()+link.getExpireAfter();
                }
                String url = link.getOriginalUrl();
                stringRedisTemplate.opsForValue().set(cacheKey, url);
                stringRedisTemplate.expire(cacheKey, java.time.Duration.ofSeconds(Long.min(expireTime-currentTime,expireSeconds)));
                stringRedisTemplate.opsForValue().set(cacheViewCountKey, link.getViewCount()+"");
                stringRedisTemplate.opsForValue().set(cacheExpireKey, expireTime+"");
                return url;
            }else {
                stringRedisTemplate.expire(cacheKey, java.time.Duration.ofSeconds(expireSeconds));
                stringRedisTemplate.opsForValue().set(cacheViewCountKey, sentinelViewCount);
                stringRedisTemplate.opsForValue().set(cacheExpireKey, currentTime+expireSeconds+"");
                throw new IllegalArgumentException("Shortlink not found");
            }
        
        }catch (IllegalArgumentException e) {
            throw e;
        }catch (Exception e) {
            throw e instanceof RuntimeException? (RuntimeException)e : new RuntimeException(e);
        }finally {
            if (lockAcquired) {
                stringRedisTemplate.execute(RELEASE_LOCK_SCRIPT,
                        Collections.singletonList(dbLockKey), lockToken);
            }
        }
    }
    public String getInfo(String idu) {
        if(idu==null || idu.isEmpty()) {
            throw new IllegalArgumentException("Shortlink ID cannot be empty");
        }
        long id = Util.strToId(idu);
        redirect(id);
        
        try{
            Shortlink sl= shortlinkRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Shortlink not found"));
            return sl.toString();
        }catch(Exception e) {
            throw new IllegalArgumentException("Shortlink not found");
        }
    }

    public String update(String idu, String url, long expireAfter, String updateCode) {
        long id = Util.strToId(idu);
        Shortlink sl= shortlinkRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Shortlink not found"));
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
        shortlinkRepository.saveAndFlush(sl);
        stringRedisTemplate.expire(RedisKeys.URL_KEY_PREFIX + id, java.time.Duration.ofSeconds(0));
        
        return "Shortlink updated successfully";
    }

    public String delete(String idu, String updateCode) {
        updateCode = updateCode.trim();
        long id = Util.strToId(idu);
        try(var conn = dataSource.getConnection()) {
            Shortlink sl = shortlinkRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Shortlink not found"));
            /*var stmt = conn.prepareStatement("SELECT updateCode FROM urls WHERE idx = ?");
            stmt.setLong(1, id);
            var rs = stmt.executeQuery();*/
            String realUpdateCode = sl.getUpdateCode();
            logger.info("Real update code: {}, provided update code: {}", realUpdateCode, updateCode);
            if(realUpdateCode==null || !realUpdateCode.equals(updateCode)|| realUpdateCode.isEmpty()) {
                throw new IllegalArgumentException("Invalid update code");
            }
            shortlinkRepository.deleteById(id);
            stringRedisTemplate.delete(RedisKeys.URL_KEY_PREFIX + id);
            stringRedisTemplate.delete(RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + id);
            stringRedisTemplate.delete(RedisKeys.URL_EXPIRE_KEY_PREFIX + id);
            return "Shortlink deleted successfully";
        }catch (IllegalArgumentException e) {
            throw e;

        } catch (Exception e) {
            throw e instanceof RuntimeException? (RuntimeException)e : new RuntimeException(e);
        }
    }
}

