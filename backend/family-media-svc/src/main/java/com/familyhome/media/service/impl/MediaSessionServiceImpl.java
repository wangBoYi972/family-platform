package com.familyhome.media.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.media.dto.MediaSessionCreateDTO;
import com.familyhome.media.mapper.MediaSessionMapper;
import com.familyhome.media.model.MediaSession;
import com.familyhome.media.service.MediaSessionService;
import com.familyhome.media.vo.MediaSessionVO;
import com.familyhome.security.SessionPrincipal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MediaSessionServiceImpl implements MediaSessionService {
    @Autowired private MediaSessionMapper mapper;

    private MediaSessionVO vo(MediaSession s) {
        return new MediaSessionVO(
                s.getId(),
                s.getHouseholdId(),
                s.getCreatorId(),
                s.getKind(),
                s.getStatus(),
                s.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<MediaSessionVO> list(SessionPrincipal p) {
        return mapper
                .selectList(
                        new LambdaQueryWrapper<MediaSession>()
                                .eq(MediaSession::getHouseholdId, p.householdId())
                                .orderByDesc(MediaSession::getCreatedAt))
                .stream()
                .map(this::vo)
                .toList();
    }

    @Transactional
    public MediaSessionVO create(SessionPrincipal p, MediaSessionCreateDTO i) {
        var s = new MediaSession();
        s.setId(UUID.randomUUID().toString());
        s.setHouseholdId(p.householdId());
        s.setCreatorId(p.userId());
        s.setKind(i.kind());
        s.setStatus("WAITING");
        s.setCreatedAt(LocalDateTime.now());
        mapper.insert(s);
        return vo(s);
    }

    @Transactional
    public void close(SessionPrincipal p, String id) {
        var s =
                mapper.selectOne(
                        new LambdaQueryWrapper<MediaSession>()
                                .eq(MediaSession::getId, id)
                                .eq(MediaSession::getHouseholdId, p.householdId()));
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!p.admin() && !s.getCreatorId().equals(p.userId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        s.setStatus("CLOSED");
        mapper.updateById(s);
    }
}
