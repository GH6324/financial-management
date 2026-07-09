package com.family.finance.web.broker;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.broker.BrokerVendor;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.BrokerLinkMapper;
import com.family.finance.service.NavService;
import com.family.finance.service.broker.BrokerLinkService;
import com.family.finance.service.broker.BrokerSyncService;
import com.family.finance.service.stock.StockHoldingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 券商关联管理 · v0.15。
 *
 * <p>路由清单:</p>
 * <ul>
 *   <li>GET  /accounts/{id}/broker         · 关联管理页(状态 / 券商选择 / 两步确认)</li>
 *   <li>POST /accounts/{id}/broker/link    · 关联(高危 · 需 confirmed=true 且 acknowledged=true)</li>
 *   <li>POST /accounts/{id}/broker/unlink  · 解绑(留归档快照可找回)</li>
 *   <li>POST /accounts/{id}/broker/sync    · 手动同步一次</li>
 * </ul>
 *
 * <p><b>只读铁律</b>:本控制器不提供任何下单 / 划转 / 解锁交易入口。</p>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class BrokerLinkController {

    private final BrokerLinkService linkService;
    private final BrokerSyncService syncService;
    private final BrokerLinkMapper linkMapper;
    private final AccountMapper accountMapper;
    private final NavService navService;

    @GetMapping("/accounts/{accountId}/broker")
    public String page(@AuthenticationPrincipal MemberPrincipal me,
                       @PathVariable long accountId, Model model) {
        Account account = requireHoldingAccount(me.getFamilyId(), accountId);
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("account", account);
        model.addAttribute("link", linkMapper.findByAccount(accountId).orElse(null));
        model.addAttribute("vendors", BrokerVendor.values());
        return "broker/link";
    }

    @PostMapping("/accounts/{accountId}/broker/link")
    public String link(@AuthenticationPrincipal MemberPrincipal me,
                       @PathVariable long accountId,
                       @RequestParam String vendor,
                       @RequestParam(required = false) String brokerAccountId,
                       @RequestParam(value = "confirmed", defaultValue = "false") boolean confirmed,
                       @RequestParam(value = "acknowledged", defaultValue = "false") boolean acknowledged,
                       RedirectAttributes ra) {
        // 两步确认硬门:两个勾都要打上,缺一即拒(前端二次弹窗 + 后端复核)
        if (!confirmed || !acknowledged) {
            ra.addFlashAttribute("brokerError", "关联为高危不可自动回退操作,需勾选并二次确认后才能执行。");
            return "redirect:/accounts/" + accountId + "/broker";
        }
        try {
            BrokerVendor v = BrokerVendor.valueOf(vendor.toUpperCase(java.util.Locale.ROOT));
            String msg = linkService.link(me.getFamilyId(), accountId, v,
                    blankToNull(brokerAccountId), me.getMemberId());
            ra.addFlashAttribute("brokerOk", msg);
        } catch (Exception e) {
            log.warn("broker link failed · account={}: {}", accountId, e.toString());
            ra.addFlashAttribute("brokerError", "关联失败:" + e.getMessage());
        }
        return "redirect:/accounts/" + accountId + "/broker";
    }

    @PostMapping("/accounts/{accountId}/broker/unlink")
    public String unlink(@AuthenticationPrincipal MemberPrincipal me,
                         @PathVariable long accountId, RedirectAttributes ra) {
        try {
            linkService.unlink(me.getFamilyId(), accountId, me.getMemberId());
            ra.addFlashAttribute("brokerOk", "已解绑 · 同步来的持仓已转为普通持仓可继续手动维护 · 关联前的旧持仓在归档区可恢复。");
        } catch (Exception e) {
            log.warn("broker unlink failed · account={}: {}", accountId, e.toString());
            ra.addFlashAttribute("brokerError", "解绑失败:" + e.getMessage());
        }
        return "redirect:/accounts/" + accountId + "/broker";
    }

    @PostMapping("/accounts/{accountId}/broker/sync")
    public String sync(@AuthenticationPrincipal MemberPrincipal me,
                       @PathVariable long accountId, RedirectAttributes ra) {
        try {
            String msg = syncService.sync(me.getFamilyId(), accountId, me.getMemberId());
            ra.addFlashAttribute("brokerOk", msg);
        } catch (Exception e) {
            log.warn("broker manual sync failed · account={}: {}", accountId, e.toString());
            ra.addFlashAttribute("brokerError", "同步失败:" + e.getMessage());
        }
        return "redirect:/accounts/" + accountId + "/broker";
    }

    // ---------- helpers ----------

    private Account requireHoldingAccount(long familyId, long accountId) {
        Account acc = accountMapper.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账户不存在"));
        if (!acc.getFamilyId().equals(familyId)) throw new IllegalArgumentException("无权访问账户");
        if (!StockHoldingService.supportsHoldings(acc.getType())) {
            throw new IllegalArgumentException("仅持仓类账户可关联券商");
        }
        return acc;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
