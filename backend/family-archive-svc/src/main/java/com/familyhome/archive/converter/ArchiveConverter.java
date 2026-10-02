package com.familyhome.archive.converter;

import com.familyhome.archive.model.*;
import com.familyhome.archive.vo.*;

public final class ArchiveConverter {
    private ArchiveConverter() {}

    public static ArchiveVO toVO(FamilyArchive a) {
        return new ArchiveVO(
                a.getId(),
                a.getOwnerId(),
                a.getTitle(),
                a.getCategory(),
                a.getVisibility(),
                a.getCreatedAt());
    }

    public static ArchiveDetailVO detail(FamilyArchive a, String body) {
        return new ArchiveDetailVO(
                a.getId(),
                a.getOwnerId(),
                a.getTitle(),
                a.getCategory(),
                a.getVisibility(),
                body,
                a.getCreatedAt());
    }

    public static ArchiveAuditVO toVO(ArchiveAccessLog a) {
        return new ArchiveAuditVO(a.getUserId(), a.getAction(), a.getCreatedAt());
    }
}
