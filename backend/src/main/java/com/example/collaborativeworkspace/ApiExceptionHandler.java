package com.example.collaborativeworkspace;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DocumentNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound() { return error(HttpStatus.NOT_FOUND, "Document not found"); }
    @ExceptionHandler(DuplicateUserException.class)
    ResponseEntity<Map<String, String>> duplicateUser() { return error(HttpStatus.CONFLICT, "An account with that email already exists"); }
    @ExceptionHandler(InvalidUserInputException.class)
    ResponseEntity<Map<String, String>> invalidUser() { return error(HttpStatus.BAD_REQUEST, "Invalid user input"); }
    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> badRequest(Exception ex) { return error(HttpStatus.BAD_REQUEST, "Invalid request"); }
    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
