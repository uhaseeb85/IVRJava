package com.yourco.ivr.api;

import com.yourco.ivr.api.dto.ErrorResponse;
import com.yourco.ivr.exception.BrandConfigException;
import com.yourco.ivr.exception.SessionConflictException;
import com.yourco.ivr.exception.SessionLockedException;
import com.yourco.ivr.exception.SessionNotFoundException;
import com.yourco.ivr.exception.SessionSerializationException;
import com.yourco.ivr.exception.TransferNotAllowedException;
import com.yourco.ivr.exception.UnknownBrandException;
import com.yourco.ivr.exception.UnknownCallerException;
import com.yourco.ivr.exception.UnknownLookupServiceException;
import com.yourco.ivr.exception.UnsupportedTokenTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Global exception handler for all IVR REST controllers.
 *
 * <p>Maps domain and validation exceptions to structured {@link ErrorResponse} bodies with
 * appropriate HTTP status codes. All exception types thrown by the engine and service layer
 * should have a handler here to avoid leaking stack traces in 500 responses.
 *
 * <p>HTTP status mapping:
 * <ul>
 *   <li>400 — {@link com.yourco.ivr.exception.UnknownBrandException},
 *             {@link com.yourco.ivr.exception.UnknownCallerException},
 *             {@link com.yourco.ivr.exception.UnsupportedTokenTypeException},
 *             {@link com.yourco.ivr.exception.UnknownLookupServiceException},
 *             {@link IllegalArgumentException}, validation errors</li>
 *   <li>403 — {@link com.yourco.ivr.exception.TransferNotAllowedException}</li>
 *   <li>404 — {@link com.yourco.ivr.exception.SessionNotFoundException}</li>
 *   <li>409 — {@link com.yourco.ivr.exception.SessionConflictException}</li>
 *   <li>423 — {@link com.yourco.ivr.exception.SessionLockedException} (HTTP Locked)</li>
 *   <li>500 — {@link com.yourco.ivr.exception.SessionSerializationException},
 *             {@link com.yourco.ivr.exception.BrandConfigException}, unexpected exceptions</li>
 * </ul>
 */
@RestControllerAdvice
public class IvrExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IvrExceptionHandler.class);

    /** Builds the uniform error body every handler returns. */
    private static ResponseEntity<ErrorResponse> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(SessionNotFoundException e) {
        return error(404, "SESSION_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(SessionLockedException.class)
    public ResponseEntity<ErrorResponse> handleLocked(SessionLockedException e) {
        return error(423, "SESSION_REDIRECT_TO_AGENT", e.getMessage());
    }

    @ExceptionHandler(UnknownBrandException.class)
    public ResponseEntity<ErrorResponse> handleBrand(UnknownBrandException e) {
        return error(400, "UNKNOWN_BRAND", e.getMessage());
    }

    @ExceptionHandler(UnsupportedTokenTypeException.class)
    public ResponseEntity<ErrorResponse> handleToken(UnsupportedTokenTypeException e) {
        return error(400, "UNSUPPORTED_TOKEN", e.getMessage());
    }

    @ExceptionHandler(UnknownLookupServiceException.class)
    public ResponseEntity<ErrorResponse> handleLookupService(UnknownLookupServiceException e) {
        return error(400, "UNKNOWN_LOOKUP_SERVICE", e.getMessage());
    }

    @ExceptionHandler(TransferNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleTransferNotAllowed(TransferNotAllowedException e) {
        return error(403, "TRANSFER_NOT_ALLOWED", e.getMessage());
    }

    @ExceptionHandler(UnknownCallerException.class)
    public ResponseEntity<ErrorResponse> handleUnknownCaller(UnknownCallerException e) {
        return error(400, "UNKNOWN_CALLER", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegal(IllegalArgumentException e) {
        return error(400, "INVALID_REQUEST", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return error(400, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler(SessionSerializationException.class)
    public ResponseEntity<ErrorResponse> handleSerialization(SessionSerializationException e) {
        log.error("Session serialization failure", e);
        return error(500, "INTERNAL_ERROR", "An internal error occurred");
    }

    @ExceptionHandler(SessionConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(SessionConflictException e) {
        log.warn("Session conflict: {}", e.getMessage());
        return error(409, "SESSION_CONFLICT", e.getMessage());
    }

    @ExceptionHandler(BrandConfigException.class)
    public ResponseEntity<ErrorResponse> handleBrandConfig(BrandConfigException e) {
        log.error("Brand config error", e);
        return error(500, "BRAND_CONFIG_ERROR", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return error(500, "INTERNAL_ERROR", "An unexpected error occurred");
    }
}