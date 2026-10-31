package com.familyhome.archive.service;

import com.familyhome.common.api.PageQuery;
import com.familyhome.archive.dto.ArchiveSaveDTO;
import com.familyhome.archive.vo.*;
import com.familyhome.security.SessionPrincipal;
import java.util.List;

public interface ArchiveService {
    /** 分页查询；不传分页参数时等价于改造前的「取前 500 条」。 */
    List<ArchiveVO> list(SessionPrincipal p, PageQuery page);

    default List<ArchiveVO> list(SessionPrincipal p) {
        return list(p, PageQuery.first());
    }

    ArchiveDetailVO detail(SessionPrincipal p, String id);

    ArchiveVO create(SessionPrincipal p, ArchiveSaveDTO input);

    void update(SessionPrincipal p, String id, ArchiveSaveDTO input);

    void delete(SessionPrincipal p, String id);

    /** 访问审计分页，默认与改造前一致（最近 100 条）。 */
    List<ArchiveAuditVO> audit(SessionPrincipal p, String id, PageQuery page);

    default List<ArchiveAuditVO> audit(SessionPrincipal p, String id) {
        return audit(p, id, PageQuery.of(null, null, 100));
    }
}
