package com.example.drive.security;

public record InternalPrincipal(InternalServiceType serviceType, String subjectId) {
}
