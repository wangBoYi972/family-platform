package com.familyhome.ledger.controller;

import com.familyhome.common.api.ApiResponse;
import com.familyhome.common.api.PageQuery;
import com.familyhome.ledger.dto.LedgerEntrySaveDTO;
import com.familyhome.ledger.service.LedgerService;
import com.familyhome.ledger.vo.LedgerEntryVO;
import com.familyhome.ledger.vo.LedgerSummaryVO;
import com.familyhome.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledger")
public class LedgerController {
    @Autowired private LedgerService service;

    @GetMapping("/entries")
    public ApiResponse<List<LedgerEntryVO>> list(
            HttpServletRequest r,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long payerId,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(
                service.list(
                        SessionPrincipal.from(r),
                        PageQuery.of(page, size),
                        month,
                        category,
                        payerId,
                        keyword));
    }

    /** 月度汇总独立接口：服务端基于整月数据计算，绝不让前端拿一页数据自己加总。 */
    @GetMapping("/summary")
    public ApiResponse<LedgerSummaryVO> summary(
            HttpServletRequest r, @RequestParam(required = false) String month) {
        return ApiResponse.success(
                service.summary(SessionPrincipal.from(r), r.getHeader("Authorization"), month));
    }

    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LedgerEntryVO> create(
            HttpServletRequest r, @Valid @RequestBody LedgerEntrySaveDTO input) {
        return ApiResponse.success(
                service.create(SessionPrincipal.from(r), r.getHeader("Authorization"), input));
    }

    @PutMapping("/entries/{id}")
    public ApiResponse<LedgerEntryVO> update(
            HttpServletRequest r,
            @PathVariable long id,
            @Valid @RequestBody LedgerEntrySaveDTO input) {
        return ApiResponse.success(
                service.update(SessionPrincipal.from(r), r.getHeader("Authorization"), id, input));
    }

    @DeleteMapping("/entries/{id}")
    public ApiResponse<Void> delete(HttpServletRequest r, @PathVariable long id) {
        service.delete(SessionPrincipal.from(r), id);
        return ApiResponse.success(null);
    }
}
