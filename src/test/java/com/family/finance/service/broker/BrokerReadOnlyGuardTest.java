package com.family.finance.service.broker;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.15 只读铁律 · <b>静态护栏</b>(用户硬约束:"必须只能只读,不允许申请任何写/交易类似的 api")。
 *
 * <p>扫描 broker 适配器全部源码,断言<b>没有任何下单 / 改单 / 撤单 / 解锁交易的调用</b>。
 * 未来谁手滑接了写接口,这里立刻红 —— 把整类风险钉死,不靠人肉 review。</p>
 *
 * <p>只匹配<b>调用形态</b>(方法名 + 后续 {@code (}),因此文档里"永不 unlockTrade"之类的说明不会误伤。</p>
 */
class BrokerReadOnlyGuardTest {

    /** 禁止出现的写/交易调用(小写匹配 · 调用形态) */
    private static final List<String> FORBIDDEN_CALLS = List.of(
            "unlocktrade(",   // 富途:解锁交易(只读永不调用)
            "placeorder",     // 下单
            "modifyorder",    // 改单
            "cancelorder",    // 撤单
            "replaceorder",   // 换单
            "trade("          // 泛交易入口
    );

    @Test
    void broker_adapters_contain_no_write_or_trade_calls() throws IOException {
        Path dir = Path.of("src/main/java/com/family/finance/service/broker");
        assertThat(Files.isDirectory(dir)).as("broker 源码目录应存在").isTrue();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p);
                String scan = stripComments(src).toLowerCase(Locale.ROOT);
                for (String bad : FORBIDDEN_CALLS) {
                    if (scan.contains(bad)) {
                        violations.add(p.getFileName() + " → " + bad);
                    }
                }
            }
        }
        assertThat(violations)
                .as("券商适配器出现写/交易调用(违反只读铁律)")
                .isEmpty();
    }

    /** 去掉块注释与行注释,避免文档中的说明字样误伤。 */
    private static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlock.replaceAll("(?m)//.*$", " ");
    }
}
