package com.cobalto.api.controller;

import com.cobalto.api.dto.UserResponse;
import com.cobalto.api.service.AuthService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

@RestController
@Profile("!migrate")
@RequestMapping("/api/me")
public class UserController {
    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping
    public UserResponse getProfile(@AuthenticationPrincipal UUID userId) {
        return authService.getProfile(userId);
    }
}
