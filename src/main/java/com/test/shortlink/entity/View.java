package com.test.shortlink.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "views")
public class View {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id;
    long idx;
    long ts;
    String userAgent;
    String ip;
    public long getId() {
        return id;
    }
    public void setId(long id) {
        this.id = id;
    }
    public long getIdx() {
        return idx;
    }
    public void setIdx(long idx) {
        this.idx = idx;
    }
    public long getTs() {
        return ts;
    }
    public void setTs(long timestamp) {
        this.ts = timestamp;
    }
    public String getUserAgent() {
        return userAgent;
    }
    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }
    public String getIp() {
        return ip;
    }
    public void setIp(String ip) {
        this.ip = ip;
    }

}
