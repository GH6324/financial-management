package com.family.finance.service;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.SnapshotTodoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NavService {

    private final FamilyService familyService;
    private final PeriodService periodService;
    private final SnapshotTodoMapper snapshotTodoMapper;

    public NavState load(MemberPrincipal me) {
        Family family = familyService.require(me.getFamilyId());
        Period period = periodService.findBalancePeriod(me.getFamilyId()).orElse(null);
        int pending = period == null ? 0 : snapshotTodoMapper.countPendingByPeriod(period.getId());
        String label = period == null
                ? "未开期"
                : "%d · %02d · %s".formatted(
                        period.getPeriodStart().getYear(),
                        period.getPeriodStart().getMonthValue(),
                        period.getStatus());
        return new NavState(family, period, pending, label);
    }
}
