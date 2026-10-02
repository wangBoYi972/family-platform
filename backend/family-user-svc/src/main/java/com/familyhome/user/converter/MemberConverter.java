package com.familyhome.user.converter;

import com.familyhome.user.model.WhitelistEntry;
import com.familyhome.user.vo.WhitelistVO;

public final class MemberConverter {
    private MemberConverter() {}

    public static WhitelistVO toVO(WhitelistEntry e) {
        return new WhitelistVO(e.getId(), e.getPhone(), e.getRole(), e.getEnabled());
    }
}
