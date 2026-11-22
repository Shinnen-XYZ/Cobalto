package com.cobalto.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaDisableRequest(
    @NotBlank @Size(max = 128) String password,
    @Size(max = 6) String code,
    @Size(max = 40) String recoveryCode
) {}
