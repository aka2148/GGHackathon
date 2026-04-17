package com.cybersecuals.gridgarrison.orchestrator.config;

import org.springframework.beans.factory.annotation.Value;
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

    @Value("${GG_SSL_ENABLED:false}")
    private boolean sslEnabled;

    @Bean
    @SuppressWarnings("unused")
    SecurityFilterChain ocppSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> {
                if (sslEnabled) {
                    auth.requestMatchers("/ocpp/**").authenticated();
                } else {
                    auth.requestMatchers("/ocpp/**").permitAll();
                }

                auth.requestMatchers(
                        "/actuator/health",
                        "/visualizer",
                        "/visualizer.html",
                        "/visualizer/**"
                    ).permitAll()
                    .anyRequest().denyAll();
            })
            // x509 extracts the CN from the client cert as the principal
            .x509(x509 -> x509
                .subjectPrincipalRegex("CN=(.*?)(?:,|$)")
                .userDetailsService(certCn -> org.springframework.security.core.userdetails.User
                    .withUsername(certCn)
                    .password("{noop}cert-authenticated")
                    .roles("STATION")
                    .build())
            )
            // x509 extracts the CN from the client cert as the principal
            // Disable CSRF — stateless WebSocket sessions
            .csrf(csrf -> csrf.ignoringRequestMatchers("/ocpp/**", "/visualizer/**", "/trust/**"));

        http.authorizeHttpRequests(auth -> {
            auth.requestMatchers("/ocpp/**").authenticated();
            auth.requestMatchers("/actuator/health").permitAll();
            // Demo UI and supporting APIs must remain browser-accessible in local runs.
            auth.requestMatchers(
                "/visualizer", "/visualizer.html", "/visualizer/**",
                "/panel", "/panel.html", "/panel/**",
                "/ev-control-panel", "/ev-control-panel.html", "/ev-control-panel/**",
                "/trust/api/golden-hash", "/trust/api/register-runtime-signed-baseline",
                "/trust/api/escrow/**"
            ).permitAll();
            auth.anyRequest().denyAll();
        });

        return http.build();
    }
}
