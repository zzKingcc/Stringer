package com.zzkingcc.stringer.server.knowledge;

import com.zzkingcc.stringer.api.agent.Domains;
import com.zzkingcc.stringer.common.exception.KnowledgeBaseException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 知识库文档的域声明：与 {@code @Tool(domains = {...})} 同构 ——
 * 每项都是从根域出发的完整路径，留空 = 挂在根域（累加后对全树可见），非法路径直接抛异常。
 */
class KnowledgeBaseServiceTest {

    @Test
    void 留空或全空白都挂到根域() {
        assertEquals(List.of(Domains.DEFAULT), KnowledgeBaseService.normalizeDomains(null));
        assertEquals(List.of(Domains.DEFAULT), KnowledgeBaseService.normalizeDomains(List.of()));
        // List.of 不吃 null，这里必须用 Arrays.asList
        assertEquals(List.of(Domains.DEFAULT),
                KnowledgeBaseService.normalizeDomains(Arrays.asList("", "  ", null)));
    }

    @Test
    void 去空白与去重后保持顺序() {
        assertEquals(List.of("default.sales", "default.hr"),
                KnowledgeBaseService.normalizeDomains(
                        List.of(" default.sales ", "default.hr", "default.sales")));
    }

    @Test
    void 非法路径直接抛异常而不是静默丢弃() {
        // 静默会把文档写到树里不存在的域上，结果是永远检索不到且不报错
        assertThrows(KnowledgeBaseException.class,
                () -> KnowledgeBaseService.normalizeDomains(List.of("sales")), "不从根出发");
        assertThrows(KnowledgeBaseException.class,
                () -> KnowledgeBaseService.normalizeDomains(List.of("*")), "通配不再是合法域");
        assertThrows(KnowledgeBaseException.class,
                () -> KnowledgeBaseService.normalizeDomains(List.of("default.sa les")), "段含空白");
    }

    @Test
    void 没有domains字段时视为挂根域() {
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("file_name", "退款政策.md");
        assertEquals(List.of(Domains.DEFAULT), KnowledgeBaseService.domainsOf(md),
                "未声明域的文档挂在根域，与工具声明留空的语义一致");
    }

    @Test
    void 列表与单值都能读出来() {
        assertEquals(List.of("default.sales"),
                KnowledgeBaseService.domainsOf(Map.of("domains", List.of("default.sales"))));
        assertEquals(List.of("default"),
                KnowledgeBaseService.domainsOf(Map.of("domains", "default")));
        assertEquals(List.of(Domains.DEFAULT),
                KnowledgeBaseService.domainsOf(Map.of("domains", List.of())),
                "空列表等同未声明域：挂根域");
    }
}
