package com.yourco.ivr.api;

import com.yourco.ivr.api.dto.ErrorResponse;
import com.yourco.ivr.exception.SessionNotFoundException;
import com.yourco.ivr.exception.UnknownBrandException;
import com.yourco.ivr.exception.UnsupportedTokenTypeException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class IvrExceptionHandler {

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(SessionNotFoundException e) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse("SESSION_NOT_FOUND", e.getMessage()));
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
}