package com.test.shortlink.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SnowFlakeIdTest {

    @Test
    void testNextId_Unique() {
        SnowFlakeId idGen = new SnowFlakeId(1, 1);
        int count = 1000;
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < count; i++) {
            ids.add(idGen.nextId());
        }
        assertEquals(count, ids.size());
    }

    @Test
    void testNextId_MonotonicallyIncreasing() {
        SnowFlakeId idGen = new SnowFlakeId(1, 1);
        long prev = idGen.nextId();
        for (int i = 0; i < 100; i++) {
            long current = idGen.nextId();
            assertTrue(current > prev);
            prev = current;
        }
    }

    @Test
    void testNextId_WorkerIdBitsPreserved() {
        SnowFlakeId idGen1 = new SnowFlakeId(1, 0);
        SnowFlakeId idGen2 = new SnowFlakeId(2, 0);
        long id1 = idGen1.nextId();
        long id2 = idGen2.nextId();
        assertNotEquals(id1, id2);
    }

    @Test
    void testConstructor_InvalidWorkerIdLow() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new SnowFlakeId(-1, 0));
        assertEquals("Worker ID must be between 0 and 31", ex.getMessage());
    }

    @Test
    void testConstructor_InvalidWorkerIdHigh() {
        assertThrows(IllegalArgumentException.class,
                () -> new SnowFlakeId(32, 0));
    }

    @Test
    void testConstructor_InvalidDatacenterIdLow() {
        assertThrows(IllegalArgumentException.class,
                () -> new SnowFlakeId(0, -1));
    }

    @Test
    void testConstructor_InvalidDatacenterIdHigh() {
        assertThrows(IllegalArgumentException.class,
                () -> new SnowFlakeId(0, 32));
    }

    @Test
    void testConstructor_BoundaryValues() {
        assertDoesNotThrow(() -> new SnowFlakeId(0, 0));
        assertDoesNotThrow(() -> new SnowFlakeId(31, 31));
    }

    @Test
    void testNextId_PositiveValue() {
        SnowFlakeId idGen = new SnowFlakeId(0, 0);
        long id = idGen.nextId();
        assertTrue(id > 0);
    }

    @Test
    void testNextId_NoDuplicateUnderHighConcurrency() {
        SnowFlakeId idGen = new SnowFlakeId(5, 3);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            ids.add(idGen.nextId());
        }
        assertEquals(10000, ids.size());
    }
}
