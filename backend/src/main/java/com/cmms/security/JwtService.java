package com.cmms.security;

import com.cmms.config.CmmsProperties;
import com.cmms.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    /** Claim marking a short-lived token that may only be used to download one file. */
    public static final String DOWNLOAD_CLAIM = "dl";

    private final SecretKey key;
    private final int hours;

    public JwtService(CmmsProperties props) {
        String secret = props.jwtSecret();
        byte[] bytes;
        if (secret == null || secret.isBlank() || CmmsProperties.DEFAULT_SECRET.equals(secret)) {
            // Never sign with the public placeholder: anyone could forge tokens. Use a random key instead
            // (everyone is logged out when the server restarts until CMMS_JWT_SECRET is set).
            bytes = new byte[64];
            new SecureRandom().nextBytes(bytes);
            log.warn("CMMS_JWT_SECRET is not set - using a random signing key. Set it in the settings file so logins survive restarts.");
        } else {
            bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) throw new IllegalStateException("CMMS_JWT_SECRET must be at least 32 characters");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.hours = props.jwtHours();
    }

    public String issue(User u) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(u.id))
                .claim("username", u.username)
                .claim("role", u.role.name())
                .claim("tv", u.tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(hours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    /** A 2-minute token that only works as ?access_token= on a file download URL. */
    public String issueDownload(User u) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(u.id))
                .claim("tv", u.tokenVersion)
                .claim(DOWNLOAD_CLAIM, true)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(2, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
