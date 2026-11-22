package com.cobalto.api.dto;

import java.util.List;

public record MovementPageResponse(List<MovementResponse> items, int limit, int offset) {}
