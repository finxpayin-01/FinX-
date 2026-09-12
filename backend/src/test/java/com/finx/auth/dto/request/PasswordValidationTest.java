package com.finx.auth.dto.request;

import com.finx.common.enums.Role;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private RegisterRequest createRequestWithPassword(String password) {
        return new RegisterRequest("Test User", "test.user@example.com", password, Role.BUYER);
    }

    @Test
    @DisplayName("Valid standard password should have zero constraint violations")
    void testValidPassword_Standard() {
        RegisterRequest request = createRequestWithPassword("Password123!");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest(name = "Valid password with symbol: {0}")
    @ValueSource(strings = {
            "Password123?",   // Question mark
            "Password123*",   // Asterisk
            "Password123~",   // Tilde
            "Password(123)",   // Parentheses
            "Password/123",   // Forward slash
            "Password[123]",   // Brackets
            "Password{123}",   // Braces
            "Password<123>",   // Angle brackets
            "Password|123",   // Pipe
            "Password:123",   // Colon
            "Password;123",   // Semicolon
            "Password,123",   // Comma
            "Password`123",   // Backtick
            "Password\"123",  // Double quote
            "Password'123",   // Single quote
            "Password-123",   // Hyphen
            "Password_123",   // Underscore
            "Password+123",   // Plus
            "Password=123",   // Equals
            "Password@123",   // At
            "Password#123",   // Hash
            "Password$123",   // Dollar
            "Password%123",   // Percent
            "Password^123",   // Caret
            "Password&123"    // Ampersand
    })
    @DisplayName("Valid passwords with diverse special characters should pass without false rejections")
    void testValidPasswords_DiverseSpecialCharacters(String password) {
        RegisterRequest request = createRequestWithPassword(password);
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Lowercase only should be rejected")
    void testInvalid_LowercaseOnly() {
        RegisterRequest request = createRequestWithPassword("passwordonly");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    @DisplayName("Uppercase only should be rejected")
    void testInvalid_UppercaseOnly() {
        RegisterRequest request = createRequestWithPassword("PASSWORDONLY");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    @DisplayName("Numbers only should be rejected")
    void testInvalid_NumbersOnly() {
        RegisterRequest request = createRequestWithPassword("1234567890");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    @DisplayName("Missing uppercase letter should be rejected")
    void testInvalid_MissingUppercase() {
        RegisterRequest request = createRequestWithPassword("password123!");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    @DisplayName("Missing lowercase letter should be rejected")
    void testInvalid_MissingLowercase() {
        RegisterRequest request = createRequestWithPassword("PASSWORD123!");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    @DisplayName("Missing number should be rejected")
    void testInvalid_MissingNumber() {
        RegisterRequest request = createRequestWithPassword("Password!!!!");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    @DisplayName("Missing special character should be rejected")
    void testInvalid_MissingSpecialCharacter() {
        RegisterRequest request = createRequestWithPassword("Password123");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    @DisplayName("Too short password (< 8 chars) should be rejected")
    void testInvalid_TooShort() {
        RegisterRequest request = createRequestWithPassword("Pass1!");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    @DisplayName("Too long password (> 100 chars) should be rejected")
    void testInvalid_TooLong() {
        String longPassword = "A1!" + "a".repeat(100);
        RegisterRequest request = createRequestWithPassword(longPassword);
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    @DisplayName("Spaces only should be rejected")
    void testInvalid_SpacesOnly() {
        RegisterRequest request = createRequestWithPassword("        ");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    @DisplayName("Space instead of special character should be rejected")
    void testInvalid_SpaceInsteadOfSpecialChar() {
        RegisterRequest request = createRequestWithPassword("Password 123");
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }
}
