package com.familyhome.user.service;

import com.familyhome.security.SessionPrincipal;
import com.familyhome.user.dto.PasswordChangeDTO;

public interface PasswordService {
    void create(SessionPrincipal principal, com.familyhome.user.dto.AccountCreateDTO request);

    void set(SessionPrincipal principal, long whitelistId, String password);

    void change(SessionPrincipal principal, PasswordChangeDTO request);
}
