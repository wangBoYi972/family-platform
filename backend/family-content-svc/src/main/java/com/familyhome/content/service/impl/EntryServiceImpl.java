package com.familyhome.content.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.api.file.feign.FileFeignClient;
import com.familyhome.common.api.PageQuery;
import com.familyhome.content.converter.ContentConverter;
import com.familyhome.content.dto.EntrySaveDTO;
import com.familyhome.content.mapper.AlbumMapper;
import com.familyhome.content.mapper.CalendarEntryMapper;
import com.familyhome.content.mapper.DynamicEntryMapper;
import com.familyhome.content.mapper.DynamicLikeMapper;
import com.familyhome.content.mapper.NoticeMapper;
import com.familyhome.content.model.Album;
import com.familyhome.content.model.CalendarEntry;
import com.familyhome.content.model.ContentEntry;
import com.familyhome.content.model.DynamicEntry;
import com.familyhome.content.model.Notice;
import com.familyhome.content.service.ContentFileMappingService;
import com.familyhome.content.service.EntryService;
import com.familyhome.content.vo.EntryVO;
import com.familyhome.security.SessionPrincipal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PostConstruct;

@Service
public class EntryServiceImpl implements EntryService {
    @Autowired private NoticeMapper notices;
    @Autowired private CalendarEntryMapper calendar;
    @Autowired private DynamicEntryMapper dynamics;
    @Autowired private AlbumMapper albums;
    @Autowired private DynamicLikeMapper likes;
    @Autowired private FileFeignClient files;
    @Autowired private ContentFileMappingService links;
    private Map<String, BaseMapper<? extends ContentEntry>> mappers;

    @PostConstruct
    void initializeMappers() {
        mappers = Map.of("notices", notices, "calendar", calendar, "dynamics", dynamics, "albums", albums);
    }

    @SuppressWarnings("unchecked")
    private BaseMapper<ContentEntry> mapper(String kind) {
        var m = mappers.get(kind);
        if (m == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return (BaseMapper<ContentEntry>) m;
    }

    private QueryWrapper<ContentEntry> scope(SessionPrincipal p) {
        return new QueryWrapper<ContentEntry>().eq("household_id", p.householdId());
    }

    @Transactional(readOnly = true)
    public List<EntryVO> list(SessionPrincipal p, String kind, PageQuery page, String keyword) {
        QueryWrapper<ContentEntry> w = scope(p).eq(!p.admin(), "visibility", "FAMILY");
        String text = keyword == null ? "" : keyword.trim();
        if (!text.isEmpty())
            // 括号不能省：不加 and() 会让关键字和可见性条件用 OR 拼起来，私有内容会被搜出来
            w.and(x -> x.like("title", text).or().like("body", text));
        List<ContentEntry> rows =
                mapper(kind)
                        .selectList(w.orderByDesc("pinned", "created_at", "id").last(page.limitClause()));
        // 关联关系以 content_file_mapping 为准，一次批量查出后回填到实体；
        // 映射表没有记录的历史行保留旧列 file_ids 的原值（回退读）。
        Map<Long, String> linked =
                links.load(kind, rows.stream().map(ContentEntry::getId).toList());
        if (!linked.isEmpty()) {
            for (ContentEntry row : rows) {
                String fromMapping = linked.get(row.getId());
                if (fromMapping != null) row.setFileIds(fromMapping);
            }
        }
        return rows.stream().map(ContentConverter::toVO).toList();
    }

    private void validate(SessionPrincipal p, String bearer, String kind, EntrySaveDTO i) {
        if (kind.equals("notices") || i.pinned() || "PRIVATE".equals(i.visibility())) p.requireAdmin();
        if (kind.equals("calendar") && i.eventDate() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择事件日期");
        if (i.fileIds() != null && !i.fileIds().isBlank()) {
            var ids = i.fileIds().split(",");
            if (ids.length > 30) throw new IllegalArgumentException("文件数量不能超过 30 个");
            for (String id : ids) {
                if (!id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("无效的文件 ID 格式");
                var file = files.metadata(bearer, id).data();
                if (!"PRIVATE".equals(i.visibility()) && !"FAMILY".equals(file.visibility()))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "共享内容只能引用共享文件");
                if (kind.equals("albums") && !file.mime().startsWith("image/"))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "相册仅支持图片");
            }
        }
    }

    @Transactional
    public EntryVO create(SessionPrincipal p, String bearer, String kind, EntrySaveDTO i) {
        validate(p, bearer, kind, i);
        ContentEntry e =
                switch (kind) {
                    case "notices" -> new Notice();
                    case "calendar" -> new CalendarEntry();
                    case "dynamics" -> new DynamicEntry();
                    case "albums" -> new Album();
                    default -> throw new IllegalArgumentException();
                };
        e.setHouseholdId(p.householdId());
        e.setCreatorId(p.userId());
        e.setTitle(i.title().trim());
        e.setBody(i.body());
        e.setEventDate(i.eventDate());
        e.setYearly(i.yearly());
        e.setPinned(i.pinned());
        e.setVisibility(i.visibility() == null ? "FAMILY" : i.visibility());
        e.setFileIds(i.fileIds());
        e.setLikes(0);
        e.setCreatedAt(LocalDateTime.now());
        mapper(kind).insert(e);
        // 自增 id 回填后才能写关联表；旧列同时保留，兼容未升级的读者。
        links.replace(kind, e.getId(), i.fileIds());
        return ContentConverter.toVO(e);
    }

    private void own(SessionPrincipal p, String kind, long id) {
        var e = mapper(kind).selectOne(scope(p).eq("id", id));
        if (e == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在");
        if (!p.admin() && (kind.equals("notices") || !Objects.equals(e.getCreatorId(), p.userId())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅创建者或管理员可修改");
    }

    @Transactional
    public void update(SessionPrincipal p, String bearer, String kind, long id, EntrySaveDTO i) {
        own(p, kind, id);
        validate(p, bearer, kind, i);
        mapper(kind)
                .update(
                        null,
                        new UpdateWrapper<ContentEntry>()
                                .eq("id", id)
                                .eq("household_id", p.householdId())
                                .set("title", i.title().trim())
                                .set("body", i.body())
                                .set("event_date", i.eventDate())
                                .set("yearly", i.yearly())
                                .set("pinned", i.pinned())
                                .set("visibility", i.visibility() == null ? "FAMILY" : i.visibility())
                                .set("file_ids", i.fileIds()));
        links.replace(kind, id, i.fileIds());
    }

    @Transactional
    public void delete(SessionPrincipal p, String kind, long id) {
        own(p, kind, id);
        if (kind.equals("dynamics")) {
            likes.lock(id, p.householdId());
            likes.delete(id);
        }
        mapper(kind).delete(scope(p).eq("id", id));
        // 删除内容时同步清掉关联，避免映射表留下指向已删内容的孤儿行
        links.remove(kind, id);
    }

    @Transactional
    public void like(SessionPrincipal p, String kind, long id) {
        if (!kind.equals("dynamics") || likes.lock(id, p.householdId()) == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "动态不存在");
        if (likes.count(id, p.userId()) == 0) {
            likes.insert(id, p.userId());
            likes.increment(id, p.householdId());
        }
    }
}
