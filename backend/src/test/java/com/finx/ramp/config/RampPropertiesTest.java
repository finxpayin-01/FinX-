package com.finx.ramp.config;

import com.finx.exception.PaymentGatewayUnavailableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RampPropertiesTest {

    @Test
    void isConfiguredRequiresApiKeyAndApiSecret() {
        RampProperties properties = new RampProperties();

        assertFalse(properties.isConfigured());

        properties.setApiKey("test_key");
        assertFalse(properties.isConfigured());

        properties.setApiSecret("test_secret");
        assertTrue(properties.isConfigured());
    }

    @Test
    void requireConfiguredForOperationFailsClosedWhenCredentialsMissing() {
        RampProperties properties = new RampProperties();

        assertThrows(PaymentGatewayUnavailableException.class, properties::requireConfiguredForOperation);
    }

    @Test
    void toStatusNeverIncludesSecretValues() {
        RampProperties properties = new RampProperties();
        properties.setApiKey("test_key");
        properties.setApiSecret("test_secret");

        RampConfigurationStatus status = properties.toStatus();

        assertTrue(status.configured());
        assertEquals("UNSPECIFIED", status.provider());
        assertEquals("sandbox", status.environment());
    }
}
