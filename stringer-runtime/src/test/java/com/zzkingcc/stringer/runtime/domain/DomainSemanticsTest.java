package com.zzkingcc.stringer.runtime.domain;

import com.zzkingcc.stringer.api.agent.Domains;
import com.zzkingcc.stringer.api.annotation.Tool;
import com.zzkingcc.stringer.api.tool.ToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 域语义回归测试 —— 盯住几条最容易在后续改动里被"顺手改回去"的规则：
 * 域标识是从根域出发的完整路径、沿链补齐不留悬空、删除递归带走子孙、根域不可删、
 * 三种来源同级（只有根域因是树起点而例外）。
 *
 * @author zzkingcc
 */
class DomainSemanticsTest {

    /** 构造一个只关心域声明的工具描述符 */
    private static ToolDescriptor toolWithDomains(String... domains) {
        return new ToolDescriptor("demoTool", "演示工具", "default", "1.0.0",
                Tool.Effect.READ, true, true,
                List.of(), List.of(domains), null, "test#demoTool");
    }

    @Test
    void normalizeFallsBackToDefaultDomain() {
        assertEquals(Domains.DEFAULT, Domains.normalize(null));
        assertEquals(Domains.DEFAULT, Domains.normalize("   "));
        assertEquals("default.sales", Domains.normalize(" default.sales "));
        assertTrue(Domains.isDefault(null));
        assertFalse(Domains.isDefault("default.sales"));
        assertEquals("default", Domains.DEFAULT);
        assertEquals("*", Domains.ANY);
    }

    // ===== 工具可见性（第 2 步改为沿链累加时，下面三条随之重写） =====

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

    // ==================== 路径运算 ====================

    @Test
    void pathChainIsSplitFromRoot() {
        assertEquals(List.of("default", "default.a", "default.a.b"), Domains.chainOf("default.a.b"));
        assertEquals(List.of("default"), Domains.chainOf("default"));
        assertEquals(List.of(), Domains.chainOf(null));
    }

    @Test
    void ancestorsExcludeSelf() {
        assertEquals(List.of("default", "default.a"), Domains.ancestorsOf("default.a.b"));
        assertEquals(List.of(), Domains.ancestorsOf("default"));
    }

    @Test
    void parentOfIsTheNearestAncestor() {
        assertEquals("default.a", Domains.parentOf("default.a.b"));
        assertNull(Domains.parentOf("default"), "根域没有父");
        assertNull(Domains.parentOf(null));
    }

    // ==================== 路径校验 ====================

    @Test
    void pathMustStartFromRoot() {
        assertNotNull(Domains.validatePath("sales"), "不从根出发的域必须被拒");
        assertNotNull(Domains.validatePath("other.sales"));
        assertNull(Domains.validatePath("default.sales"));
    }

    @Test
    void pathRejectsIllegalSegment() {
        assertNotNull(Domains.validatePath("default.sales.order!"), "段含非法字符");
        assertNotNull(Domains.validatePath("default.sa les"), "段含空白");
        assertNotNull(Domains.validatePath("default..sales"), "空段");
        assertNotNull(Domains.validatePath("default."), "尾部分隔符");
        assertNotNull(Domains.validatePath(Domains.ANY), "通配符不是合法域路径");
    }

    @Test
    void pathRejectsDuplicatedSegmentOnItsOwnChain() {
        assertNotNull(Domains.validatePath("default.a.a"), "自身链上重复段");
        assertNotNull(Domains.validatePath("default.a.b.a"));
        // 同名不同父是允许的：两段之间隔着别的节点，不构成链上重复
        assertNull(Domains.validatePath("default.a.x"));
        assertNull(Domains.validatePath("default.b.x"));
    }

    // ==================== 注册表 ====================

    @Test
    void defaultDomainIsRootAndProtected() {
        DomainRegistry registry = new DomainRegistry();

        assertTrue(registry.isBuiltin(Domains.DEFAULT), "default 必须是根域");
        assertTrue(registry.contains(Domains.DEFAULT));
        assertFalse(registry.delete(Domains.DEFAULT).deleted(), "根域不可删除");
        assertFalse(registry.create(Domains.DEFAULT).created(), "根域不可重复创建");
        assertFalse(registry.create(Domains.ANY).created(), "通配符不是合法域路径");
        assertFalse(registry.create("  ").created(), "空白域名应被拒");
    }

    @Test
    void creatingADomainLinksTheWholeChain() {
        DomainRegistry registry = new DomainRegistry();

        assertTrue(registry.create("default.sales.order").created());
        // 中间节点必须一并存在，否则这就是个悬空节点
        assertTrue(registry.contains("default.sales"));
        assertTrue(registry.contains("default.sales.order"));
        assertEquals("default.sales", registry.all().stream()
                .filter(d -> "default.sales.order".equals(d.id()))
                .findFirst().orElseThrow().parentId());
        assertNull(registry.all().stream()
                .filter(d -> "default".equals(d.id()))
                .findFirst().orElseThrow().parentId(), "根域没有父");
    }

    @Test
    void manualDomainsCanBeCreatedAndDeleted() {
        DomainRegistry registry = new DomainRegistry();

        assertTrue(registry.create("default.customer-service").created());
        assertTrue(registry.contains("default.customer-service"));
        assertTrue(registry.manualIds().contains("default.customer-service"));
        assertFalse(registry.create("default.customer-service").created(), "重复创建应失败");

        assertTrue(registry.delete("default.customer-service").deleted());
        assertFalse(registry.contains("default.customer-service"));
        assertTrue(registry.manualIds().isEmpty());
    }

    @Test
    void deletingADomainTakesAllDescendants() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.sales.order");
        registry.create("default.sales.order.refund");
        registry.create("default.hr");

        assertEquals(List.of("default.sales.order.refund"), registry.descendantsOf("default.sales.order"));
        assertEquals(2, registry.descendantsOf("default.sales").size());

        var deleted = registry.delete("default.sales");
        assertTrue(deleted.deleted());
        // 递归带走子孙，而不是把子孙提升到根下
        assertFalse(registry.contains("default.sales"));
        assertFalse(registry.contains("default.sales.order"));
        assertFalse(registry.contains("default.sales.order.refund"));
        assertTrue(registry.contains("default.hr"), "旁支不受影响");
        assertEquals(List.of("default.sales", "default.sales.order", "default.sales.order.refund"),
                deleted.removed());
    }

    @Test
    void derivedAndManualSourcesAreEqualInDeleteRules() {
        DomainRegistry registry = new DomainRegistry();
        registry.ensureChain("default.fromTool");
        registry.create("default.fromConsole");

        assertEquals(DomainRegistry.Source.DERIVED, registry.sourceOf("default.fromTool"));
        assertEquals(DomainRegistry.Source.MANUAL, registry.sourceOf("default.fromConsole"));
        // 来源只标记出身，不构成等级：派生域同样可删
        assertTrue(registry.delete("default.fromTool").deleted());
        assertTrue(registry.delete("default.fromConsole").deleted());
    }

    @Test
    void existingNodesKeepTheirSourceWhenRelinked() {
        DomainRegistry registry = new DomainRegistry();
        registry.ensureChain("default.sales");
        assertEquals(DomainRegistry.Source.DERIVED, registry.sourceOf("default.sales"));

        // 管控台复用同一条链时，已存在节点不该被改写成 MANUAL
        registry.create("default.sales.order");
        assertEquals(DomainRegistry.Source.DERIVED, registry.sourceOf("default.sales"));
        assertEquals(DomainRegistry.Source.MANUAL, registry.sourceOf("default.sales.order"));
    }

    @Test
    void illegalPersistedDomainsAreSkipped() {
        DomainRegistry registry = new DomainRegistry();
        registry.loadManual(List.of(Domains.ANY, "legacy-short-name", "default.kept"));

        assertFalse(registry.contains(Domains.ANY), "落盘里的通配符不应被当成域");
        assertFalse(registry.contains("legacy-short-name"), "非完整路径的存量域应被跳过");
        assertTrue(registry.contains("default.kept"));
    }
}
