package com.test.shortlink.entity;

public class RedisKeys {
    public static final String URL_KEY_PREFIX = "shortlink:url:";
    public static final String URL_VIEW_COUNT_KEY_PREFIX = "shortlink:viewCount:";
    public static final String URL_EXPIRE_KEY_PREFIX = "shortlink:expire:";
    public static final String URL_DB_LOCK_KEY_PREFIX = "shortlink:dblock:";
    public static final String URL_CREATE_LOCK_KEY_PREFIX = "shortlink:createLock:"; 
    public static final String URL_CACHE_LIST = "shortlink:cacheList";
    public static final String URL_VIEW_MQ = "shortlink:view:mq";
}
