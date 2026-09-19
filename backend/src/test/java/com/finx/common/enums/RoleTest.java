package com.finx.common.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleTest {

    @ParameterizedTest
    @CsvSource({
            "BUYER, BUYER",
            "buyer, BUYER",
            "CORPORATE, BUYER",
            "corporate, BUYER",
            "SELLER, SELLER",
            "seller, SELLER",
            "VENDOR, SELLER",
            "vendor, SELLER",
            "PROJECT_MANAGER, PROJECT_MANAGER",
            "project_manager, PROJECT_MANAGER",
            "PROJ_MANAGER, PROJECT_MANAGER",
            "proj_manager, PROJECT_MANAGER",
            "PROJ MANAGER, PROJECT_MANAGER",
            "PROJECT MANAGER, PROJECT_MANAGER",
            "FINANCE, FINANCE",
            "finance, FINANCE",
            "ADMIN, ADMIN",
            "admin, ADMIN"
    })
    @DisplayName("Should resolve role and aliases correctly")
    void shouldResolveRoleAndAliases(String input, Role expected) {
        assertThat(Role.fromString(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Should return null for null or empty input")
    void shouldReturnNullForEmpty() {
        assertThat(Role.fromString(null)).isNull();
        assertThat(Role.fromString("")).isNull();
        assertThat(Role.fromString("   ")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPERUSER", "DEVELOPER", "GUEST", "INVALID_ROLE"})
    @DisplayName("Should throw IllegalArgumentException for unknown roles")
    void shouldThrowForUnknownRole(String invalidRole) {
        assertThatThrownBy(() -> Role.fromString(invalidRole))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Permitted registration roles are");
    }
}
