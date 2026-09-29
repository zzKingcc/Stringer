package com.zzkingcc.stringer.server.prompt;

import com.zzkingcc.stringer.api.annotation.Tool;
import com.zzkingcc.stringer.api.tool.ToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词 ↔ 工具可见性自检：只认"提示词里点名了工具名"这一种不一致，且必须按词边界匹配。
 */
class PromptToolConsistencyAuditTest {

    /** 与注解扫描同构的工具描述符：只有名字与域声明是变量 */
    private static ToolDescriptor tool(String name, String... domains) {
        return new ToolDescriptor(name, name + " 的说明", "default", "1.0.0",
                Tool.Effect.READ, true, true,
                List.of(), List.of(domains), null, "test#" + name);
    }

    /** 各域都只有公共基线、没有域差异 */
    private static Map<String, String> prompts(String base, String... domains) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String domain : domains) {
            out.put(domain, base);
        }
        return out;
    }

    /** 给某个域追加一段差异片段（该域实际提示词 = 基线 + 差异） */
    private static Map<String, String> withDiff(Map<String, String> promptByDomain,
                                                String domain, String diff) {
        Map<String, String> out = new LinkedHashMap<>(promptByDomain);
        out.put(domain, promptByDomain.get(domain) + diff);
        return out;
    }

    @Test
    void 公共基线点名了不可见的工具就报出来() {
        List<ToolDescriptor> tools = List.of(tool("queryOrder", "customer"), tool("ping", "*"));
        String base = "你可以用 queryOrder 查订单，用 ping 探活。";

        List<String> mismatches = PromptToolConsistencyAudit.findMismatches(
                tools, Set.of("customer", "admin"), prompts(base, "customer", "admin"), base);

        assertEquals(1, mismatches.size(), "ping 全域可见，不该报");
        assertTrue(mismatches.get(0).contains("queryOrder"));
        assertTrue(mismatches.get(0).contains("admin"), "要列全它不可见的域");
        assertFalse(mismatches.get(0).contains("customer"), "customer 可见，不该出现在不可见清单里");
    }

    @Test
    void 域差异片段点名而该域不可见也报() {
        List<ToolDescriptor> tools = List.of(tool("businessReport", "admin"));
        String base = "你是客服助手。";
        Map<String, String> promptByDomain = withDiff(
                prompts(base, "admin", "customer"),
                "customer", "\n需要经营数据时调用 businessReport。");

        List<String> mismatches = PromptToolConsistencyAudit.findMismatches(
                tools, Set.of("admin", "customer"), promptByDomain, base);

        assertEquals(1, mismatches.size());
        assertTrue(mismatches.get(0).contains("customer"));
        assertTrue(mismatches.get(0).contains("businessReport"));
    }

    @Test
    void 基线已经报过的工具不再按域差异重复报() {
        List<ToolDescriptor> tools = List.of(tool("closeOrder", "admin"));
        String base = "关单请用 closeOrder。";

        List<String> mismatches = PromptToolConsistencyAudit.findMismatches(
                tools, Set.of("admin", "customer"),
                prompts(base, "admin", "customer"), base);

        assertEquals(1, mismatches.size(), "同一条事实只该说一次");
        assertTrue(mismatches.get(0).startsWith("公共基线点名了工具 closeOrder"));
    }

    @Test
    void 一致时没有输出() {
        List<ToolDescriptor> tools = List.of(tool("queryOrder", "customer"), tool("ping", "*"));
        // "一致"的写法：基线只点名全域可见的工具；customer 专属的 queryOrder 交给工具自带的 desc 去说明
        String base = "你可以用 ping 探活。";

        assertTrue(PromptToolConsistencyAudit.findMismatches(
                tools, Set.of("customer", "admin"),
                prompts(base, "customer", "admin"), base).isEmpty());

        assertTrue(PromptToolConsistencyAudit.findMismatches(
                List.of(), Set.of("customer"), Map.of(), "").isEmpty(), "没有工具就没有不一致");
    }

    @Test
    void 提示词里没点名工具时什么都不报() {
        List<ToolDescriptor> tools = List.of(tool("queryOrder", "customer"));
        String base = "你可以帮用户查订单（用业务语言描述能力，不写工具名）。";

        assertTrue(PromptToolConsistencyAudit.findMismatches(
                tools, Set.of("admin"), prompts(base, "admin"), base).isEmpty(),
                "只认工具名：中文口语描述能力属刻意漏报，避免误报");
    }

    @Test
    void 点名按词边界匹配() {
        assertTrue(PromptToolConsistencyAudit.mentions("调用 queryOrder 查订单", "queryOrder"));
        assertTrue(PromptToolConsistencyAudit.mentions("queryOrder", "queryOrder"), "整串即点名");
        assertTrue(PromptToolConsistencyAudit.mentions("用 `queryOrder` 查", "queryOrder"), "标点包裹算边界");
        assertFalse(PromptToolConsistencyAudit.mentions("调用 queryOrderDetail", "queryOrder"),
                "更长的标识符里出现同名子串不算点名");
        assertFalse(PromptToolConsistencyAudit.mentions("调用 xqueryOrder", "queryOrder"));
        assertFalse(PromptToolConsistencyAudit.mentions(null, "queryOrder"));
        assertFalse(PromptToolConsistencyAudit.mentions("随便写点什么", ""));
    }
}
