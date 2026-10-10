package com.stockpredict.auth.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.stockpredict.auth.config.AuthJwtProperties;
import com.stockpredict.auth.domain.UserEntity;
import com.stockpredict.common.security.JwtProperties;
import com.stockpredict.common.time.MarketClock;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Issues RS256 access tokens for authenticated users. */
@Component
public class JwtIssuer {

    private final SigningKeyProvider keys;
    private final JwtProperties jwtProperties;
    private final AuthJwtProperties authProperties;
    private final MarketClock clock;

    public JwtIssuer(SigningKeyProvider keys, JwtProperties jwtProperties,
                     AuthJwtProperties authProperties, MarketClock clock) {
        this.keys = keys;
        this.jwtProperties = jwtProperties;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    public String issueAccessToken(UserEntity user) {
        var now = clock.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .issuer(jwtProperties.getIssuer())
                .audience(jwtProperties.getAudience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(authProperties.getAccessTokenTtl())))
                .jwtID(UUID.randomUUID().toString())
                .claim("email", user.getEmail())
                .claim("plan", user.getPlan())
                .claim("roles", List.of(user.getRoles()))
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(keys.activeKey().getKeyID())
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new RSASSASigner(keys.activeKey()));
        } catch (JOSEException ex) {
            throw new IllegalStateException("Unable to sign access token", ex);
        }
        return jwt.serialize();
    }
}
