package com.cmms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cmms")
public record CmmsProperties(String jwtSecret, int jwtHours, String uploadDir, String reportDir, String adminPassword) {}
