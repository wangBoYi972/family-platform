package com.familyhome.archive.service;

import com.familyhome.archive.dto.ArchiveSaveDTO;
import com.familyhome.archive.vo.*;
import com.familyhome.security.SessionPrincipal;
import java.util.List;

public interface ArchiveService {
    List<ArchiveVO> list(SessionPrincipal p);

    ArchiveDetailVO detail(SessionPrincipal p, String id);

    ArchiveVO create(SessionPrincipal p, ArchiveSaveDTO input);

    void update(SessionPrincipal p, String id, ArchiveSaveDTO input);

    void delete(SessionPrincipal p, String id);

    List<ArchiveAuditVO> audit(SessionPrincipal p, String id);
}
