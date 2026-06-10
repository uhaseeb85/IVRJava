package com.yourco.ivr.domain;

/**
 * Supported credential types that a caller can submit during an IVR authentication session.
 *
 * <p>Token types are referenced in brand config ({@link com.yourco.ivr.domain.config.TokenPath})
 * to declare which credentials are required or accepted as backup alternatives for each
 * authentication level. Each type is validated by a corresponding
 * {@link com.yourco.ivr.validator.TokenValidator} and, when configured, verified against a
 * backend via a {@link com.yourco.ivr.lookup.TokenLookupService}.
 *
 * <p><strong>Security:</strong> raw token values must never be logged regardless of type.
 * Only the enum name and validation outcome (PASS/FAIL) may appear in logs.
 */
public enum TokenType {
    /** Customer's full account number. */
    ACCOUNT_NUMBER("account number"),
    /** Numeric personal identification number (minimum 4 digits). */
    PIN("PIN"),
    /** Time-limited one-time passcode (exactly 6 characters). */
    OTP("one-time passcode"),
    /** Last four digits of Social Security Number (exactly 4 characters). */
    SSN_LAST4("last 4 digits of your SSN"),
    /** Voice biometric sample — format validation only checks for non-blank value. */
    VOICE_PRINT("voice verification"),
    /** Date of birth in ISO local date format (YYYY-MM-DD). */
    DATE_OF_BIRTH("date of birth"),
    /** Last four digits of a payment card number (exactly 4 characters). */
    CARD_LAST4("last 4 digits of your card");

    private final String displayName;

    TokenType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}