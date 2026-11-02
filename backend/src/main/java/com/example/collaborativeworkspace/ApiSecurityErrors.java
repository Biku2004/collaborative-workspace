package com.example.collaborativeworkspace;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

final class ApiSecurityErrors {
    private ApiSecurityErrors() {}
    static void write(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        // Both fields are fixed constants; no input or exception text reaches this response.
        response.getWriter().write(status == 401
                ? "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Authentication required\"}"
                : "{\"code\":\"ACCESS_DENIED\",\"message\":\"Access denied\"}");
    }
}
