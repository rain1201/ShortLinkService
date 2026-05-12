package com.test.shortlink.service;

import com.test.shortlink.entity.Shortlink;
import com.test.shortlink.repository.ShortlinkRepository;
import com.test.shortlink.util.Util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShortlinkServiceTest {

    @InjectMocks
    @Spy
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
    private ShortlinkRepository shortlinkRepository;

    @Mock
    private ResultSet resultSet;

    @Spy // 或者使用真实的线程池
    private Executor myExecutor = Executors.newFixedThreadPool(1);

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
        when(shortlinkRepository.save(any(Shortlink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Mock DB 查询 (模拟 redirect 缓存未命中时的查库)
        Shortlink mockLink = new Shortlink();
        mockLink.setOriginalUrl(originalUrl);
        mockLink.setExpireAfter(-1);
        lenient().when(shortlinkRepository.findById(anyLong())).thenReturn(java.util.Optional.of(mockLink));

        String shortId = shortlinkService.shorten(originalUrl, expireAfter, updateCode);

        assertNotNull(shortId);
        // 验证生成时成功获取了创建锁
        verify(valueOperations).setIfAbsent(contains("createLock"), eq("1"), any(Duration.class));
        verify(shortlinkRepository, times(1)).save(any(Shortlink.class));
    }

    @Test
    void testShorten_CreateLockFailed() {
        // 模拟创建锁被占用（并发冲突）
        when(valueOperations.setIfAbsent(contains("createLock"), eq("1"), any(Duration.class))).thenReturn(false);

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            shortlinkService.shorten("http://example.com", 1000, "code12");
        });
        
        assertEquals("Shortlink creation is too frequent for the same URL", exception.getMessage());
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
        when(valueOperations.setIfAbsent(contains("dblock"), eq("1"), any(Duration.class))).thenReturn(false);
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

        when(shortlinkRepository.findById(anyLong())).thenReturn(java.util.Optional.of(mockLink));
        String result = shortlinkService.update(idStr, url, expireAfter, generatedUpdateCode);

        assertEquals("Shortlink updated successfully", result);
        verify(shortlinkRepository).saveAndFlush(any(Shortlink.class));
        // 验证缓存被设置为了立刻过期（清理缓存）
        verify(stringRedisTemplate).expire(contains("shortlink:url:"), eq(Duration.ofSeconds(0)));
    }

    @Test
    void testDelete_Success() throws Exception {
        String idStr = Util.idToStr(123L);
        String realUpdateCode = "secret123";

        // Mock 数据库连接
        when(shortlinkRepository.findById(anyLong())).thenReturn(java.util.Optional.of(new Shortlink(){{
            setUpdateCode(realUpdateCode);
        }}));
        when(dataSource.getConnection()).thenReturn(dbConnection);
        /*when(dbConnection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("updateCode")).thenReturn(realUpdateCode);
        when(preparedStatement.executeUpdate()).thenReturn(1);*/
        doNothing().when(shortlinkRepository).deleteById(anyLong());

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

    @Test
    void testRedirect_DbHit_ValidLink() throws Exception {
        long id = 123L;
        String originalUrl = "http://db-hit.com";

        // 缓存未命中，获取了DB锁
        when(valueOperations.get(contains("shortlink:url:"))).thenReturn(null);
        when(valueOperations.setIfAbsent(contains("dblock"), eq("1"), any(Duration.class))).thenReturn(true);

        // Mock DB 查询返回有效数据
        Shortlink mockLink = new Shortlink();
        mockLink.setOriginalUrl(originalUrl);
        mockLink.setExpireAfter(-1); // 永不过期
        mockLink.setViewCount(10);
        when(shortlinkRepository.findById(id)).thenReturn(java.util.Optional.of(mockLink));

        String result = shortlinkService.redirect(id, false);

        assertEquals(originalUrl, result);
        // 验证查库后重新写入缓存
        verify(stringRedisTemplate).expire(contains("shortlink:url:"), any(Duration.class));
        verify(valueOperations).set(contains("viewCount"), eq("10")); // 因为传入的是 updateViewCount=false
    }

    @Test
    void testRedirect_DbHit_ExpiredLink() throws Exception {
        long id = 123L;
        // 缓存未命中，获取了DB锁
        when(valueOperations.get(contains("shortlink:url:"))).thenReturn(null);
        when(valueOperations.setIfAbsent(contains("dblock"), eq("1"), any(Duration.class))).thenReturn(true);

        Shortlink mockLink = new Shortlink();
        mockLink.setOriginalUrl("http://expired.com");
        mockLink.setCreatedAt(System.currentTimeMillis() / 1000 - 10000); // 10000秒前创建
        mockLink.setExpireAfter(100); // 100秒后过期 (已经过期)
        when(shortlinkRepository.findById(id)).thenReturn(java.util.Optional.of(mockLink));

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            shortlinkService.redirect(id, true);
        });
        assertEquals("Shortlink has expired", exception.getMessage());
    }

    @Test
    void testUpdate_InvalidUpdateCode() {
        String idStr = Util.idToStr(123L);
        Shortlink mockLink = new Shortlink();
        mockLink.setUpdateCode("realCode");
        mockLink.setOriginalUrl("old.com");

        when(shortlinkRepository.findById(123L)).thenReturn(java.util.Optional.of(mockLink));

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            // 提供错误的 updateCode
            shortlinkService.update(idStr, "http://new.com", 100, "wrongCode");
        });
        assertEquals("Invalid update code", exception.getMessage());
    }

    @Test
    void testDelete_InvalidUpdateCode() throws Exception {
        String idStr = Util.idToStr(123L);
        Shortlink mockLink = new Shortlink();
        mockLink.setUpdateCode("realCode");

        when(dataSource.getConnection()).thenReturn(dbConnection);
        when(shortlinkRepository.findById(123L)).thenReturn(java.util.Optional.of(mockLink));

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            shortlinkService.delete(idStr, "wrongCode");
        });
        assertEquals("Invalid update code", exception.getMessage());
    }

    @Test
    void testGetInfo_Success() {
        String idStr = Util.idToStr(123L);
        Shortlink mockLink = new Shortlink();
        mockLink.setOriginalUrl("http://example.com");
        
        // redirect方法会被调用，我们需要让它通过。
        // 为了避免它真的去查DB，可以Mock它的行为（因为已经Spy了shortlinkService）
        doReturn("http://example.com").when(shortlinkService).redirect(123L, false);
        when(shortlinkRepository.findById(123L)).thenReturn(java.util.Optional.of(mockLink));

        String info = shortlinkService.getInfo(idStr);
        assertNotNull(info);
        assertTrue(info.contains("http://example.com"));
    }
}