package com.family.finance.web.holdingimport;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.holdingimport.HoldingImport;
import com.family.finance.domain.holdingimport.HoldingImportItem;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.holdingimport.HoldingImportService;
import com.family.finance.service.holdingimport.QwenVisionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v1.4 · 持仓截图导入 · 填报页选中账户后进入。
 *
 * <p>页面 GET /entry/import/{accountId}(startOrResume · 按状态渲染 上传/扫描/比对);
 * AJAX 上传/扫描/轮询/编辑;确认落库。</p>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class HoldingImportController {

    private final HoldingImportService importService;
    private final AccountMapper accountMapper;
    private final QwenVisionClient vision;

    private Account requireAccount(long familyId, long accountId) {
        Account a = accountMapper.findById(accountId).orElseThrow(() -> new IllegalArgumentException("账户不存在"));
        if (a.getFamilyId() != familyId) throw new IllegalArgumentException("无权访问");
        return a;
    }

    private HoldingImport requireImport(long familyId, long importId) {
        HoldingImport imp = importService.get(importId).orElseThrow(() -> new IllegalArgumentException("导入不存在"));
        if (imp.getFamilyId() != familyId) throw new IllegalArgumentException("无权访问");
        return imp;
    }

    // ---------- 页面 ----------

    /**
     * v1.4.2 · 消毒来源地址:只允许站内相对路径(防开放重定向)。用于导入完成后「回到进入的页面」。
     */
    private static String safeLocalPath(String from, String fallback) {
        if (from == null || from.isBlank()) return fallback;
        if (from.startsWith("/") && !from.startsWith("//") && from.matches("/[A-Za-z0-9/_?=&.\\-]*")) return from;
        return fallback;
    }

    @GetMapping("/entry/import/{accountId}")
    public String page(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long accountId,
                       @RequestParam(required = false) String from, Model model) {
        Account account = requireAccount(me.getFamilyId(), accountId);
        model.addAttribute("from", safeLocalPath(from, "/entry"));   // v1.4.2 · 回来源页
        if (!vision.available()) {
            model.addAttribute("account", account);
            model.addAttribute("visionUnavailable", true);
            return "holdingimport/import";
        }
        HoldingImport imp;
        try {
            imp = importService.startOrResume(accountId);
        } catch (IllegalStateException e) {
            // 没有开账期 → 友好提示,不 500
            model.addAttribute("account", account);
            model.addAttribute("noPeriod", true);
            return "holdingimport/import";
        }
        model.addAttribute("account", account);
        model.addAttribute("imp", imp);
        model.addAttribute("imageRels", importService.imageRels(imp.getId()));   // v1.4.2 · 查看/删除已上传图
        model.addAttribute("visionModel", vision.model());
        if (HoldingImport.REVIEW.equals(imp.getStatus())) {
            model.addAttribute("items", importService.items(imp.getId()));
            model.addAttribute("industryTags", com.family.finance.domain.lens.IndustryTag.values());
            model.addAttribute("assetClasses", com.family.finance.domain.lens.AssetClass.values());
        }
        return "holdingimport/import";
    }

    // ---------- AJAX ----------

    @PostMapping("/entry/import/{importId}/upload")
    @ResponseBody
    public Map<String, Object> upload(@AuthenticationPrincipal MemberPrincipal me,
                                      @PathVariable long importId,
                                      @RequestParam("files") MultipartFile[] files) {
        HoldingImport imp = requireImport(me.getFamilyId(), importId);
        int saved = 0;
        for (MultipartFile f : files) {
            if (f.isEmpty()) continue;
            try {
                importService.saveImage(imp, f.getBytes(), f.getContentType());
                saved++;
            } catch (Exception e) {
                log.warn("import {} 存图失败: {}", importId, e.toString());
            }
        }
        return Map.of("importId", importId, "imgCount", imp.getImgCount() == null ? 0 : imp.getImgCount(), "saved", saved);
    }

    @PostMapping("/entry/import/{importId}/scan")
    @ResponseBody
    public Map<String, Object> scan(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long importId) {
        HoldingImport imp = requireImport(me.getFamilyId(), importId);
        if (imp.getImgCount() == null || imp.getImgCount() == 0) return Map.of("error", "请先上传截图");
        importService.scanAsync(importId);   // 跨 bean → 真异步
        return Map.of("status", HoldingImport.SCANNING);
    }

    /** v1.4.2 · 删一张已上传截图(上传态/识别失败态可用)· 返回剩余张数 */
    @PostMapping("/entry/import/{importId}/image/delete")
    @ResponseBody
    public Map<String, Object> deleteImage(@AuthenticationPrincipal MemberPrincipal me,
                                           @PathVariable long importId,
                                           @RequestParam String rel) {
        importService.deleteImage(me.getFamilyId(), importId, rel);
        HoldingImport imp = requireImport(me.getFamilyId(), importId);
        return Map.of("ok", true, "imgCount", imp.getImgCount() == null ? 0 : imp.getImgCount());
    }

    @GetMapping("/entry/import/{importId}/status")
    @ResponseBody
    public Map<String, Object> status(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long importId) {
        HoldingImport imp = requireImport(me.getFamilyId(), importId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", imp.getStatus());
        r.put("scanError", imp.getScanError());
        if (HoldingImport.REVIEW.equals(imp.getStatus())) {
            r.put("itemCount", importService.items(importId).size());
        }
        return r;
    }

    /** 用户在比对表编辑一项(市值/标签/勾选/卖出定夺/改匹配) */
    @PostMapping("/entry/import/item/{itemId}")
    @ResponseBody
    public Map<String, Object> editItem(@AuthenticationPrincipal MemberPrincipal me,
                                        @PathVariable long itemId,
                                        @RequestParam(required = false) BigDecimal marketValue,
                                        @RequestParam(required = false) String parsedName,
                                        @RequestParam(required = false) String matchState,
                                        @RequestParam(required = false) Long matchedHid,
                                        @RequestParam(required = false) String industryTag,
                                        @RequestParam(required = false) String assetClassTag,
                                        @RequestParam(required = false) String platformTag,
                                        @RequestParam(required = false) String userDecision,
                                        @RequestParam(required = false) Boolean selected) {
        importService.editItem(me.getFamilyId(), itemId, HoldingImportItem.builder()
                .id(itemId).marketValue(marketValue).parsedName(parsedName).matchState(matchState)
                .matchedHid(matchedHid).industryTag(industryTag).assetClassTag(assetClassTag)
                .platformTag(platformTag).userDecision(userDecision).selected(selected).build());
        return Map.of("ok", true);
    }

    @PostMapping("/entry/import/{importId}/confirm")
    public String confirm(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long importId,
                          @RequestParam(required = false) String from) {
        requireImport(me.getFamilyId(), importId);
        importService.confirm(importId, me.getMemberId());
        return "redirect:" + safeLocalPath(from, "/entry");   // v1.4.2 · 回进入页(默认填报页)
    }

    @PostMapping("/entry/import/{importId}/abandon")
    public String abandon(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long importId,
                          @RequestParam(required = false) String from) {
        requireImport(me.getFamilyId(), importId);
        importService.abandon(importId);
        return "redirect:" + safeLocalPath(from, "/entry");   // v1.4.2 · 回进入页(默认填报页)
    }

    /** v1.4 · 从流水「看明细」进入:逐项变化 + 当时上传的原图(只读) */
    @GetMapping("/entry/import/{importId}/detail")
    public String detail(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long importId, Model model) {
        HoldingImport imp = requireImport(me.getFamilyId(), importId);
        model.addAttribute("imp", imp);
        model.addAttribute("account", requireAccount(me.getFamilyId(), imp.getAccountId()));
        java.util.List<HoldingImportItem> items = importService.items(importId);
        model.addAttribute("items", items);
        model.addAttribute("shots", items.stream().map(HoldingImportItem::getShotPath)
                .filter(java.util.Objects::nonNull).distinct().toList());
        return "holdingimport/detail";
    }
}
