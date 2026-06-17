package com.legacy.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
// 분석 대상 파일명: LegacyAnalyzerApplication.java

@SpringBootApplication(scanBasePackages = "com.legacy")
@EnableJpaRepositories(basePackages = "com.legacy")
@EntityScan(basePackages = "com.legacy")
public class LegacyAnalyzerApplication {
    public static void main(String[] args) {
        SpringApplication.run(LegacyAnalyzerApplication.class, args);
    }
}
