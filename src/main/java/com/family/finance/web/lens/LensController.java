package com.family.finance.web.lens;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.calc.lens.LensQuery;
import com.family.finance.calc.lens.LensRegistry;
import com.family.finance.calc.lens.PivotEngine;
import com.family.finance.calc.lens.Position;
import com.family.finance.domain.lens.LensBoard;
import com.family.finance.repository.LensBoardMapper;
import com.family.finance.service.NavService;
import com.family.finance.service.lens.LensQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * v1.1 · 资产透视 · 统一多维查询网关(tech-design v1.1 决策 3)。
 *
 * <p>{@code POST /lens/query} 是<b>唯一</b>分析接口:旭日 / 交叉透视表 / 明细 / 预设与自定义看板
 * 全部是它的客户端;新组件 = 新 query spec,不加后端。响应携带头寸目录(catalog),
 * cells 用索引引用 → 明细零额外请求。</p>
 */
@Controller
@RequiredArgsConstructor
public class LensController {

    private final LensQueryService lensQueryService;
    private final LensBoardMapper lensBoardMapper;
    private final NavService navService;
    private final ObjectMapper objectMapper;
    private final com.family.finance.service.lens.LensMetaService lensMetaService;

    /** 透视页壳(组件渲染由 static/js/lens.js 走 /lens/query) */
    @GetMapping("/lens")
    public String page(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        lensMetaService.addMeta(me.getFamilyId(), model);
        return "lens/index";
    }

    /** 头寸目录条目(供明细钻取 · accountId 直链账户详情页) */
    public record PosView(long accountId, String label, String accountName, boolean holding,
                          BigDecimal value, String industry, String platform) {}

    public record LensView(PivotEngine.Result result, List<PosView> positions) {}

    @PostMapping("/lens/query")
    @ResponseBody
    public LensView query(@AuthenticationPrincipal MemberPrincipal me, @RequestBody LensQuery q) {
        List<Position> ps = lensQueryService.positions(me.getFamilyId());
        PivotEngine.Result result = PivotEngine.pivot(ps, q);
        List<PosView> catalog = ps.stream()
                .map(p -> new PosView(p.accountId(), p.label(), p.accountName(), p.isHolding(),
                        p.valueBase(), p.industry(), p.platform()))
                .toList();
        return new LensView(result, catalog);
    }

    /** 存自定义看板(spec 服务端校验可解析 + 维度/度量合法,防脏 JSON 入库) */
    @PostMapping("/lens/boards")
    public String saveBoard(@AuthenticationPrincipal MemberPrincipal me,
                            @RequestParam String name,
                            @RequestParam String specJson) throws Exception {
        LensQuery spec = objectMapper.readValue(specJson, LensQuery.class);
        spec.rowsSafe().forEach(LensRegistry::requireDim);
        spec.colsSafe().forEach(LensRegistry::requireDim);
        spec.measuresSafe().forEach(LensRegistry::requireMeasure);
        String safeName = name == null || name.isBlank() ? "我的看板" : name.trim();
        if (safeName.length() > 20) safeName = safeName.substring(0, 20);
        lensBoardMapper.insert(LensBoard.builder()
                .familyId(me.getFamilyId())
                .name(safeName)
                .specJson(objectMapper.writeValueAsString(spec))
                .displayOrder(100)
                .build());
        return "redirect:/lens";
    }

    @PostMapping("/lens/boards/{id}/delete")
    public String deleteBoard(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id) {
        lensBoardMapper.delete(me.getFamilyId(), id);
        return "redirect:/lens";
    }
}
