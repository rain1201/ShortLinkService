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

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;

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

    @Mock
    private RedisClient redisClient;

    @Mock
    private StatefulRedisConnection<String, String> conn;

    @Mock
    private RedisCommands<String, String> commands;

    @Test
    void testSyncCache() {
        String testId = "1001";
        String redisKey = RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + testId;
        List<String> keyList = Collections.singletonList(redisKey);

        KeyScanCursor<String> firstCursor = mock(KeyScanCursor.class);
        when(firstCursor.isFinished()).thenReturn(false);
        when(firstCursor.getKeys()).thenReturn(keyList);

        KeyScanCursor<String> secondCursor = mock(KeyScanCursor.class);
        when(secondCursor.isFinished()).thenReturn(true);

        when(redisClient.connect()).thenReturn(conn);
        when(conn.sync()).thenReturn(commands);
        doReturn(firstCursor).when(commands).scan(isA(ScanArgs.class));
        doReturn(secondCursor).when(commands).scan(isA(ScanCursor.class), isA(ScanArgs.class));

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(redisKey)).thenReturn("55");

        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(asyncExecutor).execute(any(Runnable.class));

        cacheSyncService.syncCache();

        verify(shortlinkRepository, times(1)).updateViewCount(1001L, 55L);
    }
}
