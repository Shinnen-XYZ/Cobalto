
package com.cobalto.api.service;

import com.cobalto.api.security.MfaSecretCipher;
import com.cobalto.api.security.TokenHash;
import java.time.Instant;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@Profile("!migrate")
@ConditionalOnProperty(
    name = "cobalto.email-verification.delivery-enabled",
    havingValue = "true"
)
public class EmailVerificationOutboxDispatcher {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(EmailVerificationOutboxDispatcher.class);

    private final EmailVerificationOutboxCoordinator outboxCoordinator;
    private final MfaSecretCipher secretCipher;
    private final JavaMailSender mailSender;
    private final String senderAddress;
    private final String verificationUrl;
    private final Duration tokenLifetime;

    public EmailVerificationOutboxDispatcher(
        EmailVerificationOutboxCoordinator outboxCoordinator,
        MfaSecretCipher secretCipher,
        JavaMailSender mailSender,
        @Value("${cobalto.email-verification.sender-address:cobalto@localhost}")
        String senderAddress,
        @Value("${cobalto.email-verification.frontend-url}")
        String verificationUrl,
        @Value("${cobalto.email-verification.token-lifetime:PT30M}")
        Duration tokenLifetime
    ) {
        this.outboxCoordinator = outboxCoordinator;
        this.secretCipher = secretCipher;
        this.mailSender = mailSender;
        this.senderAddress = senderAddress;
        this.verificationUrl = verificationUrl;
        this.tokenLifetime = tokenLifetime;
    }

    @Scheduled(fixedDelayString = "${cobalto.email-verification.dispatch-delay-ms:5000}")
    public void dispatchPending() {
        for (EmailVerificationOutboxCoordinator.Claim claim
            : outboxCoordinator.claimDue(Instant.now(), 1)) {
            deliver(claim);
        }
    }

    private void deliver(EmailVerificationOutboxCoordinator.Claim claim) {

        Instant now = Instant.now();

        EmailVerificationOutboxCoordinator.Delivery delivery = outboxCoordinator
            .prepareDelivery(claim, now)
            .orElse(null);

        if (delivery == null) {
            outboxCoordinator.cancelClaim(claim, now);
            return;
        }

        String token = secretCipher.decrypt(
            delivery.userId().toString(),
            delivery.encryptedToken()
        );

        if (!TokenHash.sha256(token).equals(delivery.tokenHash())) {
            outboxCoordinator.cancelClaim(claim, Instant.now());
            throw new IllegalStateException(
                "Email verification outbox token hash mismatch"
            );
        }

        String link = UriComponentsBuilder.fromUriString(verificationUrl)
            .fragment("token=" + token)
            .build()
            .encode()
            .toUriString();

        SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(senderAddress);
        message.setTo(delivery.recipientEmail());
        message.setSubject("Verifica tu correo de Cobalto Sandbox");

        message.setText("""
            Para confirmar que controlas esta dirección, abre este enlace:

            %s

            El enlace vence en %s minutos y solo puede utilizarse una vez.
            Si no solicitaste esta cuenta, puedes ignorar este mensaje.
            """.formatted(link, tokenLifetime.toMinutes()));

        if (!outboxCoordinator.renewLeaseForSend(
            claim,
            delivery,
            Instant.now()
        )) {
            outboxCoordinator.cancelClaim(claim, Instant.now());
            return;
        }

        try {

            mailSender.send(message);

            outboxCoordinator.markSent(claim, Instant.now());

            LOGGER.info(
                "Email verification delivered successfully. Item: {}",
                claim.id()
            );

        } catch (MailException exception) {

            int attempt = claim.attempts();

            long delaySeconds = Math.min(
                3600,
                30L << Math.min(attempt - 1, 7)
            );

            outboxCoordinator.recordFailure(
                claim,
                Instant.now().plusSeconds(delaySeconds),
                exception.getClass().getSimpleName(),
                Instant.now()
            );

            LOGGER.error(
                "SMTP DELIVERY ERROR | item={} | attempt={} | type={} | message={}",
                claim.id(),
                attempt,
                exception.getClass().getName(),
                exception.getMessage(),
                exception
            );
        }
    }
}