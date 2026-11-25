package io.formkiln.server;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background jobs: webhook delivery, expired draft and abandoned upload cleanup. Disabled with
 * {@code formkiln.scheduling.enabled=false} (tests drive the jobs directly).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "formkiln.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
