package com.test.shortlink.controller;

import com.test.shortlink.entity.User;
import com.test.shortlink.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {
    @Mock AuthService auth;

    @Test void loginRejectsExpiredTimestamp() {
        AuthController controller = new AuthController(auth);
        assertThrows(IllegalArgumentException.class, () -> controller.login("alice", "password", 1));
    }

    @Test void registerDelegatesAfterPowParametersAreAccepted() {
        AuthController controller = new AuthController(auth);
        when(auth.register("alice", "password123")).thenReturn(new User());
        assertEquals("alice", controller.register("alice", "password123", "nonce", System.currentTimeMillis() / 1000).getData().get("username"));
    }
}
