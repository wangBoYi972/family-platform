package com.familyhome.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.familyhome.api.user.feign.UserFeignClient;
import com.familyhome.api.user.service.MemberCacheService;
import com.familyhome.api.user.vo.MemberApiVO;
import com.familyhome.common.api.ApiResponse;
import com.familyhome.common.api.PageQuery;
import com.familyhome.ledger.dto.LedgerEntrySaveDTO;
import com.familyhome.ledger.dto.LedgerSplitDTO;
import com.familyhome.ledger.service.LedgerService;
import com.familyhome.ledger.vo.LedgerEntryVO;
import com.familyhome.ledger.vo.LedgerMemberNetVO;
import com.familyhome.ledger.vo.LedgerSummaryVO;
import com.familyhome.security.SessionPrincipal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:ledger-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.cloud.nacos.discovery.enabled=false"
        })
@ActiveProfiles("local")
class LedgerIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("FAMILY_JWT_SECRET", () -> UUID.randomUUID() + UUID.randomUUID().toString());
    }

    @Autowired LedgerService service;
    @Autowired MemberCacheService memberCache;
    @MockBean UserFeignClient users;

    private final SessionPrincipal admin = new SessionPrincipal(11, 101, "ADMIN", "s", "family");
    private final SessionPrincipal mother = new SessionPrincipal(12, 101, "MEMBER", "s", "family");
    private final SessionPrincipal child = new SessionPrincipal(13, 101, "MEMBER", "s", "family");
    private final SessionPrincipal stranger = new SessionPrincipal(21, 102, "ADMIN", "s", "family");
    private final SessionPrincipal soloAdmin = new SessionPrincipal(31, 301, "ADMIN", "s", "family");
    private final SessionPrincipal sumAdmin = new SessionPrincipal(41, 401, "ADMIN", "s", "family");
    private final SessionPrincipal sumMother = new SessionPrincipal(42, 401, "MEMBER", "s", "family");
    private final SessionPrincipal sumChild = new SessionPrincipal(43, 401, "MEMBER", "s", "family");
    private final Map<Long, List<MemberApiVO>> roster = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        // 成员白名单由 user-svc 决定：这里按 bearer 里的家庭号返回，模拟「其他家庭 / 已停用成员查不到」。
        roster.put(101L, List.of(
                new MemberApiVO(11L, "爸爸", "ADMIN"),
                new MemberApiVO(12L, "妈妈", "MEMBER"),
                new MemberApiVO(13L, "孩子", "MEMBER")));
        roster.put(102L, List.of(new MemberApiVO(21L, "邻居", "ADMIN")));
        roster.put(301L, List.of(
                new MemberApiVO(31L, "甲", "ADMIN"),
                new MemberApiVO(32L, "乙", "MEMBER")));
        roster.put(401L, List.of(
                new MemberApiVO(41L, "甲", "ADMIN"),
                new MemberApiVO(42L, "乙", "MEMBER"),
                new MemberApiVO(43L, "丙", "MEMBER")));
        when(users.members(anyString())).thenAnswer(invocation -> {
            String bearer = invocation.getArgument(0);
            long householdId = Long.parseLong(bearer.substring(bearer.lastIndexOf('-') + 1));
            return ApiResponse.success(roster.getOrDefault(householdId, List.of()));
        });
    }

    private String bearer(SessionPrincipal p) {
        return "bearer-" + p.householdId();
    }

    private LedgerEntrySaveDTO expense(long cents, long payer, List<LedgerSplitDTO> splits) {
        return new LedgerEntrySaveDTO(
                "EXPENSE", cents, "餐饮", "晚饭", LocalDate.now().toString(), payer, splits);
    }

    private LedgerEntrySaveDTO income(long cents, long payee) {
        return new LedgerEntrySaveDTO(
                "INCOME", cents, "其他", "报销", LocalDate.now().toString(), payee, null);
    }

    @Test
    @DisplayName("分摊总和不等于账单时返回 400，且不写入任何账目")
    void splitMismatchIsRejected() {
        long before = countEntries(101L);
        assertThatThrownBy(() -> service.create(admin, bearer(admin),
                expense(1000, 11, List.of(new LedgerSplitDTO(11L, 600L), new LedgerSplitDTO(12L, 300L)))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);
        assertThat(countEntries(101L)).isEqualTo(before);
    }

    @Test
    @DisplayName("未知分类与浮点金额都进不来：分类返回 400，金额只接受整数分")
    void unknownCategoryAndFractionalCentsRejected() {
        LedgerEntrySaveDTO unknown = new LedgerEntrySaveDTO(
                "EXPENSE", 100L, "游戏", "充值", LocalDate.now().toString(), 11L,
                List.of(new LedgerSplitDTO(11L, 100L)));
        assertThatThrownBy(() -> service.create(admin, bearer(admin), unknown))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("跨家庭成员不能被分摊，已停用成员同样被拒绝")
    void crossHouseholdSplitRejected() {
        assertThatThrownBy(() -> service.create(admin, bearer(admin),
                expense(1000, 11, List.of(new LedgerSplitDTO(21L, 1000L)))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);
        roster.put(101L, List.of(new MemberApiVO(11L, "爸爸", "ADMIN")));
        memberCache.evict(101L);
        assertThatThrownBy(() -> service.create(admin, bearer(admin),
                expense(500, 11, List.of(new LedgerSplitDTO(13L, 500L)))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("成员只能改删自己的账，管理员可改删；跨家庭账目视为不存在")
    void onlyCreatorOrAdminCanModify() {
        LedgerEntryVO mine = service.create(mother, bearer(mother),
                expense(300, 12, List.of(new LedgerSplitDTO(12L, 300L))));
        assertThatThrownBy(() -> service.update(child, bearer(child), mine.id(),
                expense(300, 12, List.of(new LedgerSplitDTO(12L, 300L)))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(403);
        assertThatThrownBy(() -> service.delete(stranger, mine.id()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(404);
        service.update(mother, bearer(mother), mine.id(),
                new LedgerEntrySaveDTO("EXPENSE", 300L, "日用", "修改了", LocalDate.now().toString(), 12L,
                        List.of(new LedgerSplitDTO(12L, 300L))));
        service.update(admin, bearer(admin), mine.id(),
                new LedgerEntrySaveDTO("EXPENSE", 300L, "日用", "管理员也改", LocalDate.now().toString(), 12L,
                        List.of(new LedgerSplitDTO(12L, 300L))));
        service.delete(admin, mine.id());
        assertThatThrownBy(() -> service.delete(mother, mine.id()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("月度汇总按整月计算净额，且与分页列表的收支口径一致")
    void summaryMatchesListAndComputesNet() {
        // 用独立家庭，避免其他测试留下的账目影响绝对金额断言。
        String month = YearMonth.now().toString();
        service.create(sumAdmin, bearer(sumAdmin), expense(1000, 41, List.of(
                new LedgerSplitDTO(41L, 400L), new LedgerSplitDTO(42L, 300L), new LedgerSplitDTO(43L, 300L))));
        service.create(sumMother, bearer(sumMother), income(500, 42));
        // 上个月的不该混进本月汇总
        service.create(sumAdmin, bearer(sumAdmin), new LedgerEntrySaveDTO(
                "EXPENSE", 9000L, "住房", "上月房租", LocalDate.now().minusMonths(1).toString(), 41L,
                List.of(new LedgerSplitDTO(41L, 9000L))));

        LedgerSummaryVO s = service.summary(sumAdmin, bearer(sumAdmin), month);
        assertThat(s.month()).isEqualTo(month);
        assertThat(s.incomeCents()).isEqualTo(500);
        assertThat(s.expenseCents()).isEqualTo(1000);
        assertThat(s.members()).extracting("memberId").containsExactlyInAnyOrder(41L, 42L, 43L);
        assertThat(net(s, 41)).isEqualTo(600);   // 垫付 1000 - 自摊 400
        assertThat(net(s, 42)).isEqualTo(200);   // 收收入 500 - 自摊 300
        assertThat(net(s, 43)).isEqualTo(-300);  // 只承担分摊
        assertThat(s.members().stream().mapToLong(LedgerMemberNetVO::netCents).sum())
                .isEqualTo(s.incomeCents());    // 支出在成员之间内部抵消

        // 分页两页加总要和汇总一致；只拿第一页加总会漏掉后面的账目。
        List<LedgerEntryVO> page1 = service.list(sumAdmin, PageQuery.of(1, 1), month, null, null, null);
        List<LedgerEntryVO> page2 = service.list(sumAdmin, PageQuery.of(2, 1), month, null, null, null);
        List<LedgerEntryVO> all = new ArrayList<>(page1);
        all.addAll(page2);
        assertThat(all).hasSize(2);
        assertThat(all.stream().filter(e -> "INCOME".equals(e.type())).mapToLong(LedgerEntryVO::amountCents).sum())
                .isEqualTo(s.incomeCents());
        assertThat(all.stream().filter(e -> "EXPENSE".equals(e.type())).mapToLong(LedgerEntryVO::amountCents).sum())
                .isEqualTo(s.expenseCents());
    }

    @Test
    @DisplayName("金额全程用整数分：0.1 元 + 0.2 元等于 30 分，没有浮点尾巴")
    void centsKeepPrecision() {
        service.create(soloAdmin, bearer(soloAdmin), income(10, 31));
        service.create(soloAdmin, bearer(soloAdmin), income(20, 31));
        LedgerSummaryVO s = service.summary(soloAdmin, bearer(soloAdmin), YearMonth.now().toString());
        assertThat(s.incomeCents()).isEqualTo(30L);
        assertThat(net(s, 31)).isEqualTo(30L);
    }

    @Test
    @DisplayName("月份/分类/垫付人/关键字筛选下推数据库，关键字不会越过家庭隔离")
    void listFiltersAreGroupedAndPushedDown() {
        String tag = "筛选-" + UUID.randomUUID().toString().substring(0, 8);
        service.create(admin, bearer(admin), new LedgerEntrySaveDTO(
                "EXPENSE", 100L, "餐饮", tag + "-外卖", LocalDate.now().toString(), 11L,
                List.of(new LedgerSplitDTO(11L, 100L))));
        service.create(admin, bearer(admin), new LedgerEntrySaveDTO(
                "EXPENSE", 100L, "交通", tag + "-公交", LocalDate.now().toString(), 12L,
                List.of(new LedgerSplitDTO(12L, 100L))));
        service.create(stranger, bearer(stranger), new LedgerEntrySaveDTO(
                "EXPENSE", 100L, "餐饮", tag + "-邻居外卖", LocalDate.now().toString(), 21L,
                List.of(new LedgerSplitDTO(21L, 100L))));

        assertThat(service.list(admin, PageQuery.first(), null, null, null, tag))
                .extracting(LedgerEntryVO::id)
                .hasSize(2);
        assertThat(service.list(admin, PageQuery.first(), null, "餐饮", null, tag)).hasSize(1);
        assertThat(service.list(admin, PageQuery.first(), null, null, 12L, tag)).hasSize(1);
        assertThat(service.list(admin, PageQuery.first(), YearMonth.now().minusMonths(1).toString(), null, null, tag))
                .isEmpty();
        assertThatThrownBy(() -> service.list(admin, PageQuery.first(), null, "未知分类", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);
    }

    private long net(LedgerSummaryVO s, long memberId) {
        return s.members().stream()
                .filter(m -> m.memberId() == memberId)
                .findFirst()
                .orElseThrow()
                .netCents();
    }

    private long countEntries(long householdId) {
        return service.list(
                        new SessionPrincipal(0, householdId, "ADMIN", "s", "family"),
                        PageQuery.of(1, PageQuery.MAX_SIZE), null, null, null, null)
                .size();
    }
}
