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
import com.yourco.ivr.exception.UnsupportedTokenTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class IvrExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IvrExceptionHandler.class);

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(SessionNotFoundException e) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse("SESSION_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(SessionLockedException.class)
    public ResponseEntity<ErrorResponse> handleLocked(SessionLockedException e) {
        return ResponseEntity.status(423)
            .body(new ErrorResponse("SESSION_LOCKED", e.getMessage()));
    }

    @ExceptionHandler(UnknownBrandException.class)
    public ResponseEntity<ErrorResponse> handleBrand(UnknownBrandException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("UNKNOWN_BRAND", e.getMessage()));
    }

    @ExceptionHandler(UnsupportedTokenTypeException.class)
    public ResponseEntity<ErrorResponse> handleToken(UnsupportedTokenTypeException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("UNSUPPORTED_TOKEN", e.getMessage()));
    }

    @ExceptionHandler(TransferNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleTransferNotAllowed(TransferNotAllowedException e) {
        return ResponseEntity.status(403)
            .body(new ErrorResponse("TRANSFER_NOT_ALLOWED", e.getMessage()));
    }

    @ExceptionHandler(UnknownCallerException.class)
    public ResponseEntity<ErrorResponse> handleUnknownCaller(UnknownCallerException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("UNKNOWN_CALLER", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegal(IllegalArgumentException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("INVALID_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return ResponseEntity.status(400)
            .body(new ErrorResponse("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(SessionSerializationException.class)
    public ResponseEntity<ErrorResponse> handleSerialization(SessionSerializationException e) {
        log.error("Session serialization failure", e);
        return ResponseEntity.status(500)
            .body(new ErrorResponse("INTERNAL_ERROR", "An internal error occurred"));
    }

    @ExceptionHandler(SessionConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(SessionConflictException e) {
        log.warn("Session conflict: {}", e.getMessage());
        return ResponseEntity.status(409)
            .body(new ErrorResponse("SESSION_CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(BrandConfigException.class)
    public ResponseEntity<ErrorResponse> handleBrandConfig(BrandConfigException e) {
        log.error("Brand config error", e);
        return ResponseEntity.status(500)
            .body(new ErrorResponse("BRAND_CONFIG_ERROR", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(500)
            .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}