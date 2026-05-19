package com.family.finance.service.checkup.llm;

import com.family.finance.domain.member.Member;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.4.14 FR-63 私密隔离防回归 · 合规底线(与 [[feedback_llm_no_math]] 同级纪律)。
 *
 * <p>红线:成员手机号(member.phone)与短信平台 aksk(family_notify_config.*)
 * 绝不可出现在任何 LLM prompt / 任何 AI 交互上下文中。
 *
 * <p>两道防线:
 * <ol>
 *   <li><b>行为</b>:PromptBuilder.buildNameMapping 是白名单式,只吐 displayName→代号,
 *       即便 Member 带 phone 也不外泄;</li>
 *   <li><b>结构</b>:扫描整个 LLM prompt 构造目录源码,断言没有任何文件引用
 *       phone / aksk 私密访问器 —— 未来谁把私密字段接进 prompt 路径,编译期单测即红。</li>
 * </ol>
 */
class PrivacyIsolationTest {

    private static final String SECRET_PHONE = "13800001234";
    private static final String SECRET_AK = "LTAI5tFAKEakidEXAMPLE";

    /** 防线 1 · 即便成员带手机号,脱敏映射也只产出代号,不含手机号串。 */
    @Test
    void nameMappingNeverLeaksPhone() {
        Member m1 = Member.builder().id(1L).displayName("张三").phone(SECRET_PHONE).build();
        Member m2 = Member.builder().id(2L).displayName("李四").phone("13900005678").build();

        var mapping = PromptBuilder.buildNameMapping(List.of(m1, m2));

        String dump = mapping.realToCodename().toString() + mapping.codenameToReal().toString();
        assertThat(dump).doesNotContain(SECRET_PHONE);
        assertThat(dump).doesNotContain("13900005678");
        assertThat(mapping.realToCodename()).containsKeys("张三", "李四");
        assertThat(mapping.codenameToReal().values()).containsExactly("张三", "李四");
    }

    /** 防线 1 · applyMapping 只替换真名,绝不把手机号引入文本。 */
    @Test
    void applyMappingDoesNotIntroducePhone() {
        var mapping = PromptBuilder.buildNameMapping(
                List.of(Member.builder().id(1L).displayName("张三").phone(SECRET_PHONE).build()));
        String note = "张三 本期工资入账";
        String out = PromptBuilder.applyMapping(note, mapping.realToCodename());
        assertThat(out).doesNotContain(SECRET_PHONE);
        assertThat(out).contains("成员A");
    }

    /**
     * 防线 2 · 静态源码扫描:LLM prompt 构造目录下任何 .java 都不得引用
     * 手机号 / 短信 aksk 的私密访问器或私密载体类型。
     */
    @Test
    void llmPromptSourcesNeverReferencePrivateChannels() throws IOException {
        Path llmDir = Path.of("src/main/java/com/family/finance/service/checkup/llm");
        assertThat(Files.isDirectory(llmDir))
                .as("LLM prompt 目录应存在").isTrue();

        // 短信 / 手机号红线 · 全 LLM 目录(含 LlmClient)都不能引用
        List<String> forbidden = List.of(
                "getPhone", "setPhone", ".phone",
                "AccessKeySecret", "getSmsAccessKey",
                "FamilyNotifyConfig", "SmsAliyunChannel", "FamilyNotifyConfigMapper");

        try (Stream<Path> walk = Files.walk(llmDir)) {
            List<Path> javaFiles = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
            assertThat(javaFiles).as("应扫描到 LLM 源文件").isNotEmpty();

            for (Path f : javaFiles) {
                String src = Files.readString(f);
                for (String token : forbidden) {
                    assertThat(src)
                            .as("LLM prompt 源文件 %s 不得引用私密渠道符号 [%s]",
                                    f.getFileName(), token)
                            .doesNotContain(token);
                }
            }
        }
    }

    /**
     * 防线 3 · v0.4.18 · PromptBuilder 是 prompt 文本生成器 ·
     * 绝不可引用任何配置 service / LLM API key 访问器 ·
     * 避免误把 secret 拼进 prompt 文本。
     *
     * <p>LlmClient(QwenLlmClient / DeepSeekLlmClient)允许引用 K_LLM_*_KEY
     * (它们是通过 HTTP header 把 key 提交给上游 LLM API · 不进 prompt body)·
     * 故此校验只针对 PromptBuilder 文件本身。
     */
    @Test
    void promptBuilderNeverReferencesAnyPrivateAccessor() throws IOException {
        Path promptBuilder = Path.of("src/main/java/com/family/finance/service/checkup/llm/PromptBuilder.java");
        assertThat(Files.isRegularFile(promptBuilder))
                .as("PromptBuilder.java 应存在").isTrue();
        String src = Files.readString(promptBuilder);

        // PromptBuilder 是 prompt 文本生成器 · 绝对不读任何私密字段
        List<String> forbidden = List.of(
                // SMS 红线
                "getPhone", "setPhone", "AccessKeyId", "AccessKeySecret",
                "FamilyNotifyConfig", "SmsAliyunChannel",
                // v0.4.18 · LLM API key 红线(LlmClient 可读 · PromptBuilder 不能)
                "K_LLM_QWEN_KEY", "K_LLM_DEEPSEEK_KEY",
                "llm_qwen_api_key", "llm_deepseek_api_key",
                "currentApiKey", "FamilyConfigService");

        for (String token : forbidden) {
            assertThat(src)
                    .as("PromptBuilder.java 不得引用私密符号 [%s](会泄露进 LLM prompt 内容)", token)
                    .doesNotContain(token);
        }
    }
}
