package com.finx.release.finalizer;

import com.finx.release.finalizer.gate.SandboxSettlementFinalizationGate;
import com.finx.release.finalizer.service.TrustedSettlementFinalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseExecutionE7ArchitectureTest {

    @Test
    @DisplayName("TrustedSettlementFinalizer has zero dependencies on BankingProvider, NotificationService, or SettlementInstruction")
    void finalizer_forbiddenDependenciesAbsent() {
        Field[] fields = TrustedSettlementFinalizer.class.getDeclaredFields();
        for (Field f : fields) {
            String typeName = f.getType().getSimpleName().toLowerCase();
            assertThat(typeName)
                    .as("TrustedSettlementFinalizer must not depend on " + f.getType().getSimpleName())
                    .doesNotContain("bankingprovider")
                    .doesNotContain("notificationservice")
                    .doesNotContain("settlementinstruction")
                    .doesNotContain("settlementauthorizationservice");
        }
    }

    @Test
    @DisplayName("SandboxSettlementFinalizationGate has zero dependencies on BankingProvider")
    void gate_bankingProviderAbsent() {
        Field[] fields = SandboxSettlementFinalizationGate.class.getDeclaredFields();
        for (Field f : fields) {
            String typeName = f.getType().getSimpleName().toLowerCase();
            assertThat(typeName)
                    .as("SandboxSettlementFinalizationGate must not depend on " + f.getType().getSimpleName())
                    .doesNotContain("bankingprovider");
        }
    }

    @Test
    @DisplayName("TrustedSettlementFinalizer methods do not contain provider calls or notification calls")
    void finalizer_noProviderOrNotificationMethods() {
        Method[] methods = TrustedSettlementFinalizer.class.getDeclaredMethods();
        for (Method m : methods) {
            String name = m.getName().toLowerCase();
            assertThat(name)
                    .doesNotContain("initiatesettlement")
                    .doesNotContain("getsettlementstatus")
                    .doesNotContain("lookupsettlement")
                    .doesNotContain("sendnotification");
        }
    }
}
