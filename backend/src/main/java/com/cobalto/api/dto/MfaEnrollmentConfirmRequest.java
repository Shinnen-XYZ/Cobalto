package com.cobalto.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record MfaEnrollmentConfirmRequest(
    @NotBlank @Pattern(regexp = "\\d{6}") String code
) {}
