package com.legacy.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.legacy")
@EnableJpaRepositories(basePackages = "com.legacy")
@EntityScan(basePackages = "com.legacy")
@EnableScheduling
public class LegacyAnalyzerApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(LegacyAnalyzerApplication.class);
        app.addListeners(new DatasourceAutoSelector());
        app.run(args);
    }
}
