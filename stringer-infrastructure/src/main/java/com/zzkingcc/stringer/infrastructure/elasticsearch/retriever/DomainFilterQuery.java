package com.zzkingcc.stringer.infrastructure.elasticsearch.retriever;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.zzkingcc.stringer.api.agent.Domains;
import com.zzkingcc.stringer.api.support.RetrievalScope;
import com.zzkingcc.stringer.infrastructure.elasticsearch.EsIndexManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 知识库的<b>按域过滤条件</b>（两路检索通道共用）。
 *
 * <p>过滤必须<b>下推到每个检索通道内部</b>，不能放到融合之后：两路各自只回 Top-N，
 * 混进其他域的文档会把本域结果挤掉，融合后再过滤就只剩一两条 —— 检索"成功了"但召回塌陷，
 * 而且这个现象没有任何报错。</p>
 *
 * <p>判定与工具的域声明同构：文档 {@code domains} 含当前域或 {@code "*"} 即命中。</p>
 *
 * @author zzkingcc
 */
public final class DomainFilterQuery {

    private static final Logger log = LoggerFactory.getLogger(DomainFilterQuery.class);

    private DomainFilterQuery() {
    }

    /**
     * 当前域能看到的取值：本域 + 通配 {@code *}。
     */
    public static List<String> visibleValues(String domain) {
        String normalized = Domains.normalize(domain);
        if (Domains.ANY.equals(normalized)) {
            return List.of(Domains.ANY);
        }
        return List.of(normalized, Domains.ANY);
    }

    /**
     * 构造过滤条件；检索<b>未绑定域</b>时返回 {@code null}（不做过滤）。
     *
     * <p>未绑定只出现在非对话路径（管控台预览、重建等），保持升级前"全库检索"的行为 ——
     * 否则这些调用会凭空查不到东西。</p>
     */
    public static Query build() {
        String domain = RetrievalScope.current();
        if (domain == null || domain.isBlank()) {
            log.debug("[ES检索] 未绑定检索域，本次不做域过滤");
            return null;
        }
        List<FieldValue> values = visibleValues(domain).stream().map(FieldValue::of).toList();
        String field = EsIndexManager.DOMAINS_QUERY_FIELD;
        return Query.of(q -> q.bool(b -> b
                .should(s -> s.terms(t -> t.field(field).terms(tv -> tv.value(values))))
                // 未声明 domains 的文档只属于兜底域 default，因此过滤条件恒含 default。
                // 否则升级后这些文档会从所有域里凭空消失 —— 那是一次无声的数据丢失，比放宽更难发现。
                .should(s -> s.bool(nb -> nb.mustNot(mn -> mn.exists(e -> e.field(field)))))
                .minimumShouldMatch("1")));
    }

    /**
     * 把过滤条件挂到既有查询上（{@code bool.must} 保留原查询与打分，{@code filter} 只做约束不计分）。
     *
     * @param inner 原本的检索查询（script_score / multi_match）
     * @return 带域约束的查询；未绑定域时原样返回
     */
    public static Query wrap(Query inner) {
        Query filter = build();
        if (filter == null || inner == null) {
            return inner;
        }
        return Query.of(q -> q.bool(b -> b.must(inner).filter(filter)));
    }
}
