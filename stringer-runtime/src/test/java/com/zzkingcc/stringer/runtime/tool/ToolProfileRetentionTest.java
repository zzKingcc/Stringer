package com.zzkingcc.stringer.runtime.tool;

import com.zzkingcc.stringer.api.tool.ToolDescriptor;
import com.zzkingcc.stringer.runtime.domain.DomainRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 域的存活：工具断开（熔断 / 判死摘副本）不该让域跟着消失，入口也不该把它判成不存在。
 */
class ToolProfileRetentionTest {

    private final ToolRegistry registry = new ToolRegistry();

    private static ToolRegistry.Registered tool(String name, String source, List<String> profiles) {
        ToolDescriptor d = new ToolDescriptor(name, "desc", "cat", "1",
                null, true, true, List.of(), profiles, null, source);
        ToolSpecification spec = ToolSpecification.builder().name(name).description("desc").build();
        ToolExecutor noop = (request, context) -> "ok";
        return new ToolRegistry.Registered(d, spec, noop, List.of());
    }

    /** 域一经声明就不再抹掉：副本全部摘除后，它仍在已知域里、入口仍受理 */
    @Test
    void knownProfiles_surviveReplicaRemoval() {
        registry.register(tool("local_tool", "stringer", List.of("default.ops")));
        registry.replaceInstanceTools("i1", "http://10.0.0.5:8081/invoke",
                List.of(tool("remote_tool", "remote://i1", List.of("default.finance"))));

        assertTrue(registry.acceptsProfile("default.finance"));

        registry.removeInstance("i1");
        assertTrue(registry.find("remote_tool").isEmpty(), "副本应已摘除");
        assertTrue(registry.knownProfiles().contains("default.finance"), "域不应随工具断开而消失");
        assertTrue(registry.acceptsProfile("default.finance"), "域还可用，不能判 10004");
    }

    /** 见过域之后才开始拦：陌生域仍要被拒，这条 fail-fast 不能松 */
    @Test
    void unknownProfileStillRejectedOnceAnyDeclared() {
        registry.register(tool("local_tool", "stringer", List.of("default.ops")));
        assertFalse(registry.acceptsProfile("default.ghost"));
    }

    /** 工具声明的域必须沿链派生进域树：只声明末节点会留下悬空节点 */
    @Test
    void declarationLinksTheWholeChainIntoDomainTree() {
        DomainRegistry domains = new DomainRegistry();
        ToolRegistry registry = new ToolRegistry(domains);
        registry.register(tool("deep_tool", "stringer", List.of("default.sales.order")));

        assertTrue(domains.contains("default.sales"), "缺失的祖先必须一并建出");
        assertTrue(domains.contains("default.sales.order"));
        assertEquals(DomainRegistry.Source.DERIVED, domains.sourceOf("default.sales"));
    }

    /**
     * 显式删域时要把声明记录一并摘掉：否则被删的域会一直留在"域全集"里
     * （两个清单端点照旧列出它，但它已经不在域树里、也删不掉）。
     */
    @Test
    void forgetProfilesRemovesDeletedDomainsFromKnownSet() {
        registry.register(tool("local_tool", "stringer", List.of("default.ops")));
        registry.replaceInstanceTools("i1", "http://10.0.0.5:8081/invoke",
                List.of(tool("remote_tool", "remote://i1", List.of("default.finance"))));

        // 工具下线后域仍在（这是有意的保留语义）
        registry.removeInstance("i1");
        assertTrue(registry.knownProfiles().contains("default.finance"));

        // 显式删域则把它从保留集合里摘掉
        registry.forgetProfiles(List.of("default.finance"));

        assertFalse(registry.knownProfiles().contains("default.finance"), "被删的域不该再出现在域全集里");
        assertTrue(registry.knownProfiles().contains("default.ops"), "其它域不受影响");
    }
}
