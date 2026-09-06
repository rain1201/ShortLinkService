package com.test.shortlink.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import jakarta.annotation.PreDestroy;
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
    @Value("${app.mq.max-local-cache-size:1000}")
    long maxLocalCacheSize;
    @Value("${app.mq.max-local-cache-time:100000}")
    long maxLocalCacheTime;
    long lastSyncTime = 0;
    int cachedViewCount = 0; 
    private Map<Long,List<View>> viewCache = new HashMap<>();
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(MQService.class);
    private long workerId;
    private long datacenterId;
    private long threadId;
    private String recacheMQKey = null;
    private volatile boolean running = true;
    public MQService(long tid){
        threadId=tid;
    }
    @PostConstruct
    public void init() {
        workerId = Util.getWorkerId();
        datacenterId = Util.getDatacenterId();
        recacheMQKey = RedisKeys.URL_VIEW_MQ+":"+datacenterId+":"+workerId+":"+threadId;
        asyncExecutor.execute(()->{
            if(stringRedisTemplate.opsForList().size(recacheMQKey)>0)syncDB();
            while(running){
                try{
                    String newView = stringRedisTemplate.opsForList().rightPopAndLeftPush(
                        RedisKeys.URL_VIEW_MQ, 
                        recacheMQKey,Duration.ofSeconds(10));
                    if(cachedViewCount>=maxLocalCacheSize || System.currentTimeMillis()-lastSyncTime>maxLocalCacheTime){
                        syncDB();
                        lastSyncTime=System.currentTimeMillis();
                        cachedViewCount = 0;
                    }
                    if(newView==null){
                        Thread.sleep(1000);
                        continue;
                    }
                    cachedViewCount+=1;
                    
                }catch(Exception e){
                    logger.error("Error occurred while syncing data to DB", e);
                }
            }
        });
    }
    public void syncDB(){
        long cacheLen=stringRedisTemplate.opsForList().size(recacheMQKey);
        for(String viewStr:stringRedisTemplate.opsForList().range(recacheMQKey, 0, cacheLen-1)){
            View view=objectMapper.readValue(viewStr, View.class);
            viewCache.computeIfAbsent(view.getIdx(),k->new LinkedList<>()).add(view);
        }
        try(var conn = dataSource.getConnection()) {
            try{
                conn.setAutoCommit(false);
                logger.info("Syncing {} views to DB", cacheLen);
                List<View> pendingViews = new ArrayList<>();
                Set<Long> seenEventIds = new HashSet<>();
                for (var views : viewCache.values()) {
                    for (var view : views) {
                        if (view.getId() == 0 || seenEventIds.add(view.getId())) {
                            pendingViews.add(view);
                        }
                    }
                }

                Set<Long> persistedEventIds = new HashSet<>();
                List<Long> eventIds = pendingViews.stream()
                        .map(View::getId)
                        .filter(id -> id != 0)
                        .toList();
                if (!eventIds.isEmpty()) {
                    String placeholders = String.join(",", java.util.Collections.nCopies(eventIds.size(), "?"));
                    try (var pstmt = conn.prepareStatement("SELECT id FROM views WHERE id IN (" + placeholders + ")")) {
                        for (int i = 0; i < eventIds.size(); i++) {
                            pstmt.setLong(i + 1, eventIds.get(i));
                        }
                        try (var rs = pstmt.executeQuery()) {
                            while (rs.next()) {
                                persistedEventIds.add(rs.getLong(1));
                            }
                        }
                    }
                }

                List<View> newViews = pendingViews.stream()
                        .filter(view -> view.getId() == 0 || !persistedEventIds.contains(view.getId()))
                        .toList();
                boolean hasViews = !newViews.isEmpty();

                List<View> generatedIdViews = newViews.stream().filter(view -> view.getId() != 0).toList();
                if (!generatedIdViews.isEmpty()) {
                    try (var pstmt = conn.prepareStatement(
                            "INSERT INTO views (id, idx, ts, user_agent, ip) VALUES (?, ?, ?, ?, ?)")) {
                        for (var view : generatedIdViews) {
                            pstmt.setLong(1, view.getId());
                            pstmt.setLong(2, view.getIdx());
                            pstmt.setLong(3, view.getTs());
                            pstmt.setString(4, view.getUserAgent());
                            pstmt.setString(5, view.getIp());
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                    }
                }

                List<View> legacyViews = newViews.stream().filter(view -> view.getId() == 0).toList();
                if (!legacyViews.isEmpty()) {
                    try (var pstmt = conn.prepareStatement(
                            "INSERT INTO views (idx, ts, user_agent, ip) VALUES (?, ?, ?, ?)")) {
                        for (var view : legacyViews) {
                            pstmt.setLong(1, view.getIdx());
                            pstmt.setLong(2, view.getTs());
                            pstmt.setString(3, view.getUserAgent());
                            pstmt.setString(4, view.getIp());
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                    }
                }

                Map<Long, Integer> newViewCounts = new HashMap<>();
                for (var view : newViews) {
                    newViewCounts.merge(view.getIdx(), 1, Integer::sum);
                }
                var sql = "UPDATE urls SET view_count = view_count + ? WHERE idx = ?";
                try(var pstmt = conn.prepareStatement(sql)) {
                    for(var entry : newViewCounts.entrySet()) {
                        pstmt.setInt(1, entry.getValue());
                        pstmt.setLong(2, entry.getKey());
                        pstmt.addBatch();
                    }
                    if (hasViews) {
                        pstmt.executeBatch();
                    }
                }
                conn.commit();
                viewCache.clear();
                stringRedisTemplate.opsForList().leftPop(recacheMQKey, cacheLen);
            } catch(Exception e) {
                conn.rollback();
                //while(stringRedisTemplate.opsForList().size(recacheMQKey)>0)
                //    stringRedisTemplate.opsForList().rightPopAndLeftPush(recacheMQKey, RedisKeys.URL_VIEW_MQ);
                viewCache.clear();
                logger.error("Error occurred while syncing data to DB, transaction rolled back", e);
                Thread.sleep(1000);
            }
        } catch(Exception e) {
            logger.error("Error occurred while syncing data to DB", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
    }
}
