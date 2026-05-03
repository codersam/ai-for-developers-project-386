package com.hexlet.calendar.web.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<com.hexlet.calendar.generated.model.Error> handleApi(ApiException ex) {
        return respond(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<com.hexlet.calendar.generated.model.Error> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return respond(400, message.isEmpty() ? "Validation failed" : message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<com.hexlet.calendar.generated.model.Error> handleMalformed(HttpMessageNotReadableException ex) {
        return respond(400, "Malformed JSON request");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<com.hexlet.calendar.generated.model.Error> handleMissingParam(MissingServletRequestParameterException ex) {
        return respond(400, "Missing required parameter: " + ex.getParameterName());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<com.hexlet.calendar.generated.model.Error> handleDataIntegrity(DataIntegrityViolationException ex) {
        if (isExclusionViolation(ex.getMostSpecificCause())) {
            return respond(409, "Slot is no longer available");
        }
        LOG.error("Data integrity violation", ex);
        return respond(500, "Database error");
    }

    private static boolean isExclusionViolation(Throwable cause) {
        return cause instanceof SQLException sql && "23P01".equals(sql.getSQLState());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<com.hexlet.calendar.generated.model.Error> handleNoResource(NoResourceFoundException ex) {
        return respond(404, "Not found: " + ex.getResourcePath());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<com.hexlet.calendar.generated.model.Error> handleLegacyStatus(ResponseStatusException ex) {
        int status = ex.getStatusCode().value();
        String reason = ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason();
        return respond(status, reason);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<com.hexlet.calendar.generated.model.Error> handleAny(Exception ex) {
        LOG.error("Unhandled exception", ex);
        return respond(500, "Internal server error");
    }

    private static ResponseEntity<com.hexlet.calendar.generated.model.Error> respond(int code, String message) {
        var body = new com.hexlet.calendar.generated.model.Error();
        body.setCode(code);
        body.setMessage(message);
        return ResponseEntity.status(code).body(body);
    }
}
