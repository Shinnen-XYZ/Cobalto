package com.cobalto.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaVerificationRequest(
    @NotBlank @Size(max = 64) String challenge,
    @Size(max = 6) String code,
    @Size(max = 40) String recoveryCode
) {}
