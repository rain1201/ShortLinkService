package com.test.shortlink.service;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Component;

import com.test.shortlink.entity.RedisKeys;
import com.test.shortlink.repository.ShortlinkRepository;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;

//@Component
public class CacheSyncService {
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    @Qualifier("asyncExecutor")
    private Executor asyncExecutor;
    @Autowired
    private ShortlinkRepository shortlinkRepository;
    @Autowired
    private RedisClient redisClient;
    //@Scheduled(fixedDelay = 60000) // 每分钟执行一次
    public void syncCache() {
        try(var connection=redisClient.connect()){
            var syncCommands=connection.sync();
            ScanArgs scanArgs=ScanArgs.Builder.matches(RedisKeys.URL_VIEW_COUNT_KEY_PREFIX+"*");
            var cursor=syncCommands.scan(scanArgs);
            while(!cursor.isFinished()){
                for(String key:cursor.getKeys()){
                    asyncExecutor.execute(()->{
                        String idStr=key.substring(RedisKeys.URL_VIEW_COUNT_KEY_PREFIX.length());
                        long id=Long.parseLong(idStr);
                        String viewCountStr=stringRedisTemplate.opsForValue().get(key);
                        if(viewCountStr==null) {
                            return;
                        }
                        long viewCount=Long.parseLong(viewCountStr);
                        shortlinkRepository.updateViewCount(id, viewCount);
                    });
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                cursor=syncCommands.scan(cursor,scanArgs);
            }
        }
    }    
}
