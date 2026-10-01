package com.zzkingcc.stringer.infrastructure.elasticsearch.retriever;

import com.zzkingcc.stringer.api.agent.Domains;
import com.zzkingcc.stringer.api.support.RetrievalScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 按域过滤：取值怎么算，以及"未绑定域"必须是不做过滤（否则非对话路径会凭空查不到东西）。
 */
class DomainFilterQueryTest {

    @Test
    void 可见取值是自身加全部祖先() {
        assertEquals(List.of("default", "default.sales", "default.sales.order"),
                DomainFilterQuery.visibleValues("default.sales.order"),
                "累加语义：祖先域上的内容，后代域都检索得到");
        assertEquals(List.of(Domains.DEFAULT), DomainFilterQuery.visibleValues(null),
                "未指定域归一化为根域，与工具可见性一致");
    }

    @Test
    void 未绑定域时不过滤() {
        RetrievalScope.clear();
        assertNull(DomainFilterQuery.build(), "非对话路径（管控台预览/重建）不该被域过滤");
    }

    @Test
    void 绑定域后给出过滤条件() {
        try {
            RetrievalScope.bind("default.sales");
            assertNotNull(DomainFilterQuery.build());
        } finally {
            RetrievalScope.clear();
        }
    }

    @Test
    void wrap在没有过滤条件时原样返回() {
        RetrievalScope.clear();
        var inner = co.elastic.clients.elasticsearch._types.query_dsl.Query
                .of(q -> q.matchAll(m -> m));
        assertSame(inner, DomainFilterQuery.wrap(inner));
    }
}
