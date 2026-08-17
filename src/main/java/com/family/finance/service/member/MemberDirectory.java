package com.family.finance.service.member;

import com.family.finance.domain.member.Member;
import com.family.finance.repository.MemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.15 FR-382 · 成员名录 —— **展示历史数据里的人名时,唯一允许走的出口**。
 *
 * <p>问题的形状:{@code memberMapper.findActiveByFamily()} 只返回未归档成员,
 * 而账户、流水、快照、提醒记录都可能挂在一个已经归档的人身上。
 * 谁拿仅活跃列表去拼 {@code id → 名字} 的映射,谁就会在归档之后把那个人的名字变成空白 ——
 * 账户主理人显示成「共同」、审计日志的「由谁」列空掉、脱敏时真名漏进 LLM prompt。
 *
 * <p>所以这里不是「多一个方法」,而是把「拿全体成员」这件事收口成一个类:
 * 展示型调用方持这个 facade,面向未来的选择/分母(下拉框、代签名单、完成率分母)才留在
 * {@code findActiveByFamily}。护栏 {@code v115-MEMBER-NAME-MAP-INCLUDES-ARCHIVED}
 * 用**白名单**把后者钉死在那几个文件里 —— 是「禁止旁路」,不是「检测坏写法」。
 */
@Service
@RequiredArgsConstructor
public class MemberDirectory {

    private final MemberMapper memberMapper;

    /** 全体成员(含已归档)· 按 id 序 —— 顺序稳定,这样按序号分配颜色的地方不会因为归档而整体错位。 */
    public List<Member> listAll(long familyId) {
        return memberMapper.findAllByFamily(familyId);
    }

    /** id → 显示名(含已归档)。历史数据的名字映射一律用这个。 */
    public Map<Long, String> nameMap(long familyId) {
        Map<Long, String> m = new LinkedHashMap<>();
        for (Member x : listAll(familyId)) {
            m.put(x.getId(), x.getDisplayName());
        }
        return m;
    }

    /**
     * 编辑表单的候选列表:<b>活跃成员 ∪ 当前已选中的那个人</b>。
     *
     * <p>为什么不是纯活跃列表:账户的主理人、教育目标的孩子,都可能指向一个已归档的成员。
     * 下拉里没有他 → 表单一提交,这条记录就被**静默改派**给了别人(或变成「共同」)——
     * 用户只是想改个别的字段。归档在 v1.15 之前没有入口,所以这条路以前走不到;
     * 一旦归档成为一键操作,它就是必然会踩的坑。
     *
     * <p>已归档的那个人仍然排在末尾且带得出 {@code archived} 标记,模板负责标注「已归档」。
     */
    public List<Member> selectableWith(long familyId, Long currentId) {
        List<Member> active = memberMapper.findActiveByFamily(familyId);
        if (currentId == null || active.stream().anyMatch(m -> currentId.equals(m.getId()))) {
            return active;
        }
        List<Member> out = new java.util.ArrayList<>(active);
        listAll(familyId).stream()
                .filter(m -> currentId.equals(m.getId()))
                .findFirst()
                .ifPresent(out::add);
        return out;
    }
}
