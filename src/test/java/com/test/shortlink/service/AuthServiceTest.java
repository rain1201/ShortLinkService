package com.test.shortlink.service;

import com.test.shortlink.entity.User;
import com.test.shortlink.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock UserRepository users;
    @Mock StringRedisTemplate redis;
    @Mock HashOperations<String, Object, Object> session;

    @Test void registerAndLoginRoundTrip() {
        when(users.existsByUsername("alice")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        User user = new User();
        user.setUsername("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(redis.opsForHash()).thenReturn(session);
        when(session.get(anyString(), anyString())).thenReturn("alice");
        AuthService service = new AuthService(users, redis);
        service.register("alice", "correct horse");
        user.setPasswordHash(capturedHash(users));
        String token = service.login("alice", "correct horse");
        assertEquals("alice", service.userFor(token));
        assertThrows(IllegalArgumentException.class, () -> service.login("alice", "wrong password"));
    }

    @Test void validatesInputAndDuplicate() {
        AuthService service = new AuthService(users, redis);
        assertThrows(IllegalArgumentException.class, () -> service.register("ab", "12345678"));
        assertThrows(IllegalArgumentException.class, () -> service.register("alice", "short"));
        when(users.existsByUsername("alice")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.register("alice", "12345678"));
    }

    private String capturedHash(UserRepository repository) {
        org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(repository).save(captor.capture());
        return captor.getValue().getPasswordHash();
    }
}
