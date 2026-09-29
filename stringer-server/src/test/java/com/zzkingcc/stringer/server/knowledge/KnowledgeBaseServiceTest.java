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
 * 知识库文档的域声明：与 {@code @Tool(domains = {...})} 同构（留空＝只属兜底域）。
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
    void 没有domains字段时只属兜底域() {
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("file_name", "退款政策.md");
        assertEquals(List.of(Domains.DEFAULT), KnowledgeBaseService.domainsOf(md),
                "未声明域的文档只属于兜底域，与工具声明留空的语义一致");
    }

    @Test
    void 列表与单值都能读出来() {
        assertEquals(List.of("admin"),
                KnowledgeBaseService.domainsOf(Map.of("domains", List.of("admin"))));
        assertEquals(List.of("default"),
                KnowledgeBaseService.domainsOf(Map.of("domains", "default")));
        assertEquals(List.of(Domains.DEFAULT),
                KnowledgeBaseService.domainsOf(Map.of("domains", List.of())),
                "空列表等同未声明域：只属于兜底域");
    }
}
