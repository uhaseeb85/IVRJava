package com.yourco.ivr.validator;

public class ValidationResult {

    private final boolean valid;
    private final ValidationErrorCode errorCode;

    private ValidationResult(boolean valid, ValidationErrorCode errorCode) {
        this.valid = valid;
        this.errorCode = errorCode;
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, null);
    }

    public static ValidationResult fail(ValidationErrorCode code) {
        return new ValidationResult(false, code);
    }

    public boolean isValid() {
        return valid;
    }

    public ValidationErrorCode getErrorCode() {
        return errorCode;
    }
}