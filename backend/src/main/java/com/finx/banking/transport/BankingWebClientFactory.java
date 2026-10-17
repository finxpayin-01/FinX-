package com.finx.banking.transport;

import com.finx.banking.config.BankingPartnerProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Objects;

/**
 * Factory for creating secure, mTLS-enabled WebClient instances for regulated partner banking communication.
 * Strictly enforces TLS 1.2+, standard hostname verification, valid keystores/truststores, and finite timeouts.
 * Never disables certificate checks or hostname verification. Never logs private key or credential material.
 */
public class BankingWebClientFactory {

    private static final Logger log = LoggerFactory.getLogger(BankingWebClientFactory.class);
    private static final int MAX_IN_MEMORY_SIZE = 1024 * 1024; // 1 MB bounded response buffer

    private final ResourceLoader resourceLoader;

    public BankingWebClientFactory() {
        this(new DefaultResourceLoader());
    }

    public BankingWebClientFactory(ResourceLoader resourceLoader) {
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader must not be null");
    }

    public WebClient createWebClient(BankingPartnerProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));

        if (properties.getMtls() != null && properties.getMtls().isEnabled()) {
            SslContext sslContext = createSslContext(properties.getMtls());
            httpClient = httpClient.secure(spec -> spec.sslContext(sslContext));
        }

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();

        return WebClient.builder()
                .baseUrl(properties.getBaseUrl() != null ? properties.getBaseUrl() : "")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }

    public SslContext createSslContext(BankingPartnerProperties.MtlsProperties mtls) {
        Objects.requireNonNull(mtls, "mtls properties must not be null");
        if (!mtls.isEnabled()) {
            throw new IllegalArgumentException("Cannot create mTLS SslContext when mTLS is disabled");
        }

        KeyStore keyStore = loadKeyStore(mtls.getKeyStorePath(), mtls.getKeyStorePassword(), mtls.getKeyStoreType(), "keystore");
        KeyStore trustStore = loadKeyStore(mtls.getTrustStorePath(), mtls.getTrustStorePassword(), mtls.getTrustStoreType(), "truststore");

        try {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, mtls.getKeyStorePassword().toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            return SslContextBuilder.forClient()
                    .keyManager(kmf)
                    .trustManager(tmf)
                    .protocols("TLSv1.2", "TLSv1.3")
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build secure Netty SslContext for partner banking mTLS", e);
        }
    }

    private KeyStore loadKeyStore(String path, String password, String type, String storeRole) {
        if (path == null || path.trim().isBlank()) {
            throw new IllegalArgumentException("mTLS " + storeRole + " path must not be blank");
        }
        if (password == null) {
            throw new IllegalArgumentException("mTLS " + storeRole + " password must not be null");
        }
        String storeType = (type != null && !type.isBlank()) ? type.trim().toUpperCase() : "PKCS12";

        Resource resource = resourceLoader.getResource(path.trim());
        if (!resource.exists()) {
            throw new IllegalStateException("mTLS " + storeRole + " file not found: " + path);
        }

        try (InputStream is = resource.getInputStream()) {
            KeyStore ks = KeyStore.getInstance(storeType);
            ks.load(is, password.toCharArray());

            // Validate that keystore actually contains key/cert material
            Enumeration<String> aliases = ks.aliases();
            if (!aliases.hasMoreElements()) {
                throw new IllegalStateException("mTLS " + storeRole + " contains no aliases: " + path);
            }

            log.info("Successfully loaded mTLS {} of type {} from {}", storeRole, storeType, path);
            return ks;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load mTLS " + storeRole + " from " + path + ": " + e.getMessage(), e);
        }
    }
}
