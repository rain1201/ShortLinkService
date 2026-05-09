package com.test.shortlink.service;

import com.test.shortlink.entity.RedisKeys;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
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
    private RedisClient redisClient;

    @Mock
    private StatefulRedisConnection<String, String> redisConnection;

    @Mock
    private RedisCommands<String, String> redisCommands;

    @Mock
    private Connection dbConnection;

    @Mock
    private PreparedStatement preparedStatement;

    // 注意这里去掉了 @InjectMocks，改为手动初始化
    private RedisExpireListener redisExpireListener;

    @BeforeEach
    void setUp() {
        // 1. 手动通过构造函数实例化，满足 Spring 父类的非空要求
        redisExpireListener = new RedisExpireListener(redisMessageListenerContainer);
        
        // 2. 强行把 @Autowired 的字段塞进去，绕过 Mockito 的注入缺陷
        ReflectionTestUtils.setField(redisExpireListener, "dataSource", dataSource);
        ReflectionTestUtils.setField(redisExpireListener, "redisClient", redisClient);
    }

    @Test
    void testDoHandleMessage_UrlKeyExpired() throws Exception {
        String testId = "12345";
        String expiredKeyName = RedisKeys.URL_KEY_PREFIX + testId;
        Message message = new DefaultMessage(new byte[0], expiredKeyName.getBytes());

        // Mock Redis
        when(redisClient.connect()).thenReturn(redisConnection);
        when(redisConnection.sync()).thenReturn(redisCommands);
        when(redisCommands.getdel(RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + testId)).thenReturn("50");

        // Mock Database
        when(dataSource.getConnection()).thenReturn(dbConnection);
        when(dbConnection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenReturn(1);

        // 执行监听事件
        redisExpireListener.doHandleMessage(message);

        // 验证 Redis 取出了 viewCount 且清除了 expireKey
        verify(redisCommands).getdel(RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + testId);
        verify(redisCommands).getdel(RedisKeys.URL_EXPIRE_KEY_PREFIX + testId);

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

        redisExpireListener.doHandleMessage(message);

        // 验证没有去连接 Redis 和 DB
        verify(redisClient, never()).connect();
        verifyNoInteractions(dataSource);
    }
}