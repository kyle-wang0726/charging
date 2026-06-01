package com.charging.backend.controller;

import com.charging.backend.dto.ApiResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> illegalArg(IllegalArgumentException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> invalid(MethodArgumentNotValidException ex) {
        return ApiResponse.fail("invalid request parameters");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> other(Exception ex) {
        return ApiResponse.fail("system error: " + ex.getMessage());
    }
}
