package com.test.shortlink.service;

import com.test.shortlink.entity.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisExpireListenerTest {

    @Mock
    private RedisMessageListenerContainer redisMessageListenerContainer;

    @Mock
    private DataSource dataSource;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> redisCommands;

    @Mock
    private Connection dbConnection;

    @Mock
    private PreparedStatement preparedStatement;

    private RedisExpireListener redisExpireListener;

    @BeforeEach
    void setUp() {
        // 1. 初始化 Listener
        redisExpireListener = new RedisExpireListener(redisMessageListenerContainer);
        
        // 2. 注入依赖 (注意：这里不再放置 when(...) 打桩代码)
        ReflectionTestUtils.setField(redisExpireListener, "dataSource", dataSource);
        ReflectionTestUtils.setField(redisExpireListener, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    void testDoHandleMessage_UrlKeyExpired() throws Exception {
        String testId = "12345";
        String expiredKeyName = RedisKeys.URL_KEY_PREFIX + testId;
        Message message = new DefaultMessage(new byte[0], expiredKeyName.getBytes());

        // 修复点：将具体测试用例才会用到的 Mock 移入方法内部
        when(stringRedisTemplate.delete(anyString())).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(redisCommands);
        when(redisCommands.getAndDelete(anyString())).thenReturn("50");

        // 针对当前用例打桩 DB 行为
        when(dataSource.getConnection()).thenReturn(dbConnection);
        when(dbConnection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenReturn(1);

        // 执行被测方法
        redisExpireListener.doHandleMessage(message);

        // 验证交互
        verify(redisCommands).getAndDelete(RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + testId);
        verify(stringRedisTemplate).delete(RedisKeys.URL_EXPIRE_KEY_PREFIX + testId);

        // 验证执行了数据库更新语句
        verify(preparedStatement).setString(1, "50");
        verify(preparedStatement).setString(2, testId);
        verify(preparedStatement).setString(3, "50");
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void testDoHandleMessage_OtherKeyExpired() {
        // 如果过期的不是短链接 URL Key，不应该执行任何逻辑
        Message message = new DefaultMessage(new byte[0], "some:other:key".getBytes());

        // 因为这里没有多余的 when() 打桩，Mockito 不会再抛出 UnnecessaryStubbingException
        redisExpireListener.doHandleMessage(message);

        // 验证没有对 Redis 缓存和 DB 产生任何交互
        verifyNoInteractions(stringRedisTemplate);
        verifyNoInteractions(dataSource);
    }
}