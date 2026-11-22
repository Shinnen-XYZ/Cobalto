package com.cobalto.api.exception;

import com.cobalto.api.dto.ApiError;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiError> handleDomain(DomainException exception) {
        return response(exception.getStatus(), exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation() {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Revisa los datos enviados");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraintValidation() {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Revisa los datos enviados");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation() {
        return response(HttpStatus.CONFLICT, "CONFLICT",
            "La operación entra en conflicto con un registro existente");
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
            .body(new ApiError(Instant.now(), status.value(), code, message));
    }
}
