package com.test.shortlink.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.test.shortlink.entity.RedisKeys;
import com.test.shortlink.entity.Shortlink;
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
    private static final Logger logger = LoggerFactory.getLogger(ShortlinkService.class);
    @Transactional
    public String shorten(String url,long expireAfter,String updateCode) {
        // 这里可以实现短链接生成的逻辑，例如使用哈希算法或者随机字符串
        if(!Util.isValidUrl(url)) {
            throw new IllegalArgumentException("Invalid URL");
        }
        if(updateCode.length()>0 && !updateCode.matches("^[a-zA-Z0-9]{4,16}$")) {
            throw new IllegalArgumentException("Invalid update code format");
        }
        long idx;
        var createLockKey = RedisKeys.URL_CREATE_LOCK_KEY_PREFIX + url;
        if(!stringRedisTemplate.opsForValue().setIfAbsent(createLockKey, "1", java.time.Duration.ofSeconds(10))) {
            throw new IllegalArgumentException("Shortlink creation is too frequent for the same URL");
        }
        Shortlink shortlink = new Shortlink();
        idx=Util.generateLinkId();
        shortlink.setIdx(idx);
        shortlink.setOriginalUrl(url);
        shortlink.setExpireAfter(expireAfter);
        shortlink.setUpdateCode(updateCode);
        try {
            shortlinkRepository.save(shortlink);
        }catch(Exception e) {
            throw e instanceof RuntimeException? (RuntimeException)e : new RuntimeException(e);
        }
        redirect(idx,false);
        return Util.idToStr(idx);
    }

    public CompletableFuture<Long> incrementViewCountAsync(long id) {
        return CompletableFuture.supplyAsync(() -> {
            String cacheViewCountKey = RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + id;
            String cacheExpireKey = RedisKeys.URL_EXPIRE_KEY_PREFIX + id;
            long currentTime = System.currentTimeMillis()/1000;
            String res = stringRedisTemplate.opsForValue().get(cacheExpireKey);
            var cachedExpireTime = Long.parseLong(res!=null?res:currentTime+"");
            stringRedisTemplate.expire(id+"", java.time.Duration.ofSeconds(Long.min(cachedExpireTime-currentTime,expireSeconds)));
            stringRedisTemplate.opsForValue().increment(cacheViewCountKey);
            return 0L;
        }, asyncExecutor);
    }
    
    public String redirect(long id,boolean updateViewCount) {
        String cacheKey = RedisKeys.URL_KEY_PREFIX + id;
        String cacheViewCountKey = RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + id;
        String cacheExpireKey = RedisKeys.URL_EXPIRE_KEY_PREFIX + id;
        String dbLockKey = RedisKeys.URL_DB_LOCK_KEY_PREFIX + id;
        long currentTime = System.currentTimeMillis()/1000; 
        long expireTime = (long)1e10;
        byte updateViewCountByte = (byte)(updateViewCount?1:0);
        try {
            byte retryCount = 0;
            while(retryCount++<5) {
                var cachedUrl = stringRedisTemplate.opsForValue().get(cacheKey);
                if(cachedUrl != null){
                    incrementViewCountAsync(id);
                    return cachedUrl;
                }
                if(stringRedisTemplate.opsForValue().setIfAbsent(dbLockKey, "1", java.time.Duration.ofSeconds(10))) {
                    break;
                }else{
                    Thread.sleep(50);
                }
            }
            if(retryCount>=5) {
                throw new IllegalArgumentException("Shortlink is being accessed too frequently, please try again later");
            }
        
            Shortlink link = null;
            
            link = shortlinkRepository.findById(id).get();
            
            /*try{
                link= jdbcTemplate.queryForObject("SELECT * FROM urls WHERE idx = ?",
                                                    new BeanPropertyRowMapper<Shortlink>(Shortlink.class),id);
            }catch(Exception e) {
                // link保持为null
                throw e instanceof RuntimeException? (RuntimeException)e : new RuntimeException(e);
            }*/
            if(link!=null) {
                if(link.getExpireAfter()>0) {
                    if(link.getCreatedAt()+link.getExpireAfter()<currentTime) {
                        throw new IllegalArgumentException("Shortlink has expired");
                    }
                    expireTime=link.getCreatedAt()+link.getExpireAfter();
                }
                String url = link.getOriginalUrl();
                stringRedisTemplate.expire(cacheKey, java.time.Duration.ofSeconds(Long.min(expireTime-currentTime,expireSeconds)));
                stringRedisTemplate.opsForValue().set(cacheViewCountKey, link.getViewCount()+updateViewCountByte+"");
                stringRedisTemplate.opsForValue().set(cacheExpireKey, expireTime+"");
                return url;
            }else {
                stringRedisTemplate.expire(cacheKey, java.time.Duration.ofSeconds(expireSeconds));
                stringRedisTemplate.opsForValue().set(cacheViewCountKey, "-9999");
                stringRedisTemplate.opsForValue().set(cacheExpireKey, currentTime+expireSeconds+"");
                throw new IllegalArgumentException("Shortlink not found");
            }
        
        }catch (IllegalArgumentException e) {
            throw e;
        }catch (Exception e) {
            throw e instanceof RuntimeException? (RuntimeException)e : new RuntimeException(e);
        }finally {
            stringRedisTemplate.delete(dbLockKey);
        }
    }
    public String getInfo(String idu) {
        if(idu==null || idu.isEmpty()) {
            throw new IllegalArgumentException("Shortlink ID cannot be empty");
        }
        long id = Util.strToId(idu);
        redirect(id,false);
        
        try{
            Shortlink sl= shortlinkRepository.findById(id).get();
            return sl.toString();
        }catch(Exception e) {
            throw new IllegalArgumentException("Shortlink not found");
        }
    }

    public String update(String idu, String url, long expireAfter, String updateCode) {
        long id = Util.strToId(idu);
        Shortlink sl= shortlinkRepository.findById(id).get();
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
        shortlinkRepository.saveAndFlush(sl);
        stringRedisTemplate.expire(RedisKeys.URL_KEY_PREFIX + id, java.time.Duration.ofSeconds(0));
        
        return "Shortlink updated successfully";
    }

    public String delete(String idu, String updateCode) {
        updateCode = updateCode.trim();
        long id = Util.strToId(idu);
        try(var conn = dataSource.getConnection()) {
            Shortlink sl = shortlinkRepository.findById(id).get();
            /*var stmt = conn.prepareStatement("SELECT updateCode FROM urls WHERE idx = ?");
            stmt.setLong(1, id);
            var rs = stmt.executeQuery();*/
            if(sl!=null) {
                String realUpdateCode = sl.getUpdateCode();
                logger.info("Real update code: {}, provided update code: {}", realUpdateCode, updateCode);
                if(realUpdateCode==null || !realUpdateCode.equals(updateCode)|| realUpdateCode.isEmpty()) {
                    throw new IllegalArgumentException("Invalid update code");
                }
                /*stmt = conn.prepareStatement("DELETE FROM urls WHERE idx = ?");
                stmt.setLong(1, id);
                stmt.executeUpdate();*/
                shortlinkRepository.deleteById(id);
                stringRedisTemplate.delete(RedisKeys.URL_KEY_PREFIX + id);
                stringRedisTemplate.delete(RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + id);
                stringRedisTemplate.delete(RedisKeys.URL_EXPIRE_KEY_PREFIX + id);
                return "Shortlink deleted successfully";
            }else {
                throw new IllegalArgumentException("Shortlink not found");
            }
        }catch (IllegalArgumentException e) {
            throw e;

        } catch (Exception e) {
            throw e instanceof RuntimeException? (RuntimeException)e : new RuntimeException(e);
        }
    }
}

