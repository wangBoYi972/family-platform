package com.familyhome.file.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.common.api.PageQuery;
import com.familyhome.file.converter.FileConverter;
import com.familyhome.file.dto.FileUploadDTO;
import com.familyhome.file.mapper.FamilyFileMapper;
import com.familyhome.file.model.FamilyFile;
import com.familyhome.file.service.FileService;
import com.familyhome.file.storage.FileStorage;
import com.familyhome.file.vo.*;
import com.familyhome.security.SessionPrincipal;
import com.familyhome.messaging.OutboxEventStore;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FileServiceImpl implements FileService {
    @Autowired
    private FamilyFileMapper files;
    @Autowired
    private FileStorage storage;
    @Autowired
    private OutboxEventStore outbox;
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(FileServiceImpl.class);
    private static final Map<String, String> MIME =
            Map.ofEntries(
                    Map.entry("jpg", "image/jpeg"),
                    Map.entry("jpeg", "image/jpeg"),
                    Map.entry("png", "image/png"),
                    Map.entry("webp", "image/webp"),
                    Map.entry("pdf", "application/pdf"),
                    Map.entry("txt", "text/plain"),
                    Map.entry(
                            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                    Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                    Map.entry("mp3", "audio/mpeg"),
                    Map.entry("m4a", "audio/mp4"),
                    Map.entry("aac", "audio/aac"),
                    Map.entry("wav", "audio/wav"),
                    Map.entry("webm", "video/webm"),
                    Map.entry("mp4", "video/mp4"));

    private LambdaQueryWrapper<FamilyFile> readable(SessionPrincipal p) {
        return new LambdaQueryWrapper<FamilyFile>()
                .eq(FamilyFile::getHouseholdId, p.householdId())
                .eq(FamilyFile::getDeleted, false)
                .and(
                        q ->
                                q.eq(FamilyFile::getVisibility, "FAMILY")
                                        .or()
                                        .eq(FamilyFile::getOwnerId, p.userId()));
    }

    @Transactional(readOnly = true)
    public List<FileVO> list(SessionPrincipal p, PageQuery page) {
        return files
                .selectList(readable(p).orderByDesc(FamilyFile::getCreatedAt).last(page.limitClause()))
                .stream()
                .map(FileConverter::toVO)
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public FileVO upload(SessionPrincipal p, FileUploadDTO input) throws IOException {
        var file = input.file();
        String visibility = input.visibility();
        if (!Set.of("FAMILY", "PRIVATE").contains(visibility)
                || file.isEmpty()
                || file.getSize() > 20L * 1024 * 1024)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空、过大或权限不正确");
        String name =
                Optional.ofNullable(file.getOriginalFilename())
                        .orElse("附件")
                        .replace('\\', '_')
                        .replace('/', '_')
                        .replace('\r', '_')
                        .replace('\n', '_');
        if (name.length() > 200) name = name.substring(name.length() - 200);
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String mime = MIME.get(ext);
        if (mime == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持该文件类型");
        String id = UUID.randomUUID().toString();
        try {
            storage.save(id, file.getInputStream(), file.getSize());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文件保存失败，请稍后重试");
        }
        var f = new FamilyFile();
        f.setId(id);
        f.setHouseholdId(p.householdId());
        f.setOwnerId(p.userId());
        f.setName(name);
        f.setMime(mime);
        f.setSizeBytes(file.getSize());
        f.setVisibility(visibility);
        f.setDeleted(false);
        f.setCreatedAt(LocalDateTime.now());
        try {
            files.insert(f);
        } catch (RuntimeException ex) {
            try {
                storage.delete(id);
            } catch (IOException ignored) {
                // 元数据落库失败时尽力清理对象；清理失败交由运维手工回收。
            }
            throw ex;
        }
        outbox.append("file", id, "file.uploaded", p.householdId(),
                Map.of("fileId", id, "name", name, "mime", mime, "visibility", visibility));
        return FileConverter.toVO(f);
    }

    private FamilyFile find(SessionPrincipal p, String id) {
        if (!id.matches("[a-f0-9-]{36}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var f = files.selectOne(readable(p).eq(FamilyFile::getId, id));
        if (f == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在或无权访问");
        return f;
    }

    @Transactional(readOnly = true)
    public FileVO metadata(SessionPrincipal p, String id) {
        return FileConverter.toVO(find(p, id));
    }

    @Transactional(readOnly = true)
    public FileDownloadVO download(SessionPrincipal p, String id) {
        var f = find(p, id);
        try {
            Resource resource = storage.load(id);
            return new FileDownloadVO(f.getName(), resource);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件内容缺失");
        }
    }

    @Transactional
    public void delete(SessionPrincipal p, String id) {
        var f = find(p, id);
        if (!p.admin() && !Objects.equals(f.getOwnerId(), p.userId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅上传者或管理员可删除");
        files.update(
                null,
                new LambdaUpdateWrapper<FamilyFile>()
                        .eq(FamilyFile::getId, id)
                        .eq(FamilyFile::getHouseholdId, p.householdId())
                        .set(FamilyFile::getDeleted, true));
        // 元数据软删后必须回收对象存储，否则 blob 会永久残留占用空间。
        // 回收失败不能回滚软删（用户已看不到该文件），记录日志交由运维清理。
        try {
            storage.delete(id);
        } catch (IOException ex) {
            log.warn("对象存储清理失败，待手工回收 fileId={}", id, ex);
        }
        outbox.append("file", id, "file.deleted", p.householdId(), Map.of("fileId", id));
    }
}
