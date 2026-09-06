package com.test.shortlink.service;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import com.test.shortlink.entity.RedisKeys;

import javax.sql.DataSource;

@Component
public class RedisExpireListener extends KeyExpirationEventMessageListener{
    RedisMessageListenerContainer redisMessageListenerContainer;
    final Logger logger = LoggerFactory.getLogger(RedisExpireListener.class);
    @Autowired
    DataSource dataSource;
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    public RedisExpireListener(RedisMessageListenerContainer listenerContainer) {
        super(listenerContainer);
        this.redisMessageListenerContainer = listenerContainer;
        logger.info("RedisExpireListener initialized and subscribed to key expiration events");
    }
    @Override
    public void doHandleMessage(Message message) {
        String expiredKey = new String(message.getBody());
        if(expiredKey.startsWith(RedisKeys.URL_KEY_PREFIX)) {
            String id = expiredKey.substring(RedisKeys.URL_KEY_PREFIX.length());
            if(id.isEmpty()) return;
            //String viewCountKey = RedisKeys.URL_VIEW_COUNT_KEY_PREFIX + id;
            String expireKey = RedisKeys.URL_EXPIRE_KEY_PREFIX + id;
            //String newView = stringRedisTemplate.opsForValue().getAndDelete(viewCountKey);
            stringRedisTemplate.delete(expireKey);
            /*logger.info("Updating view count for expired key: " + id + " with new view count: " + newView);
            try(var conn = dataSource.getConnection()) {
				var stmt = conn.prepareStatement("UPDATE urls SET view_count = ? WHERE idx = ? AND view_count < ?");
                stmt.setString(1, newView != null ? newView : "0");
                stmt.setString(2, id);
                stmt.setString(3, newView != null ? newView : "0");
                stmt.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }*/
        }
    }
}
