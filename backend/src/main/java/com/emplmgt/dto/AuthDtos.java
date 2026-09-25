package com.emplmgt.dto;

import jakarta.validation.constraints.NotBlank;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank(message = "Email is required") String email,
            @NotBlank(message = "Password is required") String password,
            Boolean rememberMe) {
    }

    public record LoginResponse(String token, long expiresIn, UserDto user) {
    }

    public record UserDto(Long id, String email, String role, String employeeCode, String fullName, String avatar,
                          Long employeeId, Long teamId, String teamName) {
    }

    public record MeResponse(Long id, String email, String role, String employeeCode, String fullName, String avatar,
                             Long employeeId, Long teamId, String teamName) {
    }
}