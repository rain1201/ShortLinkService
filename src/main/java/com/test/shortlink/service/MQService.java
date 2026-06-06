package com.test.shortlink.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.test.shortlink.entity.RedisKeys;
import com.test.shortlink.entity.View;
import com.test.shortlink.util.Util;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.ObjectMapper;


public class MQService {
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    @Qualifier("asyncExecutor")
    Executor asyncExecutor;
    @Autowired
    ObjectMapper objectMapper;
    @Value("${app.mq.maxLocalCacheSize:1000}")
    long maxLocalCacheSize;
    @Value("${app.mq.maxLocalCacheTime:100000}")
    long maxLocalCacheTime;
    long lastSyncTime = 0;
    private Map<Long,List<View>> viewCache = new HashMap<>();
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(MQService.class);
    private long workerId;
    private long datacenterId;
    private long threadId;
    private String recacheMQKey = null;
    public MQService(long tid){
        threadId=tid;
    }
    @PostConstruct
    public void init() {
        workerId = Util.getWorkerId();
        datacenterId = Util.getDatacenterId();
        recacheMQKey = RedisKeys.URL_VIEW_MQ+":"+datacenterId+":"+workerId+":"+threadId;
        asyncExecutor.execute(()->{
            while(true){
                try{
                    String newView = stringRedisTemplate.opsForList().rightPopAndLeftPush(
                        RedisKeys.URL_VIEW_MQ, 
                        recacheMQKey,Duration.ofSeconds(100));
                    if(newView==null){
                        Thread.sleep(100);
                        continue;
                    }
                    View view = objectMapper.readValue(newView, View.class);
                    viewCache.computeIfAbsent(view.getId(),k->new LinkedList<>()).add(view);
                    if(viewCache.size()>=maxLocalCacheSize || System.currentTimeMillis()-lastSyncTime>maxLocalCacheTime){
                        syncDB();
                        lastSyncTime=System.currentTimeMillis();
                    }
                }catch(Exception e){
                    logger.error("Error occurred while syncing data to DB", e);
                }
            }
        });
    }
    public void syncDB(){
        try(var conn = dataSource.getConnection()) {
            try{
                conn.setAutoCommit(false);
                long totalViews = viewCache.values().stream().mapToLong(List::size).sum();
                logger.info("Syncing {} views to DB", totalViews);
                for(var entry : viewCache.entrySet()) {
                    var idx = entry.getKey();
                    var views = entry.getValue();
                    if(views.size()==0) continue;
                    var sql = "INSERT INTO views (idx, ts, user_agent, ip) VALUES (?, ?, ?, ?)";
                    try(var pstmt = conn.prepareStatement(sql)) {
                        for(var view : views) {
                            pstmt.setLong(1, view.getIdx());
                            pstmt.setLong(2, view.getTs());
                            pstmt.setString(3, view.getUserAgent());
                            pstmt.setString(4, view.getIp());
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                    }
                    sql = "UPDATE urls SET view_count = view_count + ? WHERE idx = ?";
                    try(var pstmt = conn.prepareStatement(sql)) {
                        pstmt.setInt(1, views.size());
                        pstmt.setLong(2, idx);
                        pstmt.executeUpdate();
                    }
                }
                conn.commit();
                viewCache.clear();
                stringRedisTemplate.opsForList().leftPop(recacheMQKey, totalViews);
            } catch(Exception e) {
                conn.rollback();
                while(stringRedisTemplate.opsForList().size(recacheMQKey)>0)
                    stringRedisTemplate.opsForList().rightPopAndLeftPush(recacheMQKey, RedisKeys.URL_VIEW_MQ);
                viewCache.clear();
                logger.error("Error occurred while syncing data to DB, transaction rolled back", e);
            }
        } catch(Exception e) {
            logger.error("Error occurred while syncing data to DB", e);
        }
    }
}
