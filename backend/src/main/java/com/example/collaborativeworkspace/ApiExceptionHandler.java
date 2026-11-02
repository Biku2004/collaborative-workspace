package com.example.collaborativeworkspace;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotencyConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiError("IDEMPOTENCY_CONFLICT", "Idempotency key was already used with different input"));
    }
    @ExceptionHandler(DocumentNotFoundException.class)
    ResponseEntity<ApiError> notFound() { return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Document not found"); }
    @ExceptionHandler(WorkspaceResourceNotFoundException.class)
    ResponseEntity<ApiError> workspaceNotFound() { return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found"); }
    @ExceptionHandler(LastWorkspaceOwnerException.class)
    ResponseEntity<ApiError> lastOwner() { return error(HttpStatus.CONFLICT, "LAST_OWNER", "A workspace must retain at least one owner"); }
    @ExceptionHandler(DocumentConflictException.class)
    ResponseEntity<ApiError> documentConflict() { return error(HttpStatus.CONFLICT, "DOCUMENT_CONFLICT", "Document version is stale or document is archived"); }
    @ExceptionHandler(DuplicateUserException.class)
    ResponseEntity<ApiError> duplicateUser() { return error(HttpStatus.CONFLICT, "DUPLICATE_USER", "An account with that email already exists"); }
    @ExceptionHandler(InvalidUserInputException.class)
    ResponseEntity<ApiError> invalidUser() { return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid user input"); }
    @ExceptionHandler(InvalidDocumentPageException.class)
    ResponseEntity<ApiError> invalidPage() { return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid page parameters"); }
    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> badRequest(Exception ex) { return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request"); }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden() { return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied"); }

    @ExceptionHandler({org.springframework.dao.QueryTimeoutException.class, org.springframework.dao.CannotAcquireLockException.class,
            org.springframework.dao.DataAccessResourceFailureException.class, org.springframework.transaction.CannotCreateTransactionException.class})
    ResponseEntity<ApiError> unavailable() { return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Service temporarily unavailable"); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) {
        if (ex instanceof org.springframework.web.ErrorResponse response) {
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                    .cacheControl(org.springframework.http.CacheControl.noStore())
                    .body(new ApiError(HttpStatus.valueOf(response.getStatusCode().value()).name(), "Request could not be processed"));
        }
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Unhandled API failure: {}", ex.getClass().getName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(org.springframework.http.CacheControl.noStore()).body(new ApiError(code, message));
    }
}
