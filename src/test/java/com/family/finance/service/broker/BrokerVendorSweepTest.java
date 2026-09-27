package com.family.finance.service.broker;

import com.family.finance.domain.broker.BrokerVendor;
import com.family.finance.domain.ledger.LedgerSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 加券商时不许漏 · v1.26。
 *
 * <p>加 IBKR 时查到三处会把第三家显示成别家或「来源不明」(holdings.html 的二选一、LedgerSource.ofBroker 的 default、
 * 数据库 CHECK 约束)。这里按<b>集合</b>守,不绑具体几家:以后再加第四家,漏了哪一处哪一条红。</p>
 */
class BrokerVendorSweepTest {

    @Test
    void 每一家券商都有自己的流水来源_不会被记成来源未记录() {
        for (BrokerVendor v : BrokerVendor.values()) {
            LedgerSource src = LedgerSource.ofBroker(v.name());
            assertThat(src).as(v + " 的流水来源").isNotEqualTo(LedgerSource.UNKNOWN);
            assertThat(src.getGroup()).isEqualTo("broker");
            assertThat(src.isAutomatic()).isTrue();
        }
    }

    @Test
    void 模板里不许用二选一去猜券商名() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/templates"))) {
            var bad = files.filter(p -> p.toString().endsWith(".html")).filter(p -> {
                try {
                    String s = Files.readString(p);
                    // 盯的是「两个字面量里二选一」这种猜名字的写法(原来 holdings.html:57 那样);
                    // 按券商做不同处理(比如 IBKR 账号打码)不算
                    return s.matches("(?s).*vendor\\.name\\(\\)\\s*==\\s*'[A-Z]+'\\s*\\?\\s*'[^']*'\\s*:\\s*'.*");
                } catch (IOException e) { return false; }
            }).map(Path::toString).toList();
            assertThat(bad).as("用 vendor.label,别写 vendor.name() == 'X' ? … : …").isEmpty();
        }
    }

    @Test
    void 数据库约束放得下每一家() throws IOException {
        String all;
        try (Stream<Path> files = Files.list(Path.of("db/migration"))) {
            all = files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted()
                    .map(p -> { try { return Files.readString(p); } catch (IOException e) { throw new RuntimeException(e); } })
                    .reduce("", String::concat);
        }
        int last = all.lastIndexOf("ck_broker_link_vendor CHECK");
        assertThat(last).as("broker_link.vendor 的 CHECK 约束").isGreaterThan(0);
        String latest = all.substring(last, all.indexOf(')', all.indexOf('(', last) + 1) + 1);
        for (BrokerVendor v : BrokerVendor.values()) {
            assertThat(latest).as("最新的约束要放得下 " + v).contains("'" + v.name() + "'");
        }
    }

    @Test
    void ibkr市场归一_按交易所而不是按币种() {
        assertThat(BrokerTicker.fromIbkr("NASDAQ", "USD", "AAPL")).isEqualTo(new BrokerTicker.Norm(com.family.finance.domain.stock.Market.US, "AAPL"));
        assertThat(BrokerTicker.fromIbkr("SEHK", "HKD", "700").ticker()).isEqualTo("00700");
        assertThat(BrokerTicker.fromIbkr("SEHKSZSE", "CNH", "000001").market()).isEqualTo(com.family.finance.domain.stock.Market.CN);
        // 伦敦上市的美元 ETF:币种是 USD,但不是美股
        assertThat(BrokerTicker.fromIbkr("LSEETF", "USD", "VWRA")).isNull();
        assertThat(BrokerTicker.fromIbkr("NYSE", "USD", "BRK B").ticker()).isEqualTo("BRK.B");
    }
}
