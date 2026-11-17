package com.cmms.security;

import com.cmms.config.CmmsProperties;
import com.cmms.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey key;
    private final int hours;

    public JwtService(CmmsProperties props) {
        byte[] bytes = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("CMMS_JWT_SECRET must be at least 32 characters");
        this.key = Keys.hmacShaKeyFor(bytes);
        this.hours = props.jwtHours();
    }

    public String issue(User u) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(u.id))
                .claim("username", u.username)
                .claim("role", u.role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(hours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
