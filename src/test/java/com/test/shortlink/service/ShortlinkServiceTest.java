package com.test.shortlink.service;

import com.test.shortlink.entity.Shortlink;
import com.test.shortlink.util.Util;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShortlinkServiceTest {

    @InjectMocks
    private ShortlinkService shortlinkService;

    @Mock
    private DataSource dataSource;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private RedisClient redisClient;

    @Mock
    private StatefulRedisConnection<String, String> redisConnection;

    @Mock
    private RedisAsyncCommands<String, String> redisAsyncCommands;

    @Mock
    private RedisCommands<String, String> redisSyncCommands;

    @Mock
    private Connection dbConnection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    // 根据返回值类型，分别声明不同的 Mock Future 实例防止强转或拆箱失败
    @Mock
    private RedisFuture<String> mockStringRedisFuture;

    @Mock
    private RedisFuture<Boolean> mockBooleanRedisFuture;

    @Mock
    private RedisFuture<String> mockBoolStringRedisFuture; // 用于 setex 返回 "OK"

    @Mock
    private RedisFuture<Long> mockLongRedisFuture;

    @BeforeEach
    void setUp() throws Exception {
        // 1. 注入 @Value 的默认值
        ReflectionTestUtils.setField(shortlinkService, "expireSeconds", 15);

        // 2. 统一兜底 Mock 所有的异步 Redis 常用操作，防止任何业务代码调用时报 NPE
        lenient().when(redisClient.connect()).thenReturn(redisConnection);
        lenient().when(redisConnection.async()).thenReturn(redisAsyncCommands);
        lenient().when(redisConnection.sync()).thenReturn(redisSyncCommands);

        // 异步 String 操作默认返回 mockStringRedisFuture
        lenient().when(redisAsyncCommands.get(anyString())).thenReturn(mockStringRedisFuture);
        lenient().when(redisAsyncCommands.set(anyString(), anyString())).thenReturn(mockBoolStringRedisFuture);
        lenient().when(redisAsyncCommands.setex(anyString(), anyLong(), anyString())).thenReturn(mockBoolStringRedisFuture);
        
        // 异步 Boolean 操作（如 setnx、expire）默认返回 mockBooleanRedisFuture
        lenient().when(redisAsyncCommands.setnx(anyString(), anyString())).thenReturn(mockBooleanRedisFuture);
        lenient().when(redisAsyncCommands.expire(anyString(), anyLong())).thenReturn(mockBooleanRedisFuture);
        
        // 异步 Long 操作（如 incr、del）默认返回 mockLongRedisFuture
        lenient().when(redisAsyncCommands.incr(anyString())).thenReturn(mockLongRedisFuture);
        lenient().when(redisAsyncCommands.del(any(String[].class))).thenReturn(mockLongRedisFuture);
        lenient().when(redisAsyncCommands.del(anyString())).thenReturn(mockLongRedisFuture);

        
        lenient().when(redisSyncCommands.get(anyString())).thenReturn(null);
        lenient().when(redisSyncCommands.set(anyString(), anyString())).thenReturn("OK");
        lenient().when(redisSyncCommands.set(anyString(), anyString(), any())).thenReturn("OK");
        lenient().when(redisSyncCommands.setex(anyString(), anyLong(), anyString())).thenReturn("OK");
        
        // 3. 配置这些 Mock Future 的默认行为（.get() 时返回什么安全值）
        lenient().when(mockStringRedisFuture.get()).thenReturn(null); // 默认未命中缓存
        lenient().when(mockBooleanRedisFuture.get()).thenReturn(true); // setnx 默认成功
        lenient().when(mockLongRedisFuture.get()).thenReturn(1L);
        lenient().when(mockBoolStringRedisFuture.get()).thenReturn("OK");
        
        // 预防 thenAcceptAsync 回调报错
        lenient().when(mockStringRedisFuture.thenAcceptAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void testShorten_ValidUrl() throws Exception {
        String originalUrl = "http://example.com";
        long expireAfter = 10000;
        String updateCode = "code1234";

        // Mock 数据库连接和执行
        when(dataSource.getConnection()).thenReturn(dbConnection);
        when(dbConnection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenReturn(1);

        // Mock JdbcTemplate 查询（缓存未命中时会去查数据库）
        Shortlink mockLink = new Shortlink();
        mockLink.setOriginalUrl(originalUrl);
        mockLink.setExpireAfter(-1);
        lenient().when(jdbcTemplate.queryForObject(anyString(), any(BeanPropertyRowMapper.class), anyLong())).thenReturn(mockLink);

        // 执行被测方法
        String shortId = shortlinkService.shorten(originalUrl, expireAfter, updateCode);

        // 验证
        assertNotNull(shortId);
        verify(preparedStatement, times(1)).executeUpdate();
    }

    @Test
    void testShorten_InvalidUrl() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            shortlinkService.shorten("invalid-url", 1000, "");
        });
        assertEquals("Invalid URL", exception.getMessage());
    }

    @Test
    void testRedirect_CacheHit() throws Exception {
        long id = 123L;
        String cachedUrl = "http://cached.com";

        // 局部覆盖默认设置：模拟缓存命中 (即 get().get() 返回具体 URL)
        when(mockStringRedisFuture.get()).thenReturn(cachedUrl);
        lenient().when(redisSyncCommands.get(anyString())).thenReturn(cachedUrl);

        String result = shortlinkService.redirect(id, true);

        assertEquals(cachedUrl, result);
        // 验证命中缓存时，没有去查询数据库
        verifyNoInteractions(dataSource);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void testDelete_Success() throws Exception {
        String idStr = Util.idToStr(123L);
        String realUpdateCode = "secret123";

        // Mock 数据库连接
        when(dataSource.getConnection()).thenReturn(dbConnection);
        when(dbConnection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        
        // 模拟查到了正确的 updateCode
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("updateCode")).thenReturn(realUpdateCode);
        when(preparedStatement.executeUpdate()).thenReturn(1);

        String result = shortlinkService.delete(idStr, "secret123");

        assertEquals("Shortlink deleted successfully", result);
    }

    @Test
    void testDelete_InvalidCode() throws Exception {
        String idStr = Util.idToStr(123L);

        when(dataSource.getConnection()).thenReturn(dbConnection);
        when(dbConnection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("updateCode")).thenReturn("realCode");

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            shortlinkService.delete(idStr, "wrongCode");
        });

        assertEquals("Invalid update code", exception.getMessage());
    }

    @Test
    void testGetInfo_NotFound() throws Exception {
        String idStr = Util.idToStr(123L);

        // Mock 数据库查不到
        lenient().when(jdbcTemplate.queryForObject(anyString(), any(BeanPropertyRowMapper.class), anyLong())).thenReturn(null);

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            shortlinkService.getInfo(idStr);
        });

        assertEquals("Shortlink not found", exception.getMessage());
    }
}