package com.stockpredict.common.plan;

import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.error.ErrorCode;
import com.stockpredict.common.security.JwtClaims;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Enforces {@link RequiresPlan} against the authenticated JWT claims. */
@Aspect
public class PlanEnforcementAspect {

    @Around("@annotation(requiresPlan)")
    public Object enforce(ProceedingJoinPoint joinPoint, RequiresPlan requiresPlan) throws Throwable {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof JwtClaims claims)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        if (!claims.plan().allows(requiresPlan.value())) {
            throw new ApiException(ErrorCode.PLAN_LIMIT,
                    "This feature requires the " + requiresPlan.value() + " plan");
        }
        return joinPoint.proceed();
    }
}
