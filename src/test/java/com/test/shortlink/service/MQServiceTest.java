package com.test.shortlink.service;

import com.test.shortlink.entity.View;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MQServiceTest {

    @InjectMocks
    private MQService mqService;

    @Mock
    private DataSource dataSource;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private Connection dbConnection;

    @Mock
    private PreparedStatement insertStmt;

    @Mock
    private PreparedStatement updateStmt;

    @Captor
    private ArgumentCaptor<String> sqlCaptor;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(dataSource.getConnection()).thenReturn(dbConnection);
        lenient().when(dbConnection.prepareStatement(contains("INSERT"))).thenReturn(insertStmt);
        lenient().when(dbConnection.prepareStatement(contains("UPDATE"))).thenReturn(updateStmt);
    }

    @Test
    void testHandleViewMessage() {
        View view = new View();
        view.setIdx(100L);
        view.setIp("192.168.1.1");
        view.setUserAgent("Mozilla/5.0");
        view.setTs(System.currentTimeMillis() / 1000);

        mqService.handleViewMessage(view);

        var viewCache = getViewCache();
        assertTrue(viewCache.containsKey(100L));
        assertEquals(1, viewCache.get(100L).size());
        assertEquals("192.168.1.1", viewCache.get(100L).get(0).getIp());
    }

    @Test
    void testHandleViewMessage_MultipleViewsForSameIdx() {
        View view1 = new View();
        view1.setIdx(100L);
        view1.setIp("1.1.1.1");
        View view2 = new View();
        view2.setIdx(100L);
        view2.setIp("2.2.2.2");

        mqService.handleViewMessage(view1);
        mqService.handleViewMessage(view2);

        var viewCache = getViewCache();
        assertEquals(2, viewCache.get(100L).size());
    }

    @Test
    void testSyncDB_Success() throws Exception {
        doNothing().when(dbConnection).setAutoCommit(false);
        doNothing().when(dbConnection).commit();

        View view = new View();
        view.setIdx(100L);
        view.setIp("192.168.1.1");
        view.setUserAgent("curl/7.0");
        view.setTs(1234567890L);

        mqService.handleViewMessage(view);
        mqService.syncDB();

        verify(insertStmt).executeBatch();
        verify(updateStmt).executeUpdate();
        verify(dbConnection).commit();
    }

    @Test
    void testSyncDB_EmptyCache() throws Exception {
        mqService.syncDB();
        verify(dataSource).getConnection();
    }

    @Test
    void testSyncDB_RollbackOnError() throws Exception {
        doNothing().when(dbConnection).setAutoCommit(false);
        doThrow(new RuntimeException("DB error")).when(insertStmt).executeBatch();

        View view = new View();
        view.setIdx(100L);
        view.setIp("1.1.1.1");
        view.setTs(100L);

        mqService.handleViewMessage(view);
        mqService.syncDB();

        verify(dbConnection).rollback();
    }

    @Test
    void testHandleViewMessage_MultipleIndices() {
        View v1 = new View();
        v1.setIdx(1L);
        v1.setIp("10.0.0.1");
        View v2 = new View();
        v2.setIdx(2L);
        v2.setIp("10.0.0.2");
        View v3 = new View();
        v3.setIdx(1L);
        v3.setIp("10.0.0.3");

        mqService.handleViewMessage(v1);
        mqService.handleViewMessage(v2);
        mqService.handleViewMessage(v3);

        var viewCache = getViewCache();
        assertEquals(2, viewCache.get(1L).size());
        assertEquals(1, viewCache.get(2L).size());
    }

    @SuppressWarnings("unchecked")
    private Map<Long, java.util.List<View>> getViewCache() {
        try {
            var field = MQService.class.getDeclaredField("viewCacheRef");
            field.setAccessible(true);
            var ref = (AtomicReference<Map<Long, java.util.List<View>>>) field.get(mqService);
            return ref.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
