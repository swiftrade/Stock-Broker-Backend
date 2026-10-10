package com.stockpredict.common.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.error.ErrorCode;
import com.stockpredict.common.security.JwtClaims;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class PlanEnforcementAspectTest {

    public static class PremiumFeature {
        @RequiresPlan(Plan.PREMIUM)
        public String premiumOnly() {
            return "ok";
        }

        @RequiresPlan(Plan.FREE)
        public String freeFeature() {
            return "free";
        }
    }

    private PremiumFeature proxy;

    @BeforeEach
    void setUp() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new PremiumFeature());
        factory.addAspect(new PlanEnforcementAspect());
        proxy = factory.getProxy();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(Plan plan) {
        JwtClaims claims = new JwtClaims("user-1", "u@example.com", plan, Set.of("USER"),
                Instant.now(), Instant.now().plusSeconds(900), Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims, null, Set.of()));
    }

    @Test
    void premiumUserCanUsePremiumFeature() {
        authenticate(Plan.PREMIUM);
        assertThat(proxy.premiumOnly()).isEqualTo("ok");
    }

    @Test
    void freeUserIsBlockedFromPremiumFeature() {
        authenticate(Plan.FREE);
        assertThatThrownBy(proxy::premiumOnly)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.PLAN_LIMIT);
    }

    @Test
    void freeUserCanUseFreeFeature() {
        authenticate(Plan.FREE);
        assertThat(proxy.freeFeature()).isEqualTo("free");
    }

    @Test
    void anonymousIsRejected() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(proxy::premiumOnly)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
