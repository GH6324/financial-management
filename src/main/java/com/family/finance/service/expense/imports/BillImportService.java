package com.family.finance.service.expense.imports;

import com.family.finance.domain.expense.ExpenseCategory;
import com.family.finance.domain.expense.ExpenseSource;
import com.family.finance.repository.ExpenseMerchantRuleMapper;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.expense.ExpenseCategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21 · 账单导入的编排:收文件 → 解析 → 映射 → 出草稿。
 *
 * <h3>隐私红线(本项目迄今最敏感的输入)</h3>
 *
 * <p>账单是<b>整月的消费流水</b>。所以:</p>
 * <ul>
 *   <li><b>不落盘</b> —— 字节只在方法栈与草稿里,草稿存 session,确认或离开即弃</li>
 *   <li><b>不进日志</b> —— 失败时只记「哪个渠道 / 哪一步 / 什么原因」,绝不记内容或密码</li>
 *   <li><b>金额永不进 LLM</b> —— 支付宝 CSV 全程不过 LLM;微信只在用户显式勾选时把
 *       <b>商户名列表</b>送去要映射建议(不含金额、不含时间)</li>
 * </ul>
 *
 * <h3>为什么草稿必须经过确认页</h3>
 *
 * <p>渠道的自动分类<b>不是每笔都准</b>(少数派原话),微信更是完全靠关键字猜。
 * 直接落账等于把猜的结果写进用户的账 —— 所以确认页是必经之路,映射当场可改并记住。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillImportService {

    /** 单个文件上限。整年支付宝账单 csv 约几 MB。 */
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private final ExpenseCategoryService categoryService;
    private final ExpenseMerchantRuleMapper ruleMapper;
    private final FamilyConfigService configService;
    private final com.family.finance.repository.ExpenseFlowMapper flowMapper;
    private final MerchantAiClassifier ai;

    public static class ImportException extends RuntimeException {
        public ImportException(String m) { super(m); }
        public ImportException(String m, Throwable c) { super(m, c); }
    }

    /**
     * 解析一个上传的文件(csv 或加密 zip)。
     *
     * @param channel  用户在页面上选的渠道 —— 我们<b>不猜</b>渠道:两家的格式很像,
     *                 猜错了会把微信的交易类型当成消费分类,而这个错很难被发现
     * @param password zip 解压密码;用完即弃
     */
    public BillCategoryResolver.Draft parseFile(long familyId, ExpenseSource channel,
                                                byte[] bytes, String filename, String password) {
        requireChannel(channel);
        if (bytes == null || bytes.length == 0) throw new ImportException("文件是空的。");
        if (bytes.length > MAX_FILE_BYTES) {
            throw new ImportException("文件太大了(超过 20MB)。账单 csv 通常只有几 MB —— "
                    + "确认一下选的是账单本身。");
        }

        byte[] csv;
        if (ZipOpener.looksLikeZip(bytes)) {
            List<ZipOpener.Entry> entries;
            try {
                entries = ZipOpener.open(bytes, password, ".csv", ".txt");
            } catch (ZipOpener.ZipException e) {
                // 只记渠道与原因,不记文件名之外的任何内容,更不记密码
                log.warn("账单 zip 打不开 · channel={} · {}", channel, e.getMessage());
                throw new ImportException(e.getMessage(), e);
            }
            // 一个 zip 里正常只有一个 csv;有多个时取最大的那个(说明文件通常很小)
            ZipOpener.Entry pick = entries.get(0);
            for (ZipOpener.Entry e : entries) if (e.bytes().length > pick.bytes().length) pick = e;
            csv = pick.bytes();
        } else if (looksLikePdf(bytes)) {
            throw new ImportException("这是一个 PDF —— 导出账单时选的应该是「用于个人对账」,"
                    + "而不是「用作证明材料」。后者是盖章的 PDF,只能给别人看,导不进来。");
        } else {
            csv = bytes;
        }

        CsvBillParser.Parsed parsed;
        try {
            parsed = CsvBillParser.parse(csv);
        } catch (CsvBillParser.ParseException e) {
            log.warn("账单解析失败 · channel={} · {}", channel, e.getMessage());
            throw new ImportException(e.getMessage(), e);
        }
        return toDraft(familyId, channel, parsed);
    }

    /**
     * 解析结果 → 逐笔草稿(归类 + 分桶)。
     *
     * <p>三层兜底在 {@link BillCategoryResolver#classify} 里做完前两层(渠道分类 / 关键字规则),
     * 这里补上第三层 <b>AI</b> —— 只对<b>兜底那一堆</b>送,而且<b>只送商户名</b>(FR-563)。
     * 没配 key 就整层跳过,那些笔留在「其他」等用户手改。</p>
     */
    public BillCategoryResolver.Draft toDraft(long familyId, ExpenseSource channel,
                                              CsvBillParser.Parsed parsed) {
        /* 匹配范围是【整棵树】而不是某一层 ——
         * 渠道给的分类名是大类粒度(「餐饮美食」),落在大类上完全合法;
         * 只在细类里找的话几乎全会落进「其他」(第 1 稿真踩了)。 */
        List<ExpenseCategory> allNodes = categoryService.all(familyId).stream()
                .filter(c -> !c.isArchived())
                .toList();
        if (allNodes.isEmpty()) {
            throw new ImportException("你还没有支出类目 —— 先去建几个(一键起步包最快),再回来导入。");
        }
        ExpenseCategory other = categoryService.ensureOther(familyId);

        Map<String, Long> rules = new LinkedHashMap<>();
        for (var r : ruleMapper.findByFamily(familyId)) rules.put(r.keyword(), r.categoryId());

        /* 去重:整个家庭范围内已经落过的交易号(不只是当期)——
         * 用户可能把 9 月的账单误导进 8 月那一期,只查当期会让那笔再落一次。 */
        List<String> txNos = parsed.rows().stream()
                .map(BillRow::txNo).filter(java.util.Objects::nonNull).distinct().toList();
        java.util.Set<String> seen = txNos.isEmpty() ? java.util.Set.of()
                : new java.util.HashSet<>(flowMapper.existingTxNos(familyId, txNos));

        BillCategoryResolver.Draft d =
                BillCategoryResolver.classify(channel, parsed, allNodes, rules, other.getId(), seen);
        return applyAi(familyId, d, allNodes, other.getId());
    }

    /**
     * 第三层:把「兜底」那一堆的商户名送给模型猜。
     *
     * <p>只送 {@link BillCategoryResolver.How#FALLBACK} 的行 —— 前两层命中的不该被 AI 覆盖:
     * 渠道自己的分类和用户自己的规则都比模型的猜测可信。</p>
     *
     * <p>猜中的标成 {@code AI},猜不中的<b>留在 FALLBACK</b>。两者在确认页分开标 ——
     * 「AI 猜的」值得扫一眼,「兜底」是必须处理的。混成一类就等于让用户白看一遍。</p>
     */
    private BillCategoryResolver.Draft applyAi(long familyId, BillCategoryResolver.Draft d,
                                               List<ExpenseCategory> allNodes, long otherId) {
        List<BillCategoryResolver.Line> fallback = d.lines().stream()
                .filter(l -> l.bucket() == BillCategoryResolver.Bucket.SPEND
                          && l.how() == BillCategoryResolver.How.FALLBACK)
                .toList();
        if (fallback.isEmpty() || !ai.available()) return d;

        List<String> names = fallback.stream().map(BillCategoryResolver.Line::merchant).toList();
        List<String> catNames = allNodes.stream().map(ExpenseCategory::getName).distinct().toList();
        Map<String, String> guess = ai.classify(names, catNames);
        if (guess.isEmpty()) return d;

        Map<String, Long> idOfName = new LinkedHashMap<>();
        for (ExpenseCategory c : allNodes) if (!c.isTopLevel()) idOfName.putIfAbsent(c.getName(), c.getId());
        for (ExpenseCategory c : allNodes) if (c.isTopLevel()) idOfName.putIfAbsent(c.getName(), c.getId());

        List<BillCategoryResolver.Line> out = new java.util.ArrayList<>();
        for (BillCategoryResolver.Line l : d.lines()) {
            String g = (l.bucket() == BillCategoryResolver.Bucket.SPEND
                     && l.how() == BillCategoryResolver.How.FALLBACK)
                    ? guess.get(l.merchant()) : null;
            Long tid = g == null ? null : idOfName.get(g);
            if (tid == null) { out.add(l); continue; }
            out.add(new BillCategoryResolver.Line(l.idx(), l.occurredAt(), l.merchant(), l.amount(),
                    tid, g, BillCategoryResolver.How.AI, l.bucket(), null, null, l.txNo()));
        }
        return new BillCategoryResolver.Draft(d.channel(), out, d.total(), d.parsed(), d.noTxNo());
    }

    /** 用户在确认页把某个商户改到别的类目 → 记住,下次自动命中 */
    public void rememberRule(long familyId, String keyword, long categoryId) {
        String k = keyword == null ? "" : keyword.trim();
        if (k.isEmpty() || k.length() > 40) return;
        ruleMapper.upsert(familyId, k, categoryId);
    }

    public List<ExpenseMerchantRuleMapper.Rule> rules(long familyId) {
        return ruleMapper.findByFamily(familyId);
    }

    public void deleteRule(long familyId, long id) { ruleMapper.delete(familyId, id); }

    // ──────────────────────── 小工具 ────────────────────────

    private static void requireChannel(ExpenseSource channel) {
        if (channel == null || !channel.isImported()) {
            throw new ImportException("先选一下这份账单是哪个渠道的 —— 支付宝和微信的格式不一样,"
                    + "我们不猜(猜错会把微信的交易类型当成消费分类,而这个错很难被发现)。");
        }
    }

    private static boolean looksLikePdf(byte[] b) {
        return b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
    }
}
