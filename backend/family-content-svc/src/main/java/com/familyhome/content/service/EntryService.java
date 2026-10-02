package com.familyhome.content.service;

import com.familyhome.content.dto.EntrySaveDTO;
import com.familyhome.content.vo.EntryVO;
import com.familyhome.security.SessionPrincipal;
import java.util.List;

public interface EntryService {
    List<EntryVO> list(SessionPrincipal p, String kind);

    EntryVO create(SessionPrincipal p, String bearer, String kind, EntrySaveDTO i);

    void update(SessionPrincipal p, String bearer, String kind, long id, EntrySaveDTO i);

    void delete(SessionPrincipal p, String kind, long id);

    void like(SessionPrincipal p, String kind, long id);
}
