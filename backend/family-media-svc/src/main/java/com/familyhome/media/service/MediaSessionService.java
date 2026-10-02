package com.familyhome.media.service;

import com.familyhome.media.dto.MediaSessionCreateDTO;
import com.familyhome.media.vo.MediaSessionVO;
import com.familyhome.security.SessionPrincipal;
import java.util.List;

public interface MediaSessionService {
    List<MediaSessionVO> list(SessionPrincipal p);

    MediaSessionVO create(SessionPrincipal p, MediaSessionCreateDTO i);

    void close(SessionPrincipal p, String id);
}
