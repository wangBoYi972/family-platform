package com.familyhome.ledger.service;

import com.familyhome.common.api.PageQuery;
import com.familyhome.ledger.dto.LedgerEntrySaveDTO;
import com.familyhome.ledger.vo.LedgerEntryVO;
import com.familyhome.ledger.vo.LedgerSummaryVO;
import com.familyhome.security.SessionPrincipal;
import java.util.List;

public interface LedgerService {
    LedgerEntryVO create(SessionPrincipal p, String bearer, LedgerEntrySaveDTO input);

    LedgerEntryVO update(SessionPrincipal p, String bearer, long id, LedgerEntrySaveDTO input);

    void delete(SessionPrincipal p, long id);

    List<LedgerEntryVO> list(
            SessionPrincipal p,
            PageQuery page,
            String month,
            String category,
            Long payerId,
            String keyword);

    LedgerSummaryVO summary(SessionPrincipal p, String bearer, String month);
}
