package com.kilivana.auth.api;

public record ForgotPasswordResponse(String message, String resetToken) {

    public static ForgotPasswordResponse generic() {
        return new ForgotPasswordResponse("If the account exists, a password reset link has been sent", null);
    }

    public static ForgotPasswordResponse devToken(String resetToken) {
        return new ForgotPasswordResponse(
                "Development delivery is active; the reset token is returned below", resetToken);
    }
}