package com.perf.globalorchestrator.provision;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * Backs {@link ConditionalOnProvisioningMode}. Matches when the required
 * mode equals the deployment's resolved {@link ProvisioningMode}.
 *
 * <p>An unparseable {@code PROVISIONING_MODE} propagates out of
 * {@link ProvisioningMode#parse} and fails the context refresh — the
 * deliberate choice over defaulting, since a typo would otherwise arm the
 * provisioner against a fleet the operator believes is protected.
 */
public class ProvisioningModeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes =
                metadata.getAnnotationAttributes(ConditionalOnProvisioningMode.class.getName());
        if (attributes == null) {
            // Condition reached without the annotation — nothing to require.
            return true;
        }
        Object raw = attributes.get("value");
        // Spring resolves enum attributes to constants for both reflection- and
        // ASM-based metadata, but component scanning uses the ASM path; fall back
        // to the name so this can't break on a metadata-representation change.
        ProvisioningMode required = (raw instanceof ProvisioningMode mode)
                ? mode
                : ProvisioningMode.parse(String.valueOf(raw));
        ProvisioningMode actual =
                ProvisioningMode.parse(context.getEnvironment().getProperty(ProvisioningMode.PROPERTY));
        return required == actual;
    }
}
