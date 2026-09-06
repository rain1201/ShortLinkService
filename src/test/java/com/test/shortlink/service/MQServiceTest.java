package com.test.shortlink.service;

import com.test.shortlink.entity.RedisKeys;
import com.test.shortlink.entity.View;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MQServiceTest {

    private MQService mqService;

    @Mock
    private DataSource dataSource;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ListOperations<String, String> listOperations;

    @Mock
    private Connection dbConnection;

    @Mock
    private PreparedStatement insertStmt;

    @Mock
    private PreparedStatement updateStmt;

    @BeforeEach
    void setUp() throws Exception {
        mqService = new MQService(0);
        ReflectionTestUtils.setField(mqService, "dataSource", dataSource);
        ReflectionTestUtils.setField(mqService, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(mqService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(mqService, "recacheMQKey", "test:recache:0:0:0");
        lenient().when(stringRedisTemplate.opsForList()).thenReturn(listOperations);
        lenient().when(dataSource.getConnection()).thenReturn(dbConnection);
        lenient().when(dbConnection.prepareStatement(contains("INSERT"))).thenReturn(insertStmt);
        lenient().when(dbConnection.prepareStatement(contains("UPDATE"))).thenReturn(updateStmt);
    }

    @Test
    void testSyncDB_Success() throws Exception {
        Map<Long, List<View>> viewCache = new HashMap<>();
        View view = new View();
        view.setIdx(100L);
        view.setIp("192.168.1.1");
        view.setUserAgent("Mozilla/5.0");
        view.setTs(1234567890L);
        viewCache.put(100L, new LinkedList<>(List.of(view)));
        ReflectionTestUtils.setField(mqService, "viewCache", viewCache);

        mqService.syncDB();

        verify(insertStmt).executeBatch();
        verify(updateStmt).executeBatch();
        verify(dbConnection).commit();
        verify(listOperations).leftPop(eq("test:recache:0:0:0"), eq(0L));
    }

    @Test
    void testSyncDB_EmptyCache() {
        Map<Long, List<View>> viewCache = new HashMap<>();
        ReflectionTestUtils.setField(mqService, "viewCache", viewCache);

        assertDoesNotThrow(() -> mqService.syncDB());
        verify(listOperations).leftPop(eq("test:recache:0:0:0"), eq(0L));
    }

    @Test
    void testSyncDB_MultipleIndices() throws Exception {
        Map<Long, List<View>> viewCache = new HashMap<>();
        View v1 = new View();
        v1.setIdx(1L);
        v1.setIp("10.0.0.1");
        v1.setTs(100L);
        View v2 = new View();
        v2.setIdx(2L);
        v2.setIp("10.0.0.2");
        v2.setTs(200L);
        View v3 = new View();
        v3.setIdx(1L);
        v3.setIp("10.0.0.3");
        v3.setTs(300L);
        viewCache.put(1L, new LinkedList<>(List.of(v1, v3)));
        viewCache.put(2L, new LinkedList<>(List.of(v2)));
        ReflectionTestUtils.setField(mqService, "viewCache", viewCache);

        mqService.syncDB();

        verify(insertStmt).executeBatch();
        verify(updateStmt).executeBatch();
    }

    @Test
    void testSyncDB_RollbackOnError() throws Exception {
        Map<Long, List<View>> viewCache = new HashMap<>();
        View view = new View();
        view.setIdx(100L);
        view.setIp("1.1.1.1");
        view.setTs(100L);
        viewCache.put(100L, new LinkedList<>(List.of(view)));
        ReflectionTestUtils.setField(mqService, "viewCache", viewCache);

        doThrow(new RuntimeException("DB error")).when(insertStmt).executeBatch();

        mqService.syncDB();

        verify(dbConnection).rollback();
        verify(listOperations).size(anyString());
    }

    @Test
    void testSyncDB_SkipEmptyViewList() throws Exception {
        Map<Long, List<View>> viewCache = new HashMap<>();
        viewCache.put(100L, new LinkedList<>());
        ReflectionTestUtils.setField(mqService, "viewCache", viewCache);

        mqService.syncDB();

        verify(insertStmt, never()).executeBatch();
        verify(updateStmt, never()).executeBatch();
        verify(dbConnection).commit();
    }
}
