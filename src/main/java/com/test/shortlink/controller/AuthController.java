package com.test.shortlink.controller;

import com.test.shortlink.dto.ApiResponse;
import com.test.shortlink.service.AuthService;
import com.test.shortlink.anno.PowCaptcha;
import com.test.shortlink.util.Util;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @PowCaptcha(paramNames={"username", "password"})
    public ApiResponse<Map<String, String>> register(@RequestParam String username, @RequestParam String password,
                                                      @RequestParam String captcha, @RequestParam long time) {
        auth.register(username, password);
        return ApiResponse.success("Registration successful", Map.of("username", username));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@RequestParam String username, @RequestParam String password,
                                                   @RequestParam long time) {
        if (Math.abs(System.currentTimeMillis() / 1000 - time) > Util.captchaExpireSeconds) {
            throw new IllegalArgumentException("Login request expired");
        }
        String token = auth.login(username, password);
        return ApiResponse.success(Map.of("token", token, "username", auth.userFor(token)));
    }

    @PostMapping("/logout")
    public ApiResponse<String> logout(@RequestHeader(value = "Authorization", required = false) String token) {
        auth.logout(token);
        return ApiResponse.success("Logged out");
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, String>> me(@RequestHeader(value = "Authorization", required = false) String token) {
        return ApiResponse.success(Map.of("username", auth.requireUser(token).getUsername()));
    }
}
