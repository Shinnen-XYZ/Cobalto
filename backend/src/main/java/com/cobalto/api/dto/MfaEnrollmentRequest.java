package com.cobalto.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaEnrollmentRequest(@NotBlank @Size(max = 128) String password) {}
