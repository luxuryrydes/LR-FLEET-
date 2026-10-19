package com.lrtaxi.fleet.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Consistent JSON error body: {code, message, fieldErrors?, traceId}. Never leaks stack traces or SQL. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record FieldError(String field, String message) {
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message, List<FieldError> fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        if (fields != null && !fields.isEmpty()) {
            m.put("fieldErrors", fields);
        }
        return ResponseEntity.status(status).body(m);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.status(), e.code(), e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        List<FieldError> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldError(f.getField(), f.getDefaultMessage())).toList();
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Please correct the highlighted fields", fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> violation(ConstraintViolationException e) {
        List<FieldError> fields = e.getConstraintViolations().stream()
                .map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage())).toList();
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Please correct the highlighted fields", fields);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegal(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_VALUE", e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> mismatch(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid value for parameter '" + e.getName() + "'", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException e) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        log.warn("Integrity violation: {}", msg);
        String friendly = "The record conflicts with existing data";
        if (msg.contains("ux_vehicles_registration")) {
            friendly = "A vehicle with this registration number already exists";
        } else if (msg.contains("ux_fuel_dedupe")) {
            friendly = "This fuel transaction has already been recorded";
        } else if (msg.contains("ux_fuel_txn_ref")) {
            friendly = "This transaction number has already been recorded";
        } else if (msg.contains("ux_gps_current")) {
            friendly = "A GPS km record already exists for this vehicle and date";
        } else if (msg.contains("violates check constraint")) {
            friendly = "One or more values are outside the allowed range";
        } else if (msg.contains("violates foreign key")) {
            friendly = "A referenced record does not exist or is still in use";
        }
        return body(HttpStatus.CONFLICT, "DATA_CONFLICT", friendly, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> denied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action", null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "The file exceeds the maximum upload size", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noResource(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Endpoint not found", null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY",
                "The request could not be read. Check that numbers and dates (YYYY-MM-DD) are valid.", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        // Standard Spring MVC errors (missing parameter, wrong method, unsupported media type...) keep their 4xx status.
        if (e instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            String detail = er.getBody().getDetail();
            return body(HttpStatus.valueOf(er.getStatusCode().value()), "REQUEST_ERROR", detail != null ? detail : "Invalid request", null);
        }
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled error traceId={}", traceId, e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong. Please retry; if it persists quote reference " + traceId, null);
    }
}
