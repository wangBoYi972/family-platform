package com.familyhome.user.security;

import org.springframework.beans.factory.annotation.Autowired;
import com.familyhome.security.*;
import com.familyhome.user.mapper.AuthSessionMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class UserIdentityVerifier implements IdentityVerifier {
    @Autowired
    private AuthSessionMapper sessions;

    public void verify(SessionPrincipal principal) {
        if (sessions.countActive(principal) != 1)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "会话或家庭成员资格已失效");
    }
}
