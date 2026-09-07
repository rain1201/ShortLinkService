package com.test.shortlink.service;

import com.test.shortlink.entity.User;
import com.test.shortlink.entity.RedisKeys;
import com.test.shortlink.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository users;
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();
    @Value("${app.session-expire-seconds:86400}")
    private long sessionExpireSeconds = 86400;

    public AuthService(UserRepository users, StringRedisTemplate redis) {
        this.users = users;
        this.redis = redis;
    }

    public User register(String username, String password) {
        username = cleanUsername(username);
        validatePassword(password);
        if (users.existsByUsername(username))
            throw new IllegalArgumentException("Username already exists");
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(hash(password));
        user.setManagementCode(generateManagementCode());
        user.setCreatedAt(System.currentTimeMillis() / 1000);
        return users.save(user);
    }

    public String login(String username, String password) {
        User user = users.findByUsername(cleanUsername(username))
                .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));
        if (!verify(password, user.getPasswordHash()))
            throw new IllegalArgumentException("Invalid username or password");
        String token = UUID.randomUUID().toString();
        HashOperations<String, String, String> session = redis.opsForHash();
        session.put(sessionKey(token), "username", user.getUsername());
        session.put(sessionKey(token), "managementCode", user.getManagementCode());
        redis.expire(sessionKey(token), java.time.Duration.ofSeconds(sessionExpireSeconds));
        return token;
    }

    public String userFor(String token) {
        return token == null ? null : (String) redis.opsForHash().get(sessionKey(token), "username");
    }

    public String managementCodeFor(String token) {
        return token == null ? null : (String) redis.opsForHash().get(sessionKey(token), "managementCode");
    }

    public void logout(String token) {
        if (token != null)
            redis.delete(sessionKey(token));
    }

    public User requireUser(String token) {
        String username = userFor(token);
        if (username == null)
            throw new IllegalArgumentException("Authentication required");
        return users.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Authentication required"));
    }

    private String cleanUsername(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9_]{3,64}"))
            throw new IllegalArgumentException("Username must be 3-64 letters, numbers or underscores");
        return value;
    }

    private String generateManagementCode() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder code = new StringBuilder("U");
        for (int i = 1; i < 16; i++)
            code.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return code.toString();
    }

    private String sessionKey(String token) {
        return RedisKeys.SESSION_KEY_PREFIX + token.replaceFirst("(?i)^Bearer\\s+", "").trim();
    }

    private void validatePassword(String value) {
        if (value == null || value.length() < 8 || value.length() > 128)
            throw new IllegalArgumentException("Password must be 8-128 characters");
    }

    private String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":"
                + Base64.getEncoder().encodeToString(digest(salt, password));
    }

    private boolean verify(String password, String stored) {
        String[] p = stored.split(":");
        return p.length == 2 && MessageDigest.isEqual(Base64.getDecoder().decode(p[1]),
                digest(Base64.getDecoder().decode(p[0]), password));
    }

    private byte[] digest(byte[] salt, String password) {
        byte[] value = (new String(salt, StandardCharsets.ISO_8859_1) + password).getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < 120000; i++)
            value = sha256(value);
        return value;
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
