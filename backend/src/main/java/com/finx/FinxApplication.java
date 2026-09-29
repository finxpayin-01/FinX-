package com.finx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;

import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableJpaAuditing
@EnableAsync
@org.springframework.scheduling.annotation.EnableScheduling
public class FinxApplication {

    private static final Logger log = LoggerFactory.getLogger(FinxApplication.class);

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(FinxApplication.class, args);
    }

    private static void loadDotEnv() {
        File[] potentialPaths = new File[]{
                new File(".env"),
                new File("backend/.env"),
                new File("../.env")
        };

        for (File file : potentialPaths) {
            if (file.exists() && file.isFile()) {
                log.info("Loading environment variables from: {}", file.getAbsolutePath());
                try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                            continue;
                        }
                        int eqIdx = line.indexOf('=');
                        String key = line.substring(0, eqIdx).trim();
                        String value = line.substring(eqIdx + 1).trim();

                        if ((value.startsWith("\"") && value.endsWith("\"")) ||
                            (value.startsWith("'") && value.endsWith("'"))) {
                            value = value.substring(1, value.length() - 1);
                        }

                        if (System.getProperty(key) == null) {
                            System.setProperty(key, value);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Could not read .env file at {}: {}", file.getAbsolutePath(), e.getMessage());
                }
                break;
            }
        }
    }
}
