package com.stockpredict.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.common.error.GlobalExceptionHandler;
import com.stockpredict.common.plan.PlanEnforcementAspect;
import com.stockpredict.common.security.JwtAuthenticationFilter;
import com.stockpredict.common.security.JwtProperties;
import com.stockpredict.common.security.JwtVerifier;
import com.stockpredict.common.security.ProblemAccessDeniedHandler;
import com.stockpredict.common.security.ProblemAuthenticationEntryPoint;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.common.web.RequestIdFilter;
import java.net.URI;
import java.time.Clock;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/** Auto-configures the shared servlet-side components from common-lib. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(JwtProperties.class)
public class CommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MarketClock marketClock() {
        return new MarketClock();
    }

    @Bean
    @ConditionalOnMissingBean
    public TradingCalendar tradingCalendar() {
        return new TradingCalendar();
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "stockpredict.jwt", name = "jwks-uri")
    public JwtVerifier jwtVerifier(JwtProperties properties) {
        return new JwtVerifier(URI.create(properties.getJwksUri()),
                properties.getIssuer(), properties.getAudience(),
                Clock.systemUTC(), properties.getClockSkew());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JwtVerifier.class)
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtVerifier verifier, ObjectMapper objectMapper) {
        return new JwtAuthenticationFilter(verifier, objectMapper);
    }

    /** Keep the filter out of the servlet chain; services add it to their SecurityFilterChain. */
    @Bean
    @ConditionalOnBean(JwtAuthenticationFilter.class)
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(Aspect.class)
    public PlanEnforcementAspect planEnforcementAspect() {
        return new PlanEnforcementAspect();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new ProblemAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProblemAccessDeniedHandler problemAccessDeniedHandler(ObjectMapper objectMapper) {
        return new ProblemAccessDeniedHandler(objectMapper);
    }
}
