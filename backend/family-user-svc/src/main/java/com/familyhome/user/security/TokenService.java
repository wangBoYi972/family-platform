package com.familyhome.user.security;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.user.config.AuthProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    @Autowired
    private AuthProperties properties;

    public String createToken(
            long userId, long householdId, String role, String sessionId, String client) {
        return createToken(
                userId,
                householdId,
                role,
                sessionId,
                client,
                Instant.now().plusSeconds(properties.getTokenExpirationMinutes() * 60));
    }

    public String createToken(
            long userId,
            long householdId,
            String role,
            String sessionId,
            String client,
            Instant expiresAt) {
        var key = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer("family-home")
                .subject(Long.toString(userId))
                .id(sessionId)
                .claim("householdId", householdId)
                .claim("role", role)
                .claim("client", client)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
