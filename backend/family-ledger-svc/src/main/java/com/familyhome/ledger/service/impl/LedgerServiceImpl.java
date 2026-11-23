package com.familyhome.ledger.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.api.user.service.MemberCacheService;
import com.familyhome.api.user.vo.MemberApiVO;
import com.familyhome.common.api.PageQuery;
import com.familyhome.ledger.dto.LedgerEntrySaveDTO;
import com.familyhome.ledger.dto.LedgerSplitDTO;
import com.familyhome.ledger.mapper.LedgerEntryMapper;
import com.familyhome.ledger.mapper.LedgerSplitMapper;
import com.familyhome.ledger.model.LedgerEntry;
import com.familyhome.ledger.model.LedgerSplit;
import com.familyhome.ledger.service.LedgerService;
import com.familyhome.ledger.vo.LedgerEntryVO;
import com.familyhome.ledger.vo.LedgerMemberNetVO;
import com.familyhome.ledger.vo.LedgerSplitVO;
import com.familyhome.ledger.vo.LedgerSummaryVO;
import com.familyhome.security.SessionPrincipal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LedgerServiceImpl implements LedgerService {
    /** 分类先固定这七类；未知分类一律 400，不悄悄落到「其他」。 */
    private static final Set<String> CATEGORIES =
            Set.of("餐饮", "日用", "住房", "交通", "医疗", "教育", "其他");
    private static final Set<String> TYPES = Set.of("EXPENSE", "INCOME");

    @Autowired private LedgerEntryMapper entries;
    @Autowired private LedgerSplitMapper splits;
    @Autowired private MemberCacheService memberCache;

    @Override
    @Transactional
    public LedgerEntryVO create(SessionPrincipal p, String bearer, LedgerEntrySaveDTO input) {
        return write(p, bearer, null, input);
    }

    @Override
    @Transactional
    public LedgerEntryVO update(SessionPrincipal p, String bearer, long id, LedgerEntrySaveDTO input) {
        own(p, id);
        return write(p, bearer, id, input);
    }

    @Override
    @Transactional
    public void delete(SessionPrincipal p, long id) {
        own(p, id);
        // 外键有级联删除，仍显式清理一次：孤立分摊行不能靠数据库隐式行为兜底。
        splits.delete(splitScope(p, id));
        entries.delete(entryScope(p).eq(LedgerEntry::getId, id));
    }

    @Override
    public List<LedgerEntryVO> list(
            SessionPrincipal p,
            PageQuery page,
            String month,
            String category,
            Long payerId,
            String keyword) {
        var w = entryScope(p);
        // 权限条件自成一组。和关键字的 OR 并列时必须用 and(...) 隔开，否则会把别家账目带出来。
        w.and(x -> x.eq(LedgerEntry::getHouseholdId, p.householdId()));
        if (month != null && !month.isBlank()) {
            YearMonth ym = parseMonth(month);
            w.ge(LedgerEntry::getOccurredOn, ym.atDay(1)).lt(LedgerEntry::getOccurredOn, ym.plusMonths(1).atDay(1));
        }
        String cat = filterCategory(category);
        if (cat != null) w.eq(LedgerEntry::getCategory, cat);
        if (payerId != null) {
            if (payerId < 1) throw bad("垫付人参数不合法");
            w.eq(LedgerEntry::getPayerId, payerId);
        }
        String text = keyword == null ? "" : keyword.trim();
        if (!text.isEmpty()) {
            // 关键字也必须自成一组：note OR category 不能跟家庭/筛选条件混成一片。
            w.and(x -> x.like(LedgerEntry::getNote, text).or().like(LedgerEntry::getCategory, text));
        }
        List<LedgerEntry> rows =
                entries.selectList(
                        w.orderByDesc(LedgerEntry::getOccurredOn)
                                .orderByDesc(LedgerEntry::getId)
                                .last(page.limitClause()));
        if (rows.isEmpty()) return List.of();
        List<Long> ids = rows.stream().map(LedgerEntry::getId).toList();
        List<LedgerSplit> allSplits =
                splits.selectList(
                        new LambdaQueryWrapper<LedgerSplit>()
                                .eq(LedgerSplit::getHouseholdId, p.householdId())
                                .in(LedgerSplit::getEntryId, ids));
        Map<Long, List<LedgerSplitVO>> grouped = new LinkedHashMap<>();
        allSplits.forEach(
                s ->
                        grouped
                                .computeIfAbsent(s.getEntryId(), k -> new ArrayList<>())
                                .add(new LedgerSplitVO(s.getMemberId(), s.getAmountCents())));
        return rows.stream().map(e -> toVO(e, grouped.getOrDefault(e.getId(), List.of()))).toList();
    }

    @Override
    public LedgerSummaryVO summary(SessionPrincipal p, String bearer, String month) {
        YearMonth ym = parseMonth(month == null || month.isBlank() ? null : month);
        LocalDate start = ym.atDay(1);
        LocalDate next = ym.plusMonths(1).atDay(1);
        List<LedgerEntry> monthEntries =
                entries.selectList(
                        entryScope(p)
                                .ge(LedgerEntry::getOccurredOn, start)
                                .lt(LedgerEntry::getOccurredOn, next));
        // 汇总读整月账目（不受分页影响），分摊一次 IN 查询取回，避免逐条往返。
        Map<Long, List<LedgerSplit>> byEntry = new LinkedHashMap<>();
        if (!monthEntries.isEmpty()) {
            List<Long> ids = monthEntries.stream().map(LedgerEntry::getId).toList();
            splits
                    .selectList(
                            new LambdaQueryWrapper<LedgerSplit>()
                                    .eq(LedgerSplit::getHouseholdId, p.householdId())
                                    .in(LedgerSplit::getEntryId, ids))
                    .forEach(s -> byEntry.computeIfAbsent(s.getEntryId(), k -> new ArrayList<>()).add(s));
        }

        Map<Long, LedgerMemberNet> nets = new LinkedHashMap<>();
        // 当前启用成员即使本月没有账也要出现，净额为 0。
        for (MemberApiVO member : memberCache.getMembers(bearer, p.householdId())) {
            nets.put(member.id(), new LedgerMemberNet(member.displayName()));
        }
        long incomeTotal = 0;
        long expenseTotal = 0;
        for (LedgerEntry e : monthEntries) {
            long amount = e.getAmountCents();
            LedgerMemberNet payer = nets.computeIfAbsent(e.getPayerId(), id -> new LedgerMemberNet("已停用成员"));
            if ("INCOME".equals(e.getType())) {
                incomeTotal = add(incomeTotal, amount);
                payer.incomeCents = add(payer.incomeCents, amount);
                continue;
            }
            expenseTotal = add(expenseTotal, amount);
            payer.paidCents = add(payer.paidCents, amount);
            List<LedgerSplit> parts = byEntry.getOrDefault(e.getId(), List.of());
            if (parts.isEmpty()) {
                // 历史脏数据兜底：没有分摊行时不能让净额总和对不上支出总额。
                payer.shareCents = add(payer.shareCents, amount);
            }
            for (LedgerSplit s : parts) {
                LedgerMemberNet member =
                        nets.computeIfAbsent(s.getMemberId(), id -> new LedgerMemberNet("已停用成员"));
                member.shareCents = add(member.shareCents, s.getAmountCents());
            }
        }
        List<LedgerMemberNetVO> members = new ArrayList<>();
        nets.forEach(
                (id, net) ->
                        members.add(
                                new LedgerMemberNetVO(
                                        id,
                                        net.displayName,
                                        net.incomeCents,
                                        net.paidCents,
                                        net.shareCents,
                                        net.incomeCents + net.paidCents - net.shareCents)));
        return new LedgerSummaryVO(ym.toString(), incomeTotal, expenseTotal, members);
    }

    private LedgerEntryVO write(
            SessionPrincipal p, String bearer, Long id, LedgerEntrySaveDTO input) {
        Validated v = validate(p, bearer, input);
        LedgerEntry e = id == null ? new LedgerEntry() : entries.selectById(id);
        if (id != null && e == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账目不存在");
        e.setHouseholdId(p.householdId());
        e.setType(v.type);
        e.setAmountCents(v.amountCents);
        e.setCategory(v.category);
        e.setNote(v.note);
        e.setOccurredOn(v.occurredOn);
        e.setPayerId(v.payerId);
        if (id == null) {
            // 记账人只来自会话；DTO 里没有这个字段可传。
            e.setCreatedBy(p.userId());
            e.setCreatedAt(java.time.LocalDateTime.now());
            entries.insert(e);
        } else if (entries.update(e, entryScope(p).eq(LedgerEntry::getId, id)) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账目已更新，请刷新后重试");
        }
        splits.delete(splitScope(p, e.getId()));
        for (LedgerSplitDTO s : v.splits) {
            LedgerSplit row = new LedgerSplit();
            row.setEntryId(e.getId());
            row.setHouseholdId(p.householdId());
            row.setMemberId(s.memberId());
            row.setAmountCents(s.amountCents());
            splits.insert(row);
        }
        return toVO(
                e,
                v.splits.stream()
                        .map(s -> new LedgerSplitVO(s.memberId(), s.amountCents()))
                        .toList());
    }

    private Validated validate(SessionPrincipal p, String bearer, LedgerEntrySaveDTO i) {
        if (i == null) throw bad("请填写账本信息");
        String type = i.type() == null ? "" : i.type().trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw bad("类型只能是 EXPENSE 或 INCOME");
        if (i.amountCents() == null || i.amountCents() < 1)
            throw bad("金额必须是以分为单位的正整数");
        String category = normalizeCategory(i.category());
        String note = i.note() == null ? "" : i.note().trim();
        if (note.length() > 200) throw bad("备注不能超过 200 字");
        LocalDate date;
        try {
            date = LocalDate.parse(i.occurredOn());
        } catch (DateTimeParseException | NullPointerException ex) {
            throw bad("发生日期应为 yyyy-MM-dd");
        }
        if (i.payerId() == null || i.payerId() < 1)
            throw bad("请选择" + ("EXPENSE".equals(type) ? "垫付人" : "收款人"));
        // 成员资格由 user-svc 的启用白名单决定；其他家庭或已停用成员都不会出现在这里。
        List<MemberApiVO> members = memberCache.getMembers(bearer, p.householdId());
        memberOf(members, i.payerId(), "EXPENSE".equals(type) ? "垫付人" : "收款人");

        List<LedgerSplitDTO> source = i.splits() == null ? List.of() : i.splits();
        List<LedgerSplitDTO> accepted = new ArrayList<>();
        if ("INCOME".equals(type)) {
            if (!source.isEmpty()) throw bad("收入记在收款人名下，不需要分摊");
        } else {
            if (source.isEmpty()) throw bad("支出至少需要一名分摊人");
            Set<Long> seen = new HashSet<>();
            long sum = 0;
            for (LedgerSplitDTO s : source) {
                if (s == null || s.memberId() == null || s.amountCents() == null || s.amountCents() < 1)
                    throw bad("分摊人及金额必须有效，且金额以分为单位的正整数");
                if (!seen.add(s.memberId())) throw bad("同一成员不能重复分摊");
                memberOf(members, s.memberId(), "分摊人");
                sum = add(sum, s.amountCents());
                accepted.add(s);
            }
            if (sum != i.amountCents())
                throw bad("分摊总和必须等于账单金额：当前 " + sum + " 分，账单 " + i.amountCents() + " 分");
        }
        return new Validated(type, i.amountCents(), category, note, date, i.payerId(), accepted);
    }

    private LedgerEntry own(SessionPrincipal p, long id) {
        LedgerEntry e = entries.selectOne(entryScope(p).eq(LedgerEntry::getId, id));
        if (e == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账目不存在");
        // 只有记账人本人或管理员可改删；同家庭其他成员只读。
        if (!p.admin() && !Objects.equals(e.getCreatedBy(), p.userId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅记账人或管理员可修改、删除账目");
        return e;
    }

    private LambdaQueryWrapper<LedgerEntry> entryScope(SessionPrincipal p) {
        return new LambdaQueryWrapper<LedgerEntry>().eq(LedgerEntry::getHouseholdId, p.householdId());
    }

    private LambdaQueryWrapper<LedgerSplit> splitScope(SessionPrincipal p, long entryId) {
        return new LambdaQueryWrapper<LedgerSplit>()
                .eq(LedgerSplit::getHouseholdId, p.householdId())
                .eq(LedgerSplit::getEntryId, entryId);
    }

    private LedgerEntryVO toVO(LedgerEntry e, List<LedgerSplitVO> parts) {
        return new LedgerEntryVO(
                e.getId(),
                e.getType(),
                e.getAmountCents(),
                e.getCategory(),
                e.getNote(),
                e.getOccurredOn(),
                e.getCreatedBy(),
                e.getPayerId(),
                parts);
    }

    private String normalizeCategory(String category) {
        String value = category == null ? "" : category.trim();
        if (!CATEGORIES.contains(value))
            throw bad("未知分类：" + (value.isEmpty() ? "空" : value) + "；可选：" + String.join("、", CATEGORIES));
        return value;
    }

    private String filterCategory(String category) {
        String value = category == null ? "" : category.trim();
        if (value.isEmpty() || "ALL".equalsIgnoreCase(value)) return null;
        return normalizeCategory(value);
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) return YearMonth.now();
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException ex) {
            throw bad("月份格式应为 yyyy-MM");
        }
    }

    private void memberOf(List<MemberApiVO> members, long memberId, String label) {
        boolean found = members.stream().anyMatch(m -> m.id() != null && m.id() == memberId);
        if (!found) throw bad(label + "必须是本家庭启用成员");
    }

    /** 分是整数，溢出也要显式拒绝，不能用 long 回绕制造「负金额」。 */
    private long add(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException ex) {
            throw bad("金额总和超出可表示范围");
        }
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record Validated(
            String type,
            long amountCents,
            String category,
            String note,
            LocalDate occurredOn,
            long payerId,
            List<LedgerSplitDTO> splits) {}

    /** 汇总用的可变累加器，只在服务端内存中存在，不返回给客户端。 */
    private static class LedgerMemberNet {
        final String displayName;
        long incomeCents;
        long paidCents;
        long shareCents;

        LedgerMemberNet(String displayName) {
            this.displayName = displayName;
        }
    }
}
