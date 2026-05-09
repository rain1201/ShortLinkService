package com.test.shortlink.service;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.test.shortlink.entity.RedisKeys;
import com.test.shortlink.entity.Shortlink;
import com.test.shortlink.util.Util;

import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;

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
        try(var redisConn=redisClient.connect()){
            var redisCommands = redisConn.sync();
            var createLockKey = RedisKeys.URL_CREATE_LOCK_KEY_PREFIX + url + expireAfter;
            if(!redisCommands.set(createLockKey, "1", SetArgs.Builder.nx().ex(10)).equals("OK")) {
                throw new IllegalArgumentException("Shortlink creation is too frequent for the same URL and expireAfter combination");
            }
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
        }
        redirect(idx,false);
        return Util.idToStr(idx);
    }
    
    public String redirect(long id,boolean updateViewCount) {
        String cacheKey = RedisKeys.URL_KEY_PREFIX + id;
        String cacheViewCountKey = RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + id;
        String cacheExpireKey = RedisKeys.URL_EXPIRE_KEY_PREFIX + id;
        String dbLockKey = RedisKeys.URL_DB_LOCK_KEY_PREFIX + id;
        long currentTime = System.currentTimeMillis()/1000; 
        long expireTime = (long)1e10;
        byte updateViewCountByte = (byte)(updateViewCount?1:0);
        try(var redisConn = redisClient.connect()) {
            var redisCommands = redisConn.async();
            while(true) {
                var cachedUrl = redisCommands.get(cacheKey).get();
                if(cachedUrl != null){
                    redisCommands.get(cacheExpireKey).thenAcceptAsync((res)->{
                        var cachedExpireTime = Long.parseLong(res!=null?res:currentTime+"");
                        redisCommands.expire(id+"", Long.min(cachedExpireTime-currentTime,expireSeconds));
                        if(updateViewCount)redisCommands.incr(cacheViewCountKey);
                    });
                    return cachedUrl;
                }
                if(redisCommands.setnx(dbLockKey, "1") .get()) {
                    break;
                }else{
                    Thread.sleep(50);
                }
            }
            try(var conn = dataSource.getConnection()) {
                Shortlink link = null;
                try{
                    link= jdbcTemplate.queryForObject("SELECT * FROM urls WHERE idx = ?",
                                                        new BeanPropertyRowMapper<Shortlink>(Shortlink.class),id);
                }catch(Exception e) {
                    // link保持为null
                    throw e instanceof RuntimeException? (RuntimeException)e : new RuntimeException(e);
                }
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
                throw e instanceof RuntimeException? (RuntimeException)e : new RuntimeException(e);
            } finally {
                redisCommands.del(dbLockKey);
            }
        }catch (IllegalArgumentException e) {
            throw e;
        }catch (Exception e) {
            throw e instanceof RuntimeException? (RuntimeException)e : new RuntimeException(e);
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
        try{
            Shortlink sl=jdbcTemplate.queryForObject("SELECT * FROM urls WHERE idx = ?", 
                                                new BeanPropertyRowMapper<Shortlink>(Shortlink.class),id);
            return sl.toString();
        }catch(Exception e) {
            throw new IllegalArgumentException("Shortlink not found");
        }
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
            throw e instanceof RuntimeException? (RuntimeException)e : new RuntimeException(e);
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
        }catch (IllegalArgumentException e) {
            throw e;

        } catch (Exception e) {
            throw e instanceof RuntimeException? (RuntimeException)e : new RuntimeException(e);
        }
    }
}

