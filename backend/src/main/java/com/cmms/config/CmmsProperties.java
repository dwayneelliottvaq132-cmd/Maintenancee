package com.cmms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cmms")
public record CmmsProperties(String jwtSecret, int jwtHours, String uploadDir, String reportDir, String adminPassword,
                             boolean pmScheduler) {
    /** The placeholder in application.yml. Never used for signing: a random key is generated instead. */
    public static final String DEFAULT_SECRET = "change-me-change-me-change-me-change-me-change-me-0123456789";
}
