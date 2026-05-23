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
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisExpireListenerTest {

    @Mock
    private RedisMessageListenerContainer redisMessageListenerContainer;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private RedisExpireListener redisExpireListener;

    @BeforeEach
    void setUp() {
        redisExpireListener = new RedisExpireListener(redisMessageListenerContainer);
        ReflectionTestUtils.setField(redisExpireListener, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    void testDoHandleMessage_UrlKeyExpired() {
        String testId = "12345";
        String expiredKeyName = RedisKeys.URL_KEY_PREFIX + testId;
        Message message = new DefaultMessage(new byte[0], expiredKeyName.getBytes());

        when(stringRedisTemplate.delete(anyString())).thenReturn(true);

        redisExpireListener.doHandleMessage(message);

        verify(stringRedisTemplate).delete(RedisKeys.URL_EXPIRE_KEY_PREFIX + testId);
    }

    @Test
    void testDoHandleMessage_OtherKeyExpired() {
        Message message = new DefaultMessage(new byte[0], "some:other:key".getBytes());

        redisExpireListener.doHandleMessage(message);

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void testDoHandleMessage_EmptyId() {
        Message message = new DefaultMessage(new byte[0], RedisKeys.URL_KEY_PREFIX.getBytes());

        redisExpireListener.doHandleMessage(message);

        verify(stringRedisTemplate, never()).delete(anyString());
    }
}
