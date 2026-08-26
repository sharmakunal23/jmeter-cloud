package com.perf.globalorchestrator.provision;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Wires a bean only under a given
 * {@link ProvisioningMode}.
 *
 * <p>Composes with {@code @ConditionalOnProperty}: the daemon-backed
 * provisioners carry both (mode {@code DYNAMIC} <em>and</em> their
 * substrate), so exactly one {@link PodProvisioner} bean exists in every
 * valid configuration.
 *
 * <p>A dedicated annotation rather than {@code @ConditionalOnExpression}
 * with a SpEL string: this is evaluated on five classes and read by
 * everyone who touches provisioning, so it should say what it means at
 * the use site and be impossible to typo.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ProvisioningModeCondition.class)
public @interface ConditionalOnProvisioningMode {

    /** The mode this bean requires. */
    ProvisioningMode value();
}
