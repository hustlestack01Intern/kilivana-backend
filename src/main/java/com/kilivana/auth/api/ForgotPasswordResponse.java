package com.kilivana.auth.api;

public record ForgotPasswordResponse(String message) {

    public static ForgotPasswordResponse generic() {
        return new ForgotPasswordResponse("If the account exists, a password reset link has been sent");
    }
}
