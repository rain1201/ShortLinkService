package com.test.shortlink.service;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.test.shortlink.entity.RedisKeys;
import com.test.shortlink.repository.ShortlinkRepository;

@Component
public class CacheSyncService {
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    @Qualifier("asyncExecutor")
    private Executor asyncExecutor;
    @Autowired
    private ShortlinkRepository shortlinkRepository;
    @Scheduled(fixedDelay = 60000) // 每分钟执行一次
    public void syncCache() {
        for(var key:stringRedisTemplate.keys(RedisKeys.URL_VIEW_COUNT_KEY_PREFIX+"*")){
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
    }    
}
