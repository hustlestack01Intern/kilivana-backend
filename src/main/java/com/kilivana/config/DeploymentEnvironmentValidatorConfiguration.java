package com.kilivana.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class DeploymentEnvironmentValidatorConfiguration {

    /**
     * Validated in the constructor so the check runs while the context is still being
     * assembled, before anything accepts traffic. Inert unless {@code prod} is active, so
     * local runs and the test suite are unaffected.
     */
    public DeploymentEnvironmentValidatorConfiguration(Environment environment) {
        DeploymentEnvironmentValidator validator = new DeploymentEnvironmentValidator(environment);
        if (validator.applies()) {
            validator.validate();
        }
    }
}
