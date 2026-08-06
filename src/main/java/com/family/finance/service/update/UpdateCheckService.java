package com.family.finance.service.update;

import com.family.finance.repository.FamilyRuntimeConfigMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * v1.9 · 版本落后检查(PRD v1.9 / tech-design v1.9)。
 *
 * <h3>范围:只查不改</h3>
 * 本服务**不下载、不换 jar、不重启、不回滚**,一个字节的产物都不落盘。
 * 一键更新的可行性与风险分析见 {@code tech-design/v1.9.md} §7。
 *
 * <h3>三条不能破的约束</h3>
 * <ol>
 *   <li><b>页面渲染永不等网络</b> —— 出网只发生在定时器和「立即检查」按钮里。
 *       页面读的是 {@link #cached(long)}(内存),见 {@code GlobalModelAdvice}。</li>
 *   <li><b>失败不许覆盖上次成功的结果</b> —— 「上次成功结果」与「最近一次尝试」分成两个 KV 键。
 *       合成一个的话:失败时要么盖掉好结果(页面突然什么都不显示),要么不写(没法显示失败原因)。</li>
 *   <li><b>判据是「上一次成功的结果」,不是「现在能不能连上 GitHub」</b> ——
 *       项目纪律:连通性探针不能当可用性判据。</li>
 * </ol>
 *
 * <h3>不带遥测</h3>
 * UA 固定为 {@code UA}(**不含版本号**)。GitHub 要求请求带 UA,顺手写成
 * {@code financial-management/1.9.0} 是最自然的写法 —— 那就等于把版本号发出去了,
 * 与 PRD FR-303「不带版本号、不带实例标识」冲突。守护 {@code v19-UPD-NO-TELEMETRY} 盯着这一点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateCheckService {

    /** 只信本仓库自己的 release。写死不做成可配置项 —— 可配置 = 可被指向任意仓库。 */
    public static final String REPO = "LuoDi-Nate/financial-management";

    /** 不含版本号:见类注释「不带遥测」。 */
    static final String UA = "financial-management";

    static final String KEY_ENABLED = "update.check.enabled";
    static final String KEY_RESULT = "update.check.result";
    static final String KEY_ATTEMPT = "update.check.lastAttempt";
    /**
     * v1.9.2 · 最新版的一段摘要(弹窗里给用户看「这版干了什么」)。
     * **单独一个键**:release body 动辄几千字,塞进 result 会把那 512 字节的预算挤爆,
     * 而 result 里的落后数/迁移判定是不能被挤掉的关键字段。
     */
    static final String KEY_SUMMARY = "update.check.summary";

    /** family_runtime_config.value_text 是 VARCHAR(512),不为这个功能改表 → 写入前必须收敛长度。 */
    static final int VALUE_MAX = 512;

    /** PRD FR-302:最多列最近 5 个版本,更早的折叠。 */
    static final int MAX_ITEMS = 5;

    /** 单条主题超这个长度就截断,免得一条长标题把整个 JSON 撑爆。 */
    static final int MAX_TITLE = 40;

    /** GitHub compare 的 files 上限。达到即视为被截断 → 迁移判定不可信。 */
    static final int COMPARE_FILES_CAP = 300;

    private final FamilyRuntimeConfigMapper configMapper;
    private final ObjectMapper json;

    /**
     * 进程内缓存。{@code GlobalModelAdvice} 每个请求都会读它,所以那里只能做一次字段读、
     * 绝不能查库更不能出网(tech-design §2.3)。
     *
     * <p><b>前提:单实例部署</b>(systemd 一个进程 / compose 一个容器)。多实例会各自缓存 ——
     * 哪天要多实例,这里会漂,别以为它是集群安全的。</p>
     */
    private volatile UpdateInfo memo = UpdateInfo.unknown();

    /** 正在跑的 jar 版本。reloadMemo 用它盖掉 KV 里可能已过期的 current。
     *  用字段注入而不是构造器参数:单测直接 new 这个类,不想为它多传一个参。 */
    @Value("${app.version:dev}")
    private String appVersion;

    // ── 对外 ────────────────────────────────────────────────────────────

    /** 页面用:只读内存,零 IO。 */
    public UpdateInfo cached(long familyId) {
        return memo;
    }

    public boolean enabled(long familyId) {
        // 缺省视为开(PRD FR-303 默认开)
        return configMapper.findValue(familyId, KEY_ENABLED)
                .map(v -> !"false".equalsIgnoreCase(v.trim()))
                .orElse(true);
    }

    public void setEnabled(long familyId, boolean on) {
        configMapper.upsert(familyId, KEY_ENABLED, on ? "true" : "false");
        if (!on) {
            // 关掉之后圆点必须立刻消失,不能等下一次定时器
            memo = UpdateInfo.unknown();
        } else {
            reloadMemo(familyId);
        }
    }

    /**
     * 启动时 / 每次写库后:把 KV 里的结果读进内存,**并用正在跑的版本覆盖里面的 current**。
     *
     * <p>为什么要覆盖:KV 行是上次检查时写的,里面的 current 是**那时候**在跑的版本。
     * 用户按提示升级完 jar、重启,这行还没刷新 —— 拿旧 current 去比,已经升到最新版的实例
     * 依然显示 NEW,一直挂到隔天定时器跑过。</p>
     *
     * <p>覆盖动作放在**这里**而不是调用方:读缓存的入口有三个(启动预热 / 开关切换 / 检查后回写),
     * 靠每个调用方各自记得传版本号,漏一个就能把过期的 current 复活 ——
     * 最初就是只在预热那条路上传了参,一开关就露。守护 {@code v192-UPD-STALE-CURRENT}。</p>
     */
    public void reloadMemo(long familyId) {
        if (!enabled(familyId)) {
            memo = UpdateInfo.unknown();
            return;
        }
        memo = readResult(familyId)
                .map(i -> i.withSummary(configMapper.findValue(familyId, KEY_SUMMARY).orElse(null)))
                .map(i -> i.withCurrent(appVersion))
                .orElse(UpdateInfo.unknown());
    }

    /** 最近一次尝试(含失败)· 管理页显示「检查于 X · 失败原因 Y」用。 */
    public Attempt lastAttempt(long familyId) {
        return configMapper.findValue(familyId, KEY_ATTEMPT)
                .flatMap(this::parseAttempt)
                .orElse(new Attempt(null, false, null));
    }

    // ── 纯函数(可单测,不出网)─────────────────────────────────────────

    /**
     * 语义版本比较。只认 {@code vX.Y.Z} 三段数字;解析不了返回 null(**不猜**)。
     *
     * @return 负=current 更旧 · 0=相同 · 正=current 更新;任一解析失败 → null
     */
    static Integer compare(String current, String latest) {
        int[] a = parse(current), b = parse(latest);
        if (a == null || b == null) return null;
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }

    /**
     * 版本号 → **git tag 引用**。
     *
     * <p>{@code app.version} 是 {@code 1.9.0}(application.yml 里不带 v),而我们的 tag 是
     * {@code v1.9.0}。compare API 要的是**真实存在的 ref** —— 直接把 app.version 拼进 URL
     * 会得到 {@code /compare/1.9.0...v1.9.1} → <b>404</b>,于是迁移判定永远落到「无法确定」,
     * 版本卡上最有价值的那一格彻底失效。</p>
     *
     * <p>这个 bug 在 beta 上测不出来:那个分支只在「有新版」时才走,而 beta 的版本总是比
     * 已发布的最新版更新(在研版本号先行),分支根本不执行。是准备发 v1.9.1 做真机验证时
     * 核 URL 才发现的。</p>
     */
    static String tagOf(String version) {
        if (version == null) return null;
        String s = version.trim();
        if (s.isEmpty()) return null;
        return (s.startsWith("v") || s.startsWith("V")) ? s : "v" + s;
    }

    static int[] parse(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        String[] p = s.split("\\.");
        if (p.length != 3) return null;
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            try {
                out[i] = Integer.parseInt(p[i].trim());
            } catch (NumberFormatException e) {
                return null;   // 带 -rc1 之类的一律不认
            }
            if (out[i] < 0) return null;
        }
        return out;
    }

    /**
     * 迁移判定。**fail-closed**:文件清单被截断(达到 GitHub 300 上限)或拿不到 →
     * {@code known=false},页面显示「无法确定」。
     *
     * <p>不这么做的话会得到「无 schema 变更」这个<b>错误且危险</b>的结论 ——
     * 正是项目纪律里 {@code || echo 0}「查不出来就当没有」那类老毛病。</p>
     */
    static Migrations detectMigrations(List<String> changedFiles, boolean truncated) {
        if (changedFiles == null || truncated) {
            return new Migrations(0, List.of(), false);
        }
        List<String> ids = new ArrayList<>();
        for (String f : changedFiles) {
            if (f != null && f.startsWith("db/migration/")) {
                String base = f.substring("db/migration/".length());
                int us = base.indexOf("__");
                ids.add(us > 0 ? base.substring(0, us) : base);
            }
        }
        return new Migrations(ids.size(), ids, true);
    }

    /**
     * 把结果压进 VARCHAR(512)。超长时**逐步丢 items 尾部**,绝不写半截 JSON 进库。
     * 全丢完还超(理论上不会)→ 返回 empty,调用方按失败处理。
     */
    Optional<String> serializeWithin(UpdateInfo info) {
        List<Item> items = new ArrayList<>(info.items());
        while (true) {
            String s = toJson(info.withItems(List.copyOf(items)));
            if (s == null) return Optional.empty();
            if (s.length() <= VALUE_MAX) return Optional.of(s);
            if (items.isEmpty()) return Optional.empty();
            items.remove(items.size() - 1);
        }
    }

    private String toJson(UpdateInfo i) {
        try {
            ObjectNode n = json.createObjectNode();
            n.put("at", i.checkedAt() == null ? null : i.checkedAt().toString());
            n.put("current", i.current());
            n.put("latest", i.latest());
            n.put("behind", i.behind());
            n.put("pub", i.publishedAt());          // 只 10 字符,便宜;summary 走单独的键
            ObjectNode m = n.putObject("mig");
            m.put("n", i.migrations().count());
            m.put("known", i.migrations().known());
            ArrayNode ids = m.putArray("ids");
            i.migrations().ids().forEach(ids::add);
            ArrayNode arr = n.putArray("items");
            for (Item it : i.items()) {
                ObjectNode o = arr.addObject();
                o.put("v", it.version());
                o.put("t", it.title());
            }
            return json.writeValueAsString(n);
        } catch (Exception e) {
            log.warn("update check · 结果序列化失败", e);
            return null;
        }
    }

    /** 摘要最大长度(存 VARCHAR(512),留足余量) */
    static final int MAX_SUMMARY = 260;

    /**
     * 从 release body(markdown)里抠一段摘要。
     *
     * <p>取**第一段正文** —— 跳过标题行(`#`)、图片/表格/HTML、引用和列表符号,
     * 因为我们的发布说明开头往往是 `<table>` 宫格图。取不到就返回 null,
     * 弹窗那一段直接不显示(**不显示一段乱码般的 markdown 残渣**)。</p>
     */
    static String summarize(String body) {
        if (body == null || body.isBlank()) return null;
        StringBuilder out = new StringBuilder();
        for (String raw : body.replace("\r", "").split("\n")) {
            String l = raw.trim();
            if (l.isEmpty()) { if (out.length() > 0) break; else continue; }   // 遇到空行=段落结束
            if (l.startsWith("#") || l.startsWith("|") || l.startsWith(">")
                    || l.startsWith("<") || l.startsWith("!") || l.startsWith("---")
                    || l.startsWith("```") || l.startsWith("- ") || l.startsWith("* ")) {
                if (out.length() > 0) break;    // 正文已经开始了,遇到这些就收尾
                continue;                        // 还没开始,跳过
            }
            if (out.length() > 0) out.append(' ');
            out.append(l);
            if (out.length() >= MAX_SUMMARY) break;
        }
        String s = out.toString()
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")          // 去粗体标记
                .replaceAll("`([^`]+)`", "$1")                          // 去行内代码标记
                .replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1")   // 链接只留文字
                .trim();
        if (s.isEmpty()) return null;
        return s.length() <= MAX_SUMMARY ? s : s.substring(0, MAX_SUMMARY - 1) + "…";
    }

    static String trimTitle(String t) {
        if (t == null) return "";
        String s = t.trim();
        return s.length() <= MAX_TITLE ? s : s.substring(0, MAX_TITLE - 1) + "…";
    }

    // ── 落库 / 读库 ──────────────────────────────────────────────────────

    Optional<UpdateInfo> readResult(long familyId) {
        return configMapper.findValue(familyId, KEY_RESULT).flatMap(this::parseResult);
    }

    private Optional<UpdateInfo> parseResult(String raw) {
        try {
            JsonNode n = json.readTree(raw);
            List<Item> items = new ArrayList<>();
            for (JsonNode it : n.path("items")) {
                items.add(new Item(it.path("v").asText(""), it.path("t").asText("")));
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode id : n.path("mig").path("ids")) ids.add(id.asText(""));
            return Optional.of(new UpdateInfo(
                    n.path("at").isNull() ? null : Instant.parse(n.path("at").asText()),
                    n.path("current").asText(null),
                    n.path("latest").asText(null),
                    n.path("behind").asInt(0),
                    new Migrations(n.path("mig").path("n").asInt(0), ids,
                            n.path("mig").path("known").asBoolean(false)),
                    items,
                    n.path("pub").isNull() ? null : n.path("pub").asText(null),
                    null));   // summary 单独读
        } catch (Exception e) {
            log.warn("update check · 缓存结果解析失败,当作没有", e);
            return Optional.empty();
        }
    }

    private Optional<Attempt> parseAttempt(String raw) {
        try {
            JsonNode n = json.readTree(raw);
            return Optional.of(new Attempt(
                    n.path("at").isNull() ? null : Instant.parse(n.path("at").asText()),
                    n.path("ok").asBoolean(false),
                    n.path("error").isNull() ? null : n.path("error").asText(null)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    void writeAttempt(long familyId, boolean ok, String error) {
        try {
            ObjectNode n = json.createObjectNode();
            n.put("at", Instant.now().toString());
            n.put("ok", ok);
            n.put("error", error == null ? null : trimTitle(error));
            configMapper.upsert(familyId, KEY_ATTEMPT, n.toString());
        } catch (Exception e) {
            log.warn("update check · 写 lastAttempt 失败", e);
        }
    }

    // ── 出网 ────────────────────────────────────────────────────────────

    /** connect 3s / read 5s —— 这是锦上添花的功能,不值得等。 */
    private HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)                      // 不含版本号
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(5))
                .GET().build();
        HttpResponse<String> r = client().send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + r.statusCode());
        return r.body();
    }

    /**
     * 真正出网检查。**只有定时器和「立即检查」按钮调它。**
     *
     * <p>失败时:只写 {@code lastAttempt},<b>不动 {@code result}、不动内存</b> ——
     * 永不因为一次失败让页面从「有新版」变成「什么都没有」。</p>
     */
    public UpdateInfo checkNow(long familyId, String currentVersion) {
        if (!enabled(familyId)) {
            return memo;
        }
        try {
            String latestBody = httpGet("https://api.github.com/repos/" + REPO + "/releases/latest");
            JsonNode rel = json.readTree(latestBody);
            if (rel.path("draft").asBoolean(false) || rel.path("prerelease").asBoolean(false)) {
                throw new IllegalStateException("latest 是 draft/prerelease");
            }
            String latest = rel.path("tag_name").asText(null);
            String published = rel.path("published_at").asText("");
            published = published.length() >= 10 ? published.substring(0, 10) : null;   // 只留 yyyy-MM-dd
            String summary = summarize(rel.path("body").asText(""));
            Integer cmp = compare(currentVersion, latest);
            if (cmp == null) {
                // 解析不了就别猜(自己 fork 改过版本号的情况)
                throw new IllegalStateException("版本号无法解析: " + currentVersion + " / " + latest);
            }

            int behind = 0;
            List<Item> items = List.of();
            Migrations mig = new Migrations(0, List.of(), true);

            if (cmp < 0) {
                // 落后几个「版本」= 比当前新的 release 个数。
                // 注意别用 compare API 的 ahead_by —— 那是 **commit 数**,不是版本数。
                List<Item> newer = fetchNewerReleases(currentVersion);
                behind = newer.size();
                items = newer.size() <= MAX_ITEMS ? newer : newer.subList(0, MAX_ITEMS);
                // 必须传 **tag 引用**,不能传 app.version 原样(见 tagOf 的注释)
                mig = fetchMigrations(tagOf(currentVersion), tagOf(latest));
            }

            UpdateInfo info = new UpdateInfo(Instant.now(), currentVersion, latest, behind, mig, items,
                    published, summary);
            serializeWithin(info).ifPresent(s -> configMapper.upsert(familyId, KEY_RESULT, s));
            configMapper.upsert(familyId, KEY_SUMMARY, summary == null ? "" : summary);
            writeAttempt(familyId, true, null);
            memo = info;
            return info;
        } catch (Exception e) {
            log.info("update check · 检查失败(保留上次结果): {}", e.toString());
            writeAttempt(familyId, false, e.getClass().getSimpleName() + ": " + e.getMessage());
            return memo;
        }
    }

    /** 迁移判定 · 失败或截断都落到「无法确定」(fail-closed)。 */
    private Migrations fetchMigrations(String current, String latest) {
        try {
            String body = httpGet("https://api.github.com/repos/" + REPO
                    + "/compare/" + current + "..." + latest);
            JsonNode n = json.readTree(body);
            List<String> files = new ArrayList<>();
            for (JsonNode f : n.path("files")) files.add(f.path("filename").asText(""));
            boolean truncated = files.size() >= COMPARE_FILES_CAP;
            return detectMigrations(files, truncated);
        } catch (Exception e) {
            log.info("update check · compare 失败,迁移判定标为「无法确定」: {}", e.toString());
            return new Migrations(0, List.of(), false);
        }
    }

    /**
     * 比当前版本新的全部正式版(新→旧)。**落后数就是它的 size**。
     * 失败返回空表 —— 那样 behind=0、items 为空,页面退化成「已是最新」而不是报错。
     */
    private List<Item> fetchNewerReleases(String current) {
        try {
            String body = httpGet("https://api.github.com/repos/" + REPO + "/releases?per_page=100");
            JsonNode arr = json.readTree(body);
            List<Item> out = new ArrayList<>();
            for (JsonNode r : arr) {
                if (r.path("draft").asBoolean(false) || r.path("prerelease").asBoolean(false)) continue;
                String tag = r.path("tag_name").asText("");
                Integer c = compare(current, tag);
                if (c == null || c >= 0) continue;              // 只要比当前新的
                out.add(new Item(tag, trimTitle(r.path("name").asText(tag))));
            }
            return out;
        } catch (Exception e) {
            log.info("update check · 版本列表拉取失败: {}", e.toString());
            return List.of();
        }
    }

    // ── VO ──────────────────────────────────────────────────────────────

    public record Item(String version, String title) {}

    public record Migrations(int count, List<String> ids, boolean known) {
        public boolean hasAny() { return known && count > 0; }
    }

    public record Attempt(Instant at, boolean ok, String error) {
        public boolean never() { return at == null; }
    }

    public record UpdateInfo(Instant checkedAt, String current, String latest,
                             int behind, Migrations migrations, List<Item> items,
                             String publishedAt, String summary) {

        public static UpdateInfo unknown() {
            return new UpdateInfo(null, null, null, 0, new Migrations(0, List.of(), false), List.of(), null, null);
        }

        /** GitHub 上这一版的发布说明页。模板直接用,别在模板里拼字符串。 */
        public String releaseUrl() {
            return latest == null ? "https://github.com/" + REPO + "/releases"
                    : "https://github.com/" + REPO + "/releases/tag/" + latest;
        }

        /** 有没有新版 —— 圆点、卡片都看它。**从来没查成功过时必须是 false。** */
        public boolean hasUpdate() {
            if (checkedAt == null || latest == null || current == null) return false;
            Integer c = compare(current, latest);
            return c != null && c < 0;
        }

        /** 展示用的当前版本:app.version 不带 v(1.9.0),latest 带(v1.9.1),
         *  直接并排就成了「1.9.0 → v1.9.1」。统一补上前缀。 */
        public String currentTag() {
            return tagOf(current);
        }

        UpdateInfo withItems(List<Item> newItems) {
            return new UpdateInfo(checkedAt, current, latest, behind, migrations, newItems, publishedAt, summary);
        }

        /**
         * 把 current 换成**正在跑的** jar 版本。
         *
         * <p>KV 里存的 current 是「上次检查时在跑的版本」。用户升级完 jar 之后这行还没刷新,
         * 如果继续拿它比,就会在**已经升到最新版**的实例上继续显示 NEW,一直挂到隔天定时器跑过。
         * latest 是关于 GitHub 的事实(可以旧),current 必须是关于本进程的事实(不能旧)。</p>
         */
        UpdateInfo withCurrent(String running) {
            return new UpdateInfo(checkedAt, running, latest, behind, migrations, items, publishedAt, summary);
        }

        UpdateInfo withSummary(String s) {
            return new UpdateInfo(checkedAt, current, latest, behind, migrations, items, publishedAt, s);
        }
    }
}
