package com.stockpredict.common.plan;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the minimum plan required to invoke a handler. Enforced server-side
 * by {@link PlanEnforcementAspect} from the JWT {@code plan} claim; clients can
 * never raise their own entitlement.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPlan {
    Plan value();
}
