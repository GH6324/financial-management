package com.family.finance.service.member;

import com.family.finance.repository.MemberReferenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.15 FR-383 · 删除前的引用扫描。
 *
 * <p>规矩很硬:**只有零引用的成员才允许删**。不做级联删除 —— 一个人身上挂着三年的流水,
 * 「删掉这个人」绝不能顺手把那三年的钱一起删了。有引用就只能归档。
 *
 * <p>扫描结果是给用户看的:哪张表、还剩几条,让人自己判断该不该去清理,
 * 而不是丢一句「无法删除」。
 */
@Service
@RequiredArgsConstructor
public class MemberReferenceScanner {

    private final MemberReferenceMapper mapper;

    /** 一处引用:面向用户的说法 + 条数。 */
    public record Ref(String label, int count) {}

    /** 扫描结果。{@link #deletable()} 为 true 才允许物理删除。 */
    public record Scan(List<Ref> refs, int total) {
        public boolean deletable() {
            return total == 0;
        }
    }

    public Scan scan(long familyId, long memberId) {
        List<Ref> all = List.of(
                new Ref("名下账户", mapper.countAccountOwner(memberId)),
                new Ref("填报的账户快照", mapper.countPeriodSnapshot(memberId)),
                new Ref("记的收支流水", mapper.countCashFlow(memberId)),
                new Ref("记的转账", mapper.countTransfer(memberId)),
                new Ref("待办(指派给 ta)", mapper.countTodoAssigned(memberId)),
                new Ref("待办(由 ta 完成)", mapper.countTodoDoneBy(memberId)),
                new Ref("月度填报完成记录", mapper.countPeriodCompletion(memberId)),
                new Ref("操作留痕", mapper.countAuditActor(memberId)),
                new Ref("反结账记录", mapper.countPeriodReopen(memberId)),
                new Ref("期间现金流", mapper.countPeriodMemberCashflow(memberId)),
                new Ref("估值变动事件", mapper.countStockValuationEvent(memberId)),
                new Ref("提醒发送记录", mapper.countReportReminderLog(memberId)),
                new Ref("教育目标里的孩子", mapper.countGoalChildRef(familyId, memberId)));

        List<Ref> hit = new ArrayList<>();
        int total = 0;
        for (Ref r : all) {
            total += r.count();
            if (r.count() > 0) hit.add(r);
        }
        return new Scan(hit, total);
    }
}
