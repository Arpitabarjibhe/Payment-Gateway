package com.payments.gateway.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {
    private ErrorDetail error;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ErrorDetail {
        private String type;
        private String message;
        private String code;
    }

    public static ErrorResponse of(String type, String message, String code) {
        return new ErrorResponse(ErrorDetail.builder()
                .type(type)
                .message(message)
                .code(code)
                .build());
    }
}
