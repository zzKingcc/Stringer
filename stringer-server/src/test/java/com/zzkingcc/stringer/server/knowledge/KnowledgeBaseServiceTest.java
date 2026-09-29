package com.zzkingcc.stringer.server.knowledge;

import com.zzkingcc.stringer.api.agent.Domains;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库文档的域声明：与 {@code @Tool(domains = {...})} 同构，且历史文档不能被"过滤掉"。
 */
class KnowledgeBaseServiceTest {

    @Test
    void 留空或不合法都归到兜底域() {
        assertEquals(List.of(Domains.DEFAULT), KnowledgeBaseService.normalizeDomains(null));
        assertEquals(List.of(Domains.DEFAULT), KnowledgeBaseService.normalizeDomains(List.of()));
        // List.of 不吃 null，这里必须用 Arrays.asList
        assertEquals(List.of(Domains.DEFAULT), KnowledgeBaseService.normalizeDomains(Arrays.asList("", "  ", null)));
    }

    @Test
    void 去空白与去重后保持顺序() {
        assertEquals(List.of("customer", "admin"),
                KnowledgeBaseService.normalizeDomains(List.of(" customer ", "admin", "customer")));
    }

    @Test
    void 出现通配就只剩通配() {
        assertEquals(List.of("*"), KnowledgeBaseService.normalizeDomains(List.of("customer", "*")));
        assertEquals(List.of("*"), KnowledgeBaseService.normalizeDomains(List.of("*")));
    }

    @Test
    void 历史文档没有domains字段时按全域可见() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("file_name", "退款政策.md");
        assertEquals(List.of("*"), KnowledgeBaseService.domainsOf(legacy),
                "升级前入库的文档没有 domains 字段 —— 视作全域可见，否则它们会凭空从所有域消失");
    }

    @Test
    void 列表与单值都能读出来() {
        assertEquals(List.of("admin"),
                KnowledgeBaseService.domainsOf(Map.of("domains", List.of("admin"))));
        assertEquals(List.of("default"),
                KnowledgeBaseService.domainsOf(Map.of("domains", "default")));
        assertTrue(KnowledgeBaseService.domainsOf(Map.of("domains", List.of())).contains("*"),
                "空列表等同没有该字段：按历史文档处理");
    }
}
