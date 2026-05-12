package com.test.shortlink.service;

import com.test.shortlink.entity.RedisKeys;
import com.test.shortlink.repository.ShortlinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheSyncServiceTest {

    @InjectMocks
    private CacheSyncService cacheSyncService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private Executor asyncExecutor;

    @Mock
    private ShortlinkRepository shortlinkRepository;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void testSyncCache() {
        String testId = "1001";
        String redisKey = RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + testId;
        Set<String> keys = new HashSet<>(Collections.singletonList(redisKey));

        // 模拟 Redis 数据
        when(stringRedisTemplate.keys(RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + "*")).thenReturn(keys);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(redisKey)).thenReturn("55");

        // 模拟线程池直接执行传入的 Runnable
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(asyncExecutor).execute(any(Runnable.class));

        // 执行同步
        cacheSyncService.syncCache();

        // 验证数据库被正确更新
        verify(shortlinkRepository, times(1)).updateViewCount(1001L, 55L);
    }
}