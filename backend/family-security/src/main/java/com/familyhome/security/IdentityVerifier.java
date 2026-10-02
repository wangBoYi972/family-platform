package com.familyhome.security;

public interface IdentityVerifier {
    void verify(SessionPrincipal principal);
}
