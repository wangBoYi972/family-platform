package com.familyhome.content.service;

import com.familyhome.common.api.PageQuery;
import com.familyhome.content.dto.EntrySaveDTO;
import com.familyhome.content.vo.EntryVO;
import com.familyhome.security.SessionPrincipal;
import java.util.List;

public interface EntryService {
    /**
     * 分页 + 关键字查询；不传分页参数时等价于改造前的「取前 500 条」。
     *
     * <p>keyword 匹配标题或正文，在 SQL 里判断——前端只有当前一页，本地过滤会漏掉还没加载的内容。
     */
    List<EntryVO> list(SessionPrincipal p, String kind, PageQuery page, String keyword);

    default List<EntryVO> list(SessionPrincipal p, String kind, PageQuery page) {
        return list(p, kind, page, null);
    }

    default List<EntryVO> list(SessionPrincipal p, String kind) {
        return list(p, kind, PageQuery.first(), null);
    }

    EntryVO create(SessionPrincipal p, String bearer, String kind, EntrySaveDTO i);

    void update(SessionPrincipal p, String bearer, String kind, long id, EntrySaveDTO i);

    void delete(SessionPrincipal p, String kind, long id);

    void like(SessionPrincipal p, String kind, long id);
}
