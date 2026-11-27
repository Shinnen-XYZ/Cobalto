package com.cobalto.api.service;

import com.cobalto.api.domain.AccountType;
import com.cobalto.api.domain.AppUser;
import com.cobalto.api.domain.LedgerAccount;
import com.cobalto.api.dto.AuthResponse;
import com.cobalto.api.dto.EmailChangeRequest;
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
import com.cobalto.api.dto.UserResponse;
import com.cobalto.api.exception.DomainException;
import com.cobalto.api.repository.AppUserRepository;
import com.cobalto.api.repository.LedgerAccountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!migrate")
public class AuthService {
    public static final String DEFAULT_CURRENCY = "GTQ";
    private static final UUID SANDBOX_ISSUER_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final BigDecimal SANDBOX_STARTING_BALANCE = new BigDecimal("100.00");

    private final AppUserRepository userRepository;
    private final LedgerAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService sessionService;
    private final MfaService mfaService;
    private final LedgerService ledgerService;
    private final UserPermissionService permissionService;
    private final EmailVerificationService emailVerificationService;
    private final EntityManager entityManager;

    public AuthService(AppUserRepository userRepository, LedgerAccountRepository accountRepository,
                       PasswordEncoder passwordEncoder, AuthSessionService sessionService,
                       MfaService mfaService, LedgerService ledgerService,
                       UserPermissionService permissionService,
                       EmailVerificationService emailVerificationService,
                       EntityManager entityManager) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
        this.mfaService = mfaService;
        this.ledgerService = ledgerService;
        this.permissionService = permissionService;
        this.emailVerificationService = emailVerificationService;
        this.entityManager = entityManager;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DomainException(HttpStatus.CONFLICT, "EMAIL_IN_USE",
                "Ya existe una cuenta con ese correo");
        }

        AppUser user;
        try {
            user = userRepository.saveAndFlush(new AppUser(
                request.fullName().trim(), email, passwordEncoder.encode(request.password())));
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniqueViolation(exception)) {
                throw emailInUse();
            }
            throw exception;
        }
        LedgerAccount wallet = accountRepository.saveAndFlush(new LedgerAccount(
            UUID.randomUUID(), AccountType.WALLET, user.getId(), DEFAULT_CURRENCY));
        LedgerAccount sandboxIssuer = accountRepository.findById(SANDBOX_ISSUER_ID)
            .orElseThrow(() -> new IllegalStateException("Falta la cuenta de emisión sandbox"));
        ledgerService.post("SANDBOX_CREDIT", user.getId(), "signup-" + user.getId(), null,
            DEFAULT_CURRENCY, "Saldo inicial de prueba", sandboxIssuer, wallet,
            SANDBOX_STARTING_BALANCE);
        emailVerificationService.issue(user);
        return authResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        AppUser user = userRepository.findByEmail(email)
            .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
            .orElseThrow(() -> new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "Correo o contraseña incorrectos"));
        if (user.isMfaEnabled()) {
            return AuthResponse.mfaChallenge(mfaService.createLoginChallenge(user));
        }
        return authResponse(user);
    }

    @Transactional(noRollbackFor = DomainException.class)
    public AuthResponse verifyMfaLogin(MfaVerificationRequest request) {
        AppUser user = mfaService.verifyLoginChallenge(
            request.challenge(), request.code(), request.recoveryCode());
        return authResponse(user);
    }

    @Transactional(noRollbackFor = DomainException.class)
    public AuthResponse refresh(RefreshRequest request) {
        AuthSessionService.SessionTokens tokens = sessionService.rotate(request.refreshToken());
        return authResponse(tokens.user(), tokens);
    }

    @Transactional
    public void logout(RefreshRequest request) {
        sessionService.revoke(request.refreshToken());
    }

    @Transactional
    public MfaSetupResponse beginMfaEnrollment(UUID userId, MfaEnrollmentRequest request) {
        AppUser user = userRepository.findById(userId)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                "No se encontró el usuario"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "La contraseña no es válida");
        }
        return mfaService.beginEnrollment(userId);
    }

    @Transactional(noRollbackFor = DomainException.class)
    public MfaEnrollmentResponse confirmMfaEnrollment(
        UUID userId,
        MfaEnrollmentConfirmRequest request
    ) {
        List<String> recoveryCodes = mfaService.confirmEnrollment(userId, request.code());
        sessionService.revokeAllForUser(userId);
        AppUser user = userRepository.findById(userId)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                "No se encontró el usuario"));
        AuthSessionService.SessionTokens tokens = sessionService.create(user);
        return new MfaEnrollmentResponse(true, recoveryCodes, authenticatedResponse(user, tokens));
    }

    @Transactional(noRollbackFor = DomainException.class)
    public void disableMfa(UUID userId, MfaDisableRequest request) {
        AppUser user = userRepository.findById(userId)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                "No se encontró el usuario"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "La contraseña no es válida");
        }
        mfaService.disable(userId, request.code(), request.recoveryCode());
        sessionService.revokeAllForUser(userId);
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile(UUID userId) {
        AppUser user = userRepository.findById(userId)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                "No se encontró el usuario"));
        return toUserResponse(user);
    }

    @Transactional
    public EmailVerificationResponse changeEmail(UUID userId, EmailChangeRequest request) {
        AppUser user = userRepository.findById(userId)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                "No se encontró el usuario"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "La contraseña no es válida");
        }

        boolean mfaRequired = user.isMfaEnabled();
        if (mfaRequired) {
            mfaService.verifySensitiveAction(userId, request.mfaFactor());
        } else if (request.mfaFactor() != null && !request.mfaFactor().isBlank()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "MFA_NOT_ENABLED",
                "Esta cuenta no tiene MFA activado");
        }

        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (user.isMfaEnabled() != mfaRequired) {
            throw new DomainException(HttpStatus.CONFLICT, "MFA_CONFIGURATION_CHANGED",
                "La configuración multifactor cambió; inténtalo nuevamente");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "La contraseña no es válida");
        }

        String email = normalizeEmail(request.email());
        if (email.equals(user.getEmail())) {
            return new EmailVerificationResponse(user.isEmailVerified(),
                user.isEmailVerified()
                    ? "El correo ya está verificado."
                    : "La verificación del correo sigue pendiente.");
        }
        if (userRepository.existsByEmail(email)) {
            throw emailInUse();
        }

        user.changeEmail(email);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniqueViolation(exception)) {
                throw emailInUse();
            }
            throw exception;
        }
        entityManager.refresh(user);
        if (user.isEmailVerified() || user.getEmailVerifiedAt() != null) {
            throw new IllegalStateException(
                "PostgreSQL no invalidó la verificación después del cambio de correo");
        }
        emailVerificationService.issue(user);
        sessionService.revokeAllForUser(userId);
        return new EmailVerificationResponse(
            false, "Se inició la verificación de la nueva dirección de correo.");
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public UserResponse toUserResponse(AppUser user) {
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(),
            "NOT_VERIFIED", user.getCreatedAt(), user.isEmailVerified(), user.isMfaEnabled(),
            permissionService.hasPermission(user.getId(), UserPermissionService.TRANSFER_REVERSAL));
    }

    private static DomainException emailInUse() {
        return new DomainException(HttpStatus.CONFLICT, "EMAIL_IN_USE",
            "Ya existe una cuenta con ese correo");
    }

    private static boolean isEmailUniqueViolation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                && "app_users_email_key".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private AuthResponse authResponse(AppUser user) {
        return authResponse(user, sessionService.create(user));
    }

    private AuthResponse authResponse(AppUser user, AuthSessionService.SessionTokens tokens) {
        return authenticatedResponse(user, tokens);
    }

    private AuthResponse authenticatedResponse(
        AppUser user,
        AuthSessionService.SessionTokens tokens
    ) {
        return AuthResponse.authenticated(tokens.accessToken(), tokens.refreshToken(),
            tokens.expiresInSeconds(), toUserResponse(user));
    }
}
