package com.familyhome.content.service;

import com.familyhome.common.api.PageQuery;
import com.familyhome.content.dto.EntrySaveDTO;
import com.familyhome.content.vo.EntryVO;
import com.familyhome.security.SessionPrincipal;
import java.util.List;

public interface EntryService {
    /** 分页查询；不传分页参数时等价于改造前的「取前 500 条」。 */
    List<EntryVO> list(SessionPrincipal p, String kind, PageQuery page);

    default List<EntryVO> list(SessionPrincipal p, String kind) {
        return list(p, kind, PageQuery.first());
    }

    EntryVO create(SessionPrincipal p, String bearer, String kind, EntrySaveDTO i);

    void update(SessionPrincipal p, String bearer, String kind, long id, EntrySaveDTO i);

    void delete(SessionPrincipal p, String kind, long id);

    void like(SessionPrincipal p, String kind, long id);
}
