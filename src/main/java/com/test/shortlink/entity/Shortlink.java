package com.test.shortlink.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "urls")
public class Shortlink {
    @Id
    long idx;
    String originalUrl;
    long viewCount;
    long createdAt;
    long expireAfter; 
    @Column(length = 16) 
    String updateCode; 
    public long getIdx() {
        return idx;
    }
    public void setIdx(long idx) {
        this.idx = idx;
    }
    public String getOriginalUrl() {
        return originalUrl;
    }
    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }
    public long getViewCount() {
        return viewCount;
    }
    public void setViewCount(long viewCount) {
        this.viewCount = viewCount;
    }
    public long getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
    public long getExpireAfter() {
        return expireAfter;
    }
    public void setExpireAfter(long expireAfter) {
        this.expireAfter = expireAfter;
    }
    public String getUpdateCode() {
        return updateCode;
    }
    public void setUpdateCode(String updateCode) {
        this.updateCode = updateCode;
    }
    @Override
    public String toString() {
        return "Shortlink [idx=" + idx + ", originalUrl=" + originalUrl + ", viewCount=" + viewCount + ", createdAt="
                + createdAt + ", expireAfter=" + expireAfter + "]";
    }
}
