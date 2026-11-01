package info.trizub.clamav.webclient.configuration;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Runs before DataSource, Flyway, servlet settings, identity and webhook binding. */
public final class ConfigurationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER+1; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment,SpringApplication application) {
        ConfigurationSource.install(environment);
    }
}
