package com.test.shortlink.service;

import com.test.shortlink.entity.Shortlink;
import com.test.shortlink.util.Util;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
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
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private Connection dbConnection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    @BeforeEach
    void setUp() throws Exception {
        // 1. 注入 @Value 的默认值
        ReflectionTestUtils.setField(shortlinkService, "expireSeconds", 15);

        // 2. 基础 Redis Mock
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        // 3. 默认读写行为打桩
        lenient().when(valueOperations.get(anyString())).thenReturn(null); // 默认未命中缓存
        
        // 核心：默认允许所有分布式锁获取成功 (setIfAbsent 返回 true)
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        
        // 其他无返回值的常用操作兜底
        lenient().when(stringRedisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        lenient().when(stringRedisTemplate.delete(anyString())).thenReturn(true);
        lenient().when(valueOperations.increment(anyString())).thenReturn(1L);
    }

    @Test
    void testShorten_ValidUrl() throws Exception {
        String originalUrl = "http://example.com";
        long expireAfter = 10000;
        String updateCode = "code1234";

        // Mock DB 插入
        when(dataSource.getConnection()).thenReturn(dbConnection);
        when(dbConnection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenReturn(1);

        // Mock DB 查询 (模拟 redirect 缓存未命中时的查库)
        Shortlink mockLink = new Shortlink();
        mockLink.setOriginalUrl(originalUrl);
        mockLink.setExpireAfter(-1);
        lenient().when(jdbcTemplate.queryForObject(anyString(), any(BeanPropertyRowMapper.class), anyLong())).thenReturn(mockLink);

        String shortId = shortlinkService.shorten(originalUrl, expireAfter, updateCode);

        assertNotNull(shortId);
        // 验证生成时成功获取了创建锁
        verify(valueOperations).setIfAbsent(contains("createLock"), eq("1"), any(Duration.class));
        verify(preparedStatement, times(1)).executeUpdate();
    }

    @Test
    void testShorten_CreateLockFailed() {
        // 模拟创建锁被占用（并发冲突）
        when(valueOperations.setIfAbsent(contains("createLock"), eq("1"), any(Duration.class))).thenReturn(false);

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            shortlinkService.shorten("http://example.com", 1000, "code12");
        });
        
        assertEquals("Shortlink creation is too frequent for the same URL and expireAfter combination", exception.getMessage());
    }

    @Test
    void testRedirect_CacheHit() throws Exception {
        long id = 123L;
        String cachedUrl = "http://cached.com";

        // 模拟缓存命中
        when(valueOperations.get(contains("shortlink:url:"))).thenReturn(cachedUrl);
        // 模拟过期时间读取（用于异步更新 viewCount）
        lenient().when(valueOperations.get(contains("expire:"))).thenReturn("9999999999");

        String result = shortlinkService.redirect(id, true);

        assertEquals(cachedUrl, result);
        // 验证命中缓存时，没有去查询数据库，也没有去抢 DB 锁
        verifyNoInteractions(dataSource);
        verifyNoInteractions(jdbcTemplate);
        
        // 因为 incrementViewCountAsync 是异步执行的，稍作等待以验证
        Thread.sleep(100); 
        verify(valueOperations).increment(contains("viewCount"));
    }

    @Test
    void testRedirect_LockFailed() {
        long id = 123L;

        // 模拟缓存未命中
        when(valueOperations.get(contains("shortlink:url:"))).thenReturn(null);
        // 模拟 DB 锁被其他线程一直占用（重试5次均失败）
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            shortlinkService.redirect(id, true);
        });

        assertEquals("Shortlink is being accessed too frequently, please try again later", exception.getMessage());
        // 验证确实重试了 5 次
        verify(valueOperations, times(5)).setIfAbsent(contains("dblock"), eq("1"), any(Duration.class));
    }

    @Test
    void testUpdate_Success() {
        String idStr = Util.idToStr(123L);
        String realUpdateCode = "secret123";
        String url = "http://new-url.com";
        long expireAfter = 3600;

        // 构造 DB 存在的记录
        Shortlink mockLink = new Shortlink();
        mockLink.setOriginalUrl("http://old.com");
        mockLink.setExpireAfter(-1);
        mockLink.setCreatedAt(System.currentTimeMillis() / 1000);
        mockLink.setUpdateCode(realUpdateCode);
        
        // 反推计算合法的 UpdateCode
        var generatedUpdateCode = Util.generateUpdateCode(realUpdateCode, 123L + url + expireAfter);

        when(jdbcTemplate.queryForObject(anyString(), any(BeanPropertyRowMapper.class), anyLong())).thenReturn(mockLink);

        String result = shortlinkService.update(idStr, url, expireAfter, generatedUpdateCode);

        assertEquals("Shortlink updated successfully", result);
        verify(jdbcTemplate).update(anyString(), anyString(), anyLong(), anyLong());
        // 验证缓存被设置为了立刻过期（清理缓存）
        verify(stringRedisTemplate).expire(contains("shortlink:url:"), eq(Duration.ofSeconds(0)));
    }

    @Test
    void testDelete_Success() throws Exception {
        String idStr = Util.idToStr(123L);
        String realUpdateCode = "secret123";

        // Mock 数据库连接
        when(dataSource.getConnection()).thenReturn(dbConnection);
        when(dbConnection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("updateCode")).thenReturn(realUpdateCode);
        when(preparedStatement.executeUpdate()).thenReturn(1);

        String result = shortlinkService.delete(idStr, realUpdateCode);

        assertEquals("Shortlink deleted successfully", result);
        // 验证删除了三个相关的 redis key
        verify(stringRedisTemplate, times(3)).delete(anyString());
    }

    @Test
    void testGetInfo_NotFound() {
        String idStr = Util.idToStr(123L);

        // Mock redirect 方法内部的查询抛出异常
        doThrow(new IllegalArgumentException("Shortlink not found")).when(shortlinkService).redirect(123L, false);
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            shortlinkService.getInfo(idStr);
        });

        assertEquals("Shortlink not found", exception.getMessage());
    }
}