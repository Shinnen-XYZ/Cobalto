package com.cobalto.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountType accountType;

    @Column(unique = true)
    private UUID ownerUserId;

    @Column(nullable = false, length = 3)
    private String currency;

    protected LedgerAccount() {}

    public LedgerAccount(UUID id, AccountType accountType, UUID ownerUserId, String currency) {
        this.id = id;
        this.accountType = accountType;
        this.ownerUserId = ownerUserId;
        this.currency = currency;
    }

    public UUID getId() { return id; }
    public AccountType getAccountType() { return accountType; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public String getCurrency() { return currency; }
}
