package com.family.finance.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.19.12 · 创建 Agent 失败时,我们附在百炼原话后面的那句提示。
 *
 * <p>这几条测的不是文案好不好看,而是<b>它有没有把人指错方向</b> ——
 * 上一版那句「核对业务空间 ID / MCP 服务 ID / 公网地址」在用户三样全对的情况下照样输出,
 * 白白耗掉一整轮排查。所以这里守两件事:认得出的错要说准,认不出的要承认自己在猜。</p>
 */
class AiAccessHintTest {

    @Test
    @DisplayName("模型不存在 → 指向业务空间的模型调用权限,而不是模型名写错")
    void modelNotFoundPointsAtWorkspacePermission() {
        for (String raw : new String[]{
                "upstream 400 · AGENT_010",
                "upstream 400 · 模型不存在: model=qwen-plus",
                "upstream 400 · Model not found"}) {
            String h = AiAccessController.hint(raw);
            assertThat(h).as(raw).contains("调用权限");
            // 反面同样重要:不能再把人往「三个 ID 核对一遍」那条错路上引
            assertThat(h).as(raw).doesNotContain("公网地址");
        }
    }

    @Test
    @DisplayName("模型这条要明说「聊天能用不代表这里能用」—— 这正是最难自己想到的一点")
    void modelHintWarnsAboutChatEndpointFalsePositive() {
        assertThat(AiAccessController.hint("upstream 400 · 模型不存在: model=qwen-plus"))
                .contains("普通对话接口");
    }

    @Test
    @DisplayName("403 指业务空间 ID · 401 指 Key · MCP 指同一空间")
    void otherKnownShapes() {
        assertThat(AiAccessController.hint("upstream 403 · Endpoint.AccessDenied"))
                .contains("业务空间 ID");
        assertThat(AiAccessController.hint("upstream 401 · InvalidApiKey"))
                .contains("API Key");
        assertThat(AiAccessController.hint("upstream 400 · mcpServers[0].type 取值非法"))
                .contains("同一个业务空间");
    }

    @Test
    @DisplayName("405 要说清「这是我们的 bug」—— 用户改配置永远修不好它")
    void methodNotAllowedIsOurBug() {
        for (String raw : new String[]{"upstream 405 · 请求方法不支持", "upstream 405"}) {
            String h = AiAccessController.hint(raw);
            assertThat(h).as(raw).contains("本应用的 bug");
            // 反面:不能把人引去核对配置 —— 405 跟配置一点关系都没有
            assertThat(h).as(raw).doesNotContain("业务空间 ID");
        }
    }

    @Test
    @DisplayName("认不出来的错要承认在猜,不给笃定的错方向")
    void unknownShapeAdmitsUncertainty() {
        String h = AiAccessController.hint("upstream 500 · something we have never seen");
        assertThat(h).contains("没见过");
    }

    @Test
    @DisplayName("null / 空 不能抛 —— 上游异常没有 message 是完全可能的")
    void nullSafe() {
        assertThat(AiAccessController.hint(null)).isNotBlank();
        assertThat(AiAccessController.hint("")).isNotBlank();
    }
}
