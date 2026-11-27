package com.cobalto.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EmailVerificationConfirmRequest(
    @NotBlank @Size(min = 43, max = 43) @Pattern(regexp = "^[A-Za-z0-9_-]{43}$")
    String token
) {}
