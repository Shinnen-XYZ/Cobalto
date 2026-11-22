package com.cobalto.api.controller;

import com.cobalto.api.dto.MovementPageResponse;
import com.cobalto.api.dto.ReversalRequest;
import com.cobalto.api.dto.RecipientConfirmationResponse;
import com.cobalto.api.dto.TransferReceiptResponse;
import com.cobalto.api.dto.TransferRequest;
import com.cobalto.api.dto.TransferResponse;
import com.cobalto.api.dto.WalletResponse;
import com.cobalto.api.service.WalletService;
import com.cobalto.api.service.TransferReversalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!migrate")
@Validated
@RequestMapping("/api")
public class WalletController {
    private final WalletService walletService;
    private final TransferReversalService reversalService;

    public WalletController(WalletService walletService, TransferReversalService reversalService) {
        this.walletService = walletService;
        this.reversalService = reversalService;
    }

    @GetMapping("/wallet")
    public WalletResponse getWallet(@AuthenticationPrincipal UUID userId) {
        return walletService.getWallet(userId);
    }

    @GetMapping("/wallet/movements")
    public MovementPageResponse listMovements(
        @AuthenticationPrincipal UUID userId,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return new MovementPageResponse(
            walletService.listMovements(userId, limit, offset), limit, offset);
    }

    @GetMapping("/recipients/confirm")
    public RecipientConfirmationResponse confirmRecipient(
        @AuthenticationPrincipal UUID userId,
        @RequestParam @NotBlank @Email @Size(max = 254) String email
    ) {
        return walletService.confirmRecipient(userId, email);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferReceiptResponse transfer(
        @AuthenticationPrincipal UUID userId,
        @RequestHeader("Idempotency-Key") UUID idempotencyKey,
        @Valid @RequestBody TransferRequest request
    ) {
        TransferResponse transfer = walletService.transfer(userId, idempotencyKey, request);
        return walletService.getTransferDetails(userId, transfer.transactionId());
    }

    @GetMapping("/transfers/{transactionId}")
    public TransferReceiptResponse getTransferDetails(
        @AuthenticationPrincipal UUID userId,
        @PathVariable UUID transactionId
    ) {
        return walletService.getTransferDetails(userId, transactionId);
    }

    @PostMapping("/transfers/{transactionId}/reversal")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferReceiptResponse reverseTransfer(
        @AuthenticationPrincipal UUID userId,
        @PathVariable UUID transactionId,
        @RequestHeader("Idempotency-Key") UUID idempotencyKey,
        @Valid @RequestBody ReversalRequest request
    ) {
        var reversal = reversalService.reverse(userId, transactionId, idempotencyKey, request);
        return walletService.getTransferDetails(userId, reversal.getId());
    }
}
