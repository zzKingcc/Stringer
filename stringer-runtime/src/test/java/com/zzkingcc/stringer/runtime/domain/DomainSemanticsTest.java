package com.zzkingcc.stringer.runtime.domain;

import com.zzkingcc.stringer.api.agent.Domains;
import com.zzkingcc.stringer.api.annotation.StringerTool;
import com.zzkingcc.stringer.api.tool.ToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 域语义回归测试 —— 盯住几条最容易在后续改动里被"顺手改回去"的规则：
 * 留空只属于兜底域、通配 {@code *} 才全域可用、兜底域内置不可删、人工域可增删。
 *
 * @author zzkingcc
 */
class DomainSemanticsTest {

    /** 构造一个只关心域声明的工具描述符 */
    private static ToolDescriptor toolWithDomains(String... domains) {
        return new ToolDescriptor("demoTool", "演示工具", "default", "1.0.0",
                StringerTool.SideEffect.READ, true, true,
                List.of(), List.of(domains), null, "test#demoTool");
    }

    @Test
    void normalizeFallsBackToDefaultDomain() {
        assertEquals(Domains.DEFAULT, Domains.normalize(null));
        assertEquals(Domains.DEFAULT, Domains.normalize("   "));
        assertEquals("customer", Domains.normalize(" customer "));
        assertTrue(Domains.isDefault(null));
        assertFalse(Domains.isDefault("customer"));
        assertEquals("default", Domains.DEFAULT);
        assertEquals("*", Domains.ANY);
    }

    @Test
    void emptyDeclarationBelongsToDefaultOnly() {
        ToolDescriptor tool = toolWithDomains();
        assertTrue(tool.visibleIn(Domains.DEFAULT), "兜底域应可见");
        assertTrue(tool.visibleIn(null), "未指定域归一化为兜底域后应可见");
        assertTrue(tool.visibleIn("  "), "空白域同上");
        assertFalse(tool.visibleIn("customer"), "留空不再等于全域可见");
    }

    @Test
    void wildcardDeclarationIsVisibleInEveryDomain() {
        ToolDescriptor tool = toolWithDomains(Domains.ANY);
        assertTrue(tool.visibleIn("customer"));
        assertTrue(tool.visibleIn("admin"));
        assertTrue(tool.visibleIn(Domains.DEFAULT));
        assertTrue(tool.visibleIn(null));
    }

    @Test
    void explicitDeclarationIsVisibleOnlyInThoseDomains() {
        ToolDescriptor tool = toolWithDomains("admin", "finance");
        assertTrue(tool.visibleIn("admin"));
        assertTrue(tool.visibleIn("finance"));
        assertFalse(tool.visibleIn("customer"));
        assertFalse(tool.visibleIn(Domains.DEFAULT), "显式声明某域的工具不自动属于兜底域");
    }

    @Test
    void defaultDomainIsBuiltinAndProtected() {
        DomainRegistry registry = new DomainRegistry();

        assertTrue(registry.isBuiltin(Domains.DEFAULT), "default 必须是内置域");
        assertTrue(registry.contains(Domains.DEFAULT));
        assertFalse(registry.delete(Domains.DEFAULT).deleted(), "兜底域不可删除");
        assertFalse(registry.create(Domains.DEFAULT).created(), "兜底域不可重复创建");
        assertFalse(registry.create(Domains.ANY).created(), "通配符不能作为域名");
        assertFalse(registry.create("  ").created(), "空白域名应被拒");
    }

    @Test
    void manualDomainsCanBeCreatedAndDeleted() {
        DomainRegistry registry = new DomainRegistry();

        assertTrue(registry.create("customer-service").created());
        assertTrue(registry.contains("customer-service"));
        assertTrue(registry.manualIds().contains("customer-service"));
        assertFalse(registry.create("customer-service").created(), "重复创建应失败");

        assertTrue(registry.delete("customer-service").deleted());
        assertFalse(registry.contains("customer-service"));
        assertTrue(registry.manualIds().isEmpty());
    }

    @Test
    void wildcardIsNeverTreatedAsADomain() {
        DomainRegistry registry = new DomainRegistry();
        registry.loadManual(List.of(Domains.ANY, "kept"));

        assertFalse(registry.contains(Domains.ANY), "落盘里的通配符不应被当成域");
        assertTrue(registry.contains("kept"));
    }
}
