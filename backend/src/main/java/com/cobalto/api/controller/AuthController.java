package com.cobalto.api.controller;

import com.cobalto.api.dto.AuthResponse;
import com.cobalto.api.dto.EmailVerificationConfirmRequest;
import com.cobalto.api.dto.EmailVerificationResponse;
import com.cobalto.api.dto.LoginRequest;
import com.cobalto.api.dto.MfaDisableRequest;
import com.cobalto.api.dto.MfaEnrollmentConfirmRequest;
import com.cobalto.api.dto.MfaEnrollmentRequest;
import com.cobalto.api.dto.MfaEnrollmentResponse;
import com.cobalto.api.dto.MfaSetupResponse;
import com.cobalto.api.dto.MfaVerificationRequest;
import com.cobalto.api.dto.RefreshRequest;
import com.cobalto.api.dto.RegisterRequest;
import com.cobalto.api.service.AuthService;
import com.cobalto.api.service.EmailVerificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

@RestController
@Profile("!migrate")
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;

    public AuthController(AuthService authService, EmailVerificationService emailVerificationService) {
        this.authService = authService;
        this.emailVerificationService = emailVerificationService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/email-verification/confirm")
    public EmailVerificationResponse confirmEmail(
        @Valid @RequestBody EmailVerificationConfirmRequest request
    ) {
        emailVerificationService.confirm(request.token());
        return new EmailVerificationResponse(true, "El correo fue verificado.");
    }

    @PostMapping("/email-verification/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EmailVerificationResponse resendEmail(
        @AuthenticationPrincipal UUID userId
    ) {
        return emailVerificationService.resend(userId);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request);
    }

    @PostMapping("/mfa/verify")
    public AuthResponse verifyMfaLogin(@Valid @RequestBody MfaVerificationRequest request) {
        return authService.verifyMfaLogin(request);
    }

    @PostMapping("/mfa/enrollment")
    public MfaSetupResponse beginMfaEnrollment(
        @AuthenticationPrincipal UUID userId,
        @Valid @RequestBody MfaEnrollmentRequest request
    ) {
        return authService.beginMfaEnrollment(userId, request);
    }

    @PostMapping("/mfa/enrollment/confirm")
    public MfaEnrollmentResponse confirmMfaEnrollment(
        @AuthenticationPrincipal UUID userId,
        @Valid @RequestBody MfaEnrollmentConfirmRequest request
    ) {
        return authService.confirmMfaEnrollment(userId, request);
    }

    @DeleteMapping("/mfa/enrollment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableMfa(
        @AuthenticationPrincipal UUID userId,
        @Valid @RequestBody MfaDisableRequest request
    ) {
        authService.disableMfa(userId, request);
    }
}
