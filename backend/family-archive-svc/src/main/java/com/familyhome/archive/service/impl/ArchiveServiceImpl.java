package com.familyhome.archive.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.archive.converter.ArchiveConverter;
import com.familyhome.archive.dto.ArchiveSaveDTO;
import com.familyhome.archive.mapper.*;
import com.familyhome.archive.model.*;
import com.familyhome.archive.security.ArchiveCipher;
import com.familyhome.archive.service.ArchiveService;
import com.familyhome.archive.vo.*;
import com.familyhome.security.SessionPrincipal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ArchiveServiceImpl implements ArchiveService {
    @Autowired
    private FamilyArchiveMapper archives;
    @Autowired
    private ArchiveAccessLogMapper logs;
    @Autowired
    private ArchiveCipher cipher;

    private LambdaQueryWrapper<FamilyArchive> readable(SessionPrincipal p) {
        return new LambdaQueryWrapper<FamilyArchive>()
                .eq(FamilyArchive::getHouseholdId, p.householdId())
                .and(
                        q ->
                                q.eq(FamilyArchive::getOwnerId, p.userId())
                                        .or()
                                        .eq(FamilyArchive::getVisibility, "FAMILY"));
    }

    public List<ArchiveVO> list(SessionPrincipal p) {
        return archives
                .selectList(
                        readable(p)
                                .select(
                                        FamilyArchive::getId,
                                        FamilyArchive::getOwnerId,
                                        FamilyArchive::getTitle,
                                        FamilyArchive::getCategory,
                                        FamilyArchive::getVisibility,
                                        FamilyArchive::getCreatedAt)
                                .orderByDesc(FamilyArchive::getCreatedAt)
                                .last("LIMIT 500"))
                .stream()
                .map(ArchiveConverter::toVO)
                .toList();
    }

    private FamilyArchive find(SessionPrincipal p, String id) {
        var a = archives.selectOne(readable(p).eq(FamilyArchive::getId, id));
        if (a == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "档案不存在或未授权");
        return a;
    }

    private void owner(SessionPrincipal p, FamilyArchive a) {
        if (!Objects.equals(a.getOwnerId(), p.userId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有档案创建者可以操作");
    }

    private void log(SessionPrincipal p, String id, String action) {
        var l = new ArchiveAccessLog();
        l.setHouseholdId(p.householdId());
        l.setUserId(p.userId());
        l.setArchiveId(id);
        l.setAction(action);
        l.setCreatedAt(LocalDateTime.now());
        logs.insert(l);
    }

    @Transactional
    public ArchiveDetailVO detail(SessionPrincipal p, String id) {
        var a = find(p, id);
        String body = cipher.decrypt(a.getEncryptedBody(), p.householdId() + ":" + id);
        log(p, id, "READ");
        return ArchiveConverter.detail(a, body);
    }

    @Transactional
    public ArchiveVO create(SessionPrincipal p, ArchiveSaveDTO i) {
        var a = new FamilyArchive();
        a.setId(UUID.randomUUID().toString());
        a.setHouseholdId(p.householdId());
        a.setOwnerId(p.userId());
        a.setTitle(i.title());
        a.setCategory(i.category());
        a.setVisibility(i.visibility());
        a.setEncryptedBody(cipher.encrypt(i.body(), p.householdId() + ":" + a.getId()));
        a.setCreatedAt(LocalDateTime.now());
        archives.insert(a);
        log(p, a.getId(), "CREATE");
        return ArchiveConverter.toVO(a);
    }

    @Transactional
    public void update(SessionPrincipal p, String id, ArchiveSaveDTO i) {
        owner(p, find(p, id));
        archives.update(
                null,
                new LambdaUpdateWrapper<FamilyArchive>()
                        .eq(FamilyArchive::getId, id)
                        .eq(FamilyArchive::getHouseholdId, p.householdId())
                        .eq(FamilyArchive::getOwnerId, p.userId())
                        .set(FamilyArchive::getTitle, i.title())
                        .set(FamilyArchive::getCategory, i.category())
                        .set(FamilyArchive::getVisibility, i.visibility())
                        .set(
                                FamilyArchive::getEncryptedBody,
                                cipher.encrypt(i.body(), p.householdId() + ":" + id)));
        log(p, id, "UPDATE");
    }

    @Transactional
    public void delete(SessionPrincipal p, String id) {
        owner(p, find(p, id));
        archives.delete(readable(p).eq(FamilyArchive::getId, id));
        log(p, id, "DELETE");
    }

    public List<ArchiveAuditVO> audit(SessionPrincipal p, String id) {
        owner(p, find(p, id));
        return logs
                .selectList(
                        new LambdaQueryWrapper<ArchiveAccessLog>()
                                .eq(ArchiveAccessLog::getArchiveId, id)
                                .eq(ArchiveAccessLog::getHouseholdId, p.householdId())
                                .orderByDesc(ArchiveAccessLog::getId)
                                .last("LIMIT 100"))
                .stream()
                .map(ArchiveConverter::toVO)
                .toList();
    }
}
