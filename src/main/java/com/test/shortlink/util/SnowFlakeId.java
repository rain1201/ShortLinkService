package com.test.shortlink.util;

public class SnowFlakeId {
    private long workerId;
    private long datacenterId;
    private final long epoch;
    private final long maxWorkerId;
    private final long maxDatacenterId;
    private final long sequenceMask;
    private final int workerIdShift;
    private final int datacenterIdShift;
    private final int timestampShift;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowFlakeId(long workerId, long datacenterId) {
        this(workerId, datacenterId, 1609459200000L, 31, 31, 4095);
    }

    public SnowFlakeId(long workerId, long datacenterId, long epoch, long maxWorkerId, long maxDatacenterId, long sequenceMask) {
        this.epoch = epoch;
        this.maxWorkerId = maxWorkerId;
        this.maxDatacenterId = maxDatacenterId;
        this.sequenceMask = sequenceMask;
        if(workerId < 0 || workerId > maxWorkerId) {
            throw new IllegalArgumentException("Worker ID must be between 0 and " + maxWorkerId);
        }
        if(datacenterId < 0 || datacenterId > maxDatacenterId) {
            throw new IllegalArgumentException("Datacenter ID must be between 0 and " + maxDatacenterId);
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
        this.workerIdShift = Long.bitCount(sequenceMask);
        this.datacenterIdShift = workerIdShift + Long.bitCount(maxWorkerId);
        this.timestampShift = datacenterIdShift + Long.bitCount(maxDatacenterId);
    }
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("Clock moved backwards. Refusing to generate id");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & sequenceMask;
            if (sequence == 0) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - epoch) << timestampShift) | (datacenterId << datacenterIdShift) | (workerId << workerIdShift) | sequence;
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
} 