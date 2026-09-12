package com.finx.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Role {
    BUYER,
    SELLER,
    ADMIN,
    PROJECT_MANAGER,
    FINANCE;

    public String getAuthority() {
        return "ROLE_" + this.name();
    }

    @JsonCreator
    public static Role fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String clean = value.trim().toUpperCase();
        if ("CORPORATE".equals(clean)) {
            return BUYER;
        }
        if ("VENDOR".equals(clean)) {
            return SELLER;
        }
        for (Role r : Role.values()) {
            if (r.name().equalsIgnoreCase(clean)) {
                return r;
            }
        }
        throw new IllegalArgumentException("Invalid role: '" + value + "'. Permitted registration roles are: BUYER (or CORPORATE), SELLER (or VENDOR), PROJECT_MANAGER, FINANCE");
    }

    @JsonValue
    public String toValue() {
        return this.name();
    }
}
