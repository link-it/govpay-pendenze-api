package it.govpay.pendenze.security;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.common.auth.spi.AuthType;
import it.govpay.common.auth.spi.AuthenticatedSubject;
import it.govpay.common.auth.spi.GovpayPrincipalLoader;

/**
 * Implementazione di questo servizio della SPI {@link GovpayPrincipalLoader} (libreria
 * condivisa govpay-common-auth) — stesso pattern di {@code ConsoleGovpayPrincipalLoader} in
 * govpay-console-api: cerca l'{@link UtenzaEntity} locale per principal, valida
 * l'abilitazione ed espone {@code passwordHash}/ruoli al verificatore della libreria (che
 * gestisce da sola il confronto della password, hash SHA-512 crypt).
 */
@Component
public class PendenzeGovpayPrincipalLoader implements GovpayPrincipalLoader {

    private final UtenzaRepository utenzaRepository;

    public PendenzeGovpayPrincipalLoader(UtenzaRepository utenzaRepository) {
        this.utenzaRepository = utenzaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public AuthenticatedSubject load(String principal, AuthType authType) {
        UtenzaEntity utenza = utenzaRepository.findByPrincipal(principal).orElse(null);
        if (utenza == null) {
            return null;
        }
        boolean enabled = Boolean.TRUE.equals(utenza.getAbilitato());
        return new AuthenticatedSubject(utenza.getPrincipal(), utenza.getPassword(), enabled,
                parseRoles(utenza.getRuoli()));
    }

    private static List<String> parseRoles(String ruoliCsv) {
        if (ruoliCsv == null || ruoliCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(ruoliCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
