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
    @ExceptionHandler(WorkspaceResourceNotFoundException.class)
    ResponseEntity<Map<String, String>> workspaceNotFound() { return error(HttpStatus.NOT_FOUND, "Resource not found"); }
    @ExceptionHandler(LastWorkspaceOwnerException.class)
    ResponseEntity<Map<String, String>> lastOwner() { return error(HttpStatus.CONFLICT, "A workspace must retain at least one owner"); }
    @ExceptionHandler(DocumentConflictException.class)
    ResponseEntity<Map<String, String>> documentConflict() { return error(HttpStatus.CONFLICT, "Document version is stale or document is archived"); }
    @ExceptionHandler(DuplicateUserException.class)
    ResponseEntity<Map<String, String>> duplicateUser() { return error(HttpStatus.CONFLICT, "An account with that email already exists"); }
    @ExceptionHandler(InvalidUserInputException.class)
    ResponseEntity<Map<String, String>> invalidUser() { return error(HttpStatus.BAD_REQUEST, "Invalid user input"); }
    @ExceptionHandler(InvalidDocumentPageException.class)
    ResponseEntity<Map<String, String>> invalidPage() { return error(HttpStatus.BAD_REQUEST, "Invalid document page parameters"); }
    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> badRequest(Exception ex) { return error(HttpStatus.BAD_REQUEST, "Invalid request"); }
    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
