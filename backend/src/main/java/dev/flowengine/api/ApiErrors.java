package dev.flowengine.api;

import dev.flowengine.workflow.GraphException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(GraphException.class)
    ProblemDetail invalidGraph(GraphException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException ex) {
        var error = ex.getBindingResult().getFieldErrors().getFirst();
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getField() + " " + error.getDefaultMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail malformedBody() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Send a valid JSON request body.");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail conflict() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "This workflow changed. Reload it and try again.");
    }
}
