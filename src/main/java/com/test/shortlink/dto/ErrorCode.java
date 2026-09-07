package com.test.shortlink.dto;

public final class ErrorCode {
    public static final int SUCCESS = 0;
    public static final int INVALID_REQUEST = 40000;
    public static final int INTERNAL_ERROR = 50000;

    private ErrorCode() {
    }
}
