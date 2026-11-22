package com.cobalto.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record WalletResponse(
    String currency,
    BigDecimal balance,
    BigDecimal availableBalance,
    BigDecimal heldBalance,
    List<MovementResponse> movements
) {}
