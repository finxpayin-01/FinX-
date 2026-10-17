package com.finx.banking.transport;

import com.finx.banking.config.BankingPartnerProperties;
import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankingWebClientFactoryTest {

    private final BankingWebClientFactory factory = new BankingWebClientFactory();

    private void generateEphemeralKeystore(Path keystorePath, String password, String alias) throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home"));
        String exeName = System.getProperty("os.name", "").toLowerCase().contains("win") ? "keytool.exe" : "keytool";
        Path keytool = javaHome.resolve("bin").resolve(exeName);
        ProcessBuilder pb = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias", alias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-storetype", "PKCS12",
                "-keystore", keystorePath.toAbsolutePath().toString(),
                "-storepass", password,
                "-keypass", password,
                "-validity", "1",
                "-dname", "CN=localhost"
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            String output = new String(proc.getInputStream().readAllBytes());
            throw new IllegalStateException("Failed to generate test keystore: " + output);
        }
    }

    @Test
    @DisplayName("Factory rejects missing keystore file")
    void testRejectsMissingKeystore() {
        BankingPartnerProperties.MtlsProperties mtls = new BankingPartnerProperties.MtlsProperties();
        mtls.setEnabled(true);
        mtls.setKeyStorePath("file:///non-existent-keystore.p12");
        mtls.setKeyStorePassword("wrong-pass");
        mtls.setTrustStorePath("file:///non-existent-truststore.p12");
        mtls.setTrustStorePassword("wrong-pass");

        assertThatThrownBy(() -> factory.createSslContext(mtls))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("file not found");
    }

    @Test
    @DisplayName("Factory rejects disabled mTLS when createSslContext invoked")
    void testRejectsDisabledMtls() {
        BankingPartnerProperties.MtlsProperties mtls = new BankingPartnerProperties.MtlsProperties();
        mtls.setEnabled(false);

        assertThatThrownBy(() -> factory.createSslContext(mtls))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot create mTLS SslContext when mTLS is disabled");
    }

    @Test
    @DisplayName("Factory rejects wrong keystore password")
    void testRejectsWrongPassword(@TempDir Path tempDir) throws Exception {
        Path ksPath = tempDir.resolve("keystore.p12");
        generateEphemeralKeystore(ksPath, "secret123", "client-cert");

        BankingPartnerProperties.MtlsProperties mtls = new BankingPartnerProperties.MtlsProperties();
        mtls.setEnabled(true);
        mtls.setKeyStorePath(ksPath.toUri().toString());
        mtls.setKeyStorePassword("wrong-password");
        mtls.setTrustStorePath(ksPath.toUri().toString());
        mtls.setTrustStorePassword("wrong-password");

        assertThatThrownBy(() -> factory.createSslContext(mtls))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to load mTLS keystore");
    }

    @Test
    @DisplayName("Factory builds valid mTLS SslContext and WebClient from valid keystores")
    void testBuildsValidMtlsWebClient(@TempDir Path tempDir) throws Exception {
        Path ksPath = tempDir.resolve("keystore.p12");
        Path tsPath = tempDir.resolve("truststore.p12");

        generateEphemeralKeystore(ksPath, "ks-pass-123", "client-cert");
        generateEphemeralKeystore(tsPath, "ts-pass-123", "server-cert");

        BankingPartnerProperties.MtlsProperties mtls = new BankingPartnerProperties.MtlsProperties();
        mtls.setEnabled(true);
        mtls.setKeyStorePath(ksPath.toUri().toString());
        mtls.setKeyStorePassword("ks-pass-123");
        mtls.setTrustStorePath(tsPath.toUri().toString());
        mtls.setTrustStorePassword("ts-pass-123");

        SslContext sslContext = factory.createSslContext(mtls);
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isClient()).isTrue();

        BankingPartnerProperties props = new BankingPartnerProperties();
        props.setBaseUrl("https://sandbox.bank.partner.invalid");
        props.setConnectTimeoutMs(3000);
        props.setReadTimeoutMs(10000);
        props.setMtls(mtls);

        WebClient webClient = factory.createWebClient(props);
        assertThat(webClient).isNotNull();
    }
}
