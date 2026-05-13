package com.test.shortlink.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.test.shortlink.entity.View;


@Component
public class MQService {
    @Autowired
    RabbitTemplate rabbitTemplate;
    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcTemplate jdbcTemplate;
    private AtomicReference<Map<Long,List<View>>> viewCacheRef = new AtomicReference<>(new ConcurrentHashMap<>());
    private ReadWriteLock rwLock = new java.util.concurrent.locks.ReentrantReadWriteLock();
    private Lock readLock = rwLock.readLock();
    private Lock writeLock = rwLock.writeLock();
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(MQService.class);
    @RabbitListener(queues = "view.queue")
    public void handleViewMessage(View view) {
        try {
            readLock.lock();
            var viewCache = viewCacheRef.get();
            viewCache.computeIfAbsent(view.getIdx(), k-> Collections.synchronizedList(new ArrayList<>())).add(view);
        } finally {
            readLock.unlock();
        }
    }
    @Scheduled(fixedDelay = 60000)
    public void syncDB(){
        var viewCache = viewCacheRef.get();
        try {
            writeLock.lock();
            viewCache = viewCacheRef.getAndSet(new ConcurrentHashMap<>());
        } finally {
            writeLock.unlock();
        }
        try(var conn = dataSource.getConnection()) {
            try{
                conn.setAutoCommit(false);
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
            } catch(Exception e) {
                conn.rollback();
                logger.error("Error occurred while syncing data to DB, transaction rolled back", e);
            }
        } catch(Exception e) {
            logger.error("Error occurred while syncing data to DB", e);
        }
    }
}
