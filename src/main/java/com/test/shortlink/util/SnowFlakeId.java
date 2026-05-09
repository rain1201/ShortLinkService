package com.test.shortlink.util;

public class SnowFlakeId {
    private long workerId;
    private long datacenterId;
    private final long epoch = 1609459200000L; // 2021-01-01 00:00:00 UTC
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowFlakeId(long workerId, long datacenterId) {
        if(workerId < 0 || workerId > 31) {
            throw new IllegalArgumentException("Worker ID must be between 0 and 31");
        }
        if(datacenterId < 0 || datacenterId > 31) {
            throw new IllegalArgumentException("Datacenter ID must be between 0 and 31");
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("Clock moved backwards. Refusing to generate id");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & 4095; // 12 bits for sequence
            if (sequence == 0) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - epoch) << 22) | (datacenterId << 17) | (workerId << 12) | sequence;
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
} 