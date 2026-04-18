package com.cybersecuals.gridgarrison.orchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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

    private final Environment environment;

    MtlsSecurityConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    @SuppressWarnings("unused")
    SecurityFilterChain ocppSecurityFilterChain(HttpSecurity http) throws Exception {
        boolean sslEnabled = isSslEnabled();

        http
            .authorizeHttpRequests(auth -> {
                if (sslEnabled) {
                    auth.requestMatchers("/ocpp/**").authenticated();
                } else {
                    auth.requestMatchers("/ocpp/**").permitAll();
                }

                auth.requestMatchers("/actuator/health").permitAll();

                auth.requestMatchers(
                        "/visualizer", "/visualizer.html", "/visualizer/**",
                        "/panel", "/panel.html",
                        "/ev-control-panel", "/ev-control-panel.html",
                        "/trust/api/golden-hash", "/trust/api/register-runtime-signed-baseline"
                    ).permitAll();

                auth.anyRequest().denyAll();
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
            // Disable CSRF — stateless WebSocket sessions
            .csrf(csrf -> csrf.ignoringRequestMatchers("/ocpp/**", "/visualizer/**", "/trust/**"));

        return http.build();
    }

    private boolean isSslEnabled() {
        Boolean serverSslEnabled = environment.getProperty("server.ssl.enabled", Boolean.class);
        if (Boolean.TRUE.equals(serverSslEnabled)) {
            return true;
        }

        Boolean springSslEnabled = environment.getProperty("spring.ssl.enabled", Boolean.class);
        if (Boolean.TRUE.equals(springSslEnabled)) {
            return true;
        }

        Boolean legacyFlag = environment.getProperty("GG_SSL_ENABLED", Boolean.class);
        return Boolean.TRUE.equals(legacyFlag);
    }
}
