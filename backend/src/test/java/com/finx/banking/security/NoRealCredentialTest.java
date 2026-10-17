package com.finx.banking.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class NoRealCredentialTest {

    private static final List<String> FORBIDDEN_PATTERNS = List.of(
            "-----BEGIN PRIVATE KEY-----",
            "-----BEGIN RSA PRIVATE KEY-----",
            "-----BEGIN EC PRIVATE KEY-----",
            "-----BEGIN ENCRYPTED PRIVATE KEY-----"
    );

    @Test
    @DisplayName("Source and resource directories must not contain real committed private keys")
    void testNoCommittedPrivateKeys() throws IOException {
        Path srcMain = Path.of("src/main");
        if (!Files.exists(srcMain)) {
            // If running from repo root
            srcMain = Path.of("backend/src/main");
        }
        assertThat(srcMain).exists();

        try (Stream<Path> paths = Files.walk(srcMain)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String content = Files.readString(path);
                    for (String pattern : FORBIDDEN_PATTERNS) {
                        assertThat(content)
                                .as("File " + path + " must not contain pattern: " + pattern)
                                .doesNotContain(pattern);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read: " + path, e);
                }
            });
        }
    }
}
