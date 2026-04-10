package com.cybersecuals.gridgarrison.orchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Enforces Mutual TLS (mTLS) for all OCPP WebSocket connections.
 *
 * Both EV and Charging Station MUST present a valid X.509 certificate
 * signed by the GridGarrison root CA. Certificate CN is extracted and
 * mapped to the station/EV identity for downstream modules.
 *
 * Server-side TLS config (keystore / truststore) lives in
 * {@code application.yml} under {@code server.ssl.*}.
 */
@Configuration
@EnableWebSecurity
class MtlsSecurityConfig {

    @Bean
    SecurityFilterChain ocppSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            // All /ocpp/** endpoints require a verified client certificate
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/ocpp/**").authenticated()
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().denyAll()
            )
            // x509 extracts the CN from the client cert as the principal
            .x509(x509 -> x509
                .subjectPrincipalRegex("CN=(.*?)(?:,|$)")
                .userDetailsService(certCn -> {
                    // In production: look up station/EV in identity registry
                    // For now: accept any cert signed by the trusted CA
                    return org.springframework.security.core.userdetails.User
                        .withUsername(certCn)
                        .password("")
                        .roles("STATION")
                        .build();
                })
            )
            // Disable CSRF — stateless WebSocket sessions
            .csrf(csrf -> csrf.ignoringRequestMatchers("/ocpp/**"));

        return http.build();
    }
}
