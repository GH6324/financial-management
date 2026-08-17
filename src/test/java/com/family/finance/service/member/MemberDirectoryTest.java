package com.family.finance.service.member;

import com.family.finance.domain.member.Member;
import com.family.finance.repository.MemberMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.15 FR-382 · 成员名录三个出口的语义,一个一个钉住。
 *
 * <p>这三条差别看着细,踩起来一点都不细:
 * <ul>
 *   <li>{@code nameMap} 漏了归档的人 → 历史数据里他变成「成员#7」,脱敏时真名漏进 LLM</li>
 *   <li>{@code selectableWith} 漏了当前值 → 编辑表单一保存,这条记录被静默改派给别人</li>
 *   <li>{@code listAll} 排序不稳 → 按序号分配的头像色整体错位</li>
 * </ul>
 */
class MemberDirectoryTest {

    private final MemberMapper mapper = mock(MemberMapper.class);
    private final MemberDirectory directory = new MemberDirectory(mapper);

    private static Member active(long id, String name) {
        return Member.builder().id(id).familyId(1L).username("u" + id).displayName(name).build();
    }

    private static Member archived(long id, String name) {
        return Member.builder().id(id).familyId(1L).username("u" + id).displayName(name)
                .archivedAt(LocalDateTime.of(2026, 8, 15, 10, 0)).build();
    }

    @Test
    void nameMap_includesArchivedMembers() {
        when(mapper.findAllByFamily(1L)).thenReturn(List.of(active(1, "张三"), archived(2, "李四")));

        var names = directory.nameMap(1L);

        // 归档的李四必须还在映射里 —— 他名下的账户/流水没消失,名字就不该消失
        assertThat(names).containsEntry(1L, "张三").containsEntry(2L, "李四");
    }

    @Test
    void selectableWith_currentIsActive_returnsPlainActiveList() {
        var actives = List.of(active(1, "张三"), active(2, "李四"));
        when(mapper.findActiveByFamily(1L)).thenReturn(actives);

        assertThat(directory.selectableWith(1L, 2L)).containsExactlyElementsOf(actives);
    }

    @Test
    void selectableWith_nullCurrent_returnsPlainActiveList() {
        var actives = List.of(active(1, "张三"));
        when(mapper.findActiveByFamily(1L)).thenReturn(actives);

        // 「共同 / 未指定」是合法的当前值,不需要往里塞任何人
        assertThat(directory.selectableWith(1L, null)).containsExactlyElementsOf(actives);
    }

    @Test
    void selectableWith_currentIsArchived_appendsThatMember() {
        when(mapper.findActiveByFamily(1L)).thenReturn(List.of(active(1, "张三")));
        when(mapper.findAllByFamily(1L)).thenReturn(List.of(active(1, "张三"), archived(2, "李四")));

        var options = directory.selectableWith(1L, 2L);

        // 李四已归档,但这条记录当前就挂在他身上 —— 下拉里没有他,一保存就被改派给张三
        assertThat(options).extracting(Member::getId).containsExactly(1L, 2L);
        assertThat(options.get(1).isArchived()).isTrue();
    }

    @Test
    void selectableWith_currentIsGone_doesNotBlowUp() {
        when(mapper.findActiveByFamily(1L)).thenReturn(List.of(active(1, "张三")));
        when(mapper.findAllByFamily(1L)).thenReturn(List.of(active(1, "张三")));

        // 引用指向一个已被物理删除的 id(v1.15 允许零引用删除,理论上不该出现,但不能因此 500)
        assertThat(directory.selectableWith(1L, 99L)).extracting(Member::getId).containsExactly(1L);
    }
}
