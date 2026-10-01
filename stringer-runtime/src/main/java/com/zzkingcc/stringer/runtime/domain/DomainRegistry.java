package com.zzkingcc.stringer.runtime.domain;

import com.zzkingcc.stringer.api.agent.Domains;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 域注册表 —— 域树的唯一登记处。
 *
 * <p>域以<b>完整路径</b>为标识（{@code default.sales.order}），根为 {@link Domains#DEFAULT}。
 * 登记一律走<b>沿链补齐</b>：给一条完整路径，链上缺失的祖先节点一并建出，已存在的节点原样复用
 * （不改它的 {@link Source}）。因此不存在悬空节点 —— 每个域都挂得到根上。</p>
 *
 * <p>{@link Source} 只标记<b>这个域是怎么来的</b>，不是等级：三种来源同级，删除规则一视同仁
 * （只有根域因是整棵树的起点而不可删）。来源差异仅体现在生命周期上 ——
 * {@code MANUAL} 落盘、重启仍在；{@code DERIVED} 由工具声明产生、不落盘，重启后随声明重建。</p>
 *
 * @author zzkingcc
 */
public class DomainRegistry {

    private static final Logger log = LoggerFactory.getLogger(DomainRegistry.class);

    /** 域的来源（仅标记出身，不构成等级） */
    public enum Source {
        /** 根域，启动即存在、不可删除 */
        BUILTIN,
        /** 管控台创建，落盘后重启仍在 */
        MANUAL,
        /** 工具声明 / 沿链补齐产生，不落盘，重启后随声明重建 */
        DERIVED
    }

    /**
     * 域条目
     *
     * @param id        完整路径标识
     * @param parentId  父域标识（根域为 {@code null}）
     * @param source    来源
     * @param createdAt 创建时间戳（毫秒）
     */
    public record Domain(String id, String parentId, Source source, long createdAt) {
    }

    /** 创建结果 */
    public record CreateResult(boolean created, String reason) {
        public static CreateResult ok() {
            return new CreateResult(true, null);
        }

        public static CreateResult fail(String reason) {
            return new CreateResult(false, reason);
        }
    }

    /**
     * 删除结果
     *
     * @param removed 实际被删掉的域（含自身与其全部子孙），未删除时为空
     */
    public record DeleteResult(boolean deleted, String reason, List<String> removed) {
        public static DeleteResult ok(List<String> removed) {
            return new DeleteResult(true, null, List.copyOf(removed));
        }

        public static DeleteResult fail(String reason) {
            return new DeleteResult(false, reason, List.of());
        }
    }

    /**
     * 沿链登记结果
     *
     * @param domain  登记的路径
     * @param created 本次<b>新建</b>的节点（已存在的不计入）
     * @param reason  {@code null} 表示成功
     */
    public record LinkResult(String domain, List<String> created, String reason) {
        public static LinkResult ok(String domain, List<String> created) {
            return new LinkResult(domain, List.copyOf(created), null);
        }

        public static LinkResult fail(String reason) {
            return new LinkResult(null, List.of(), reason);
        }

        public boolean ok() {
            return reason == null;
        }
    }

    private final Map<String, Domain> domains = new ConcurrentHashMap<>();

    public DomainRegistry() {
        domains.put(Domains.DEFAULT,
                new Domain(Domains.DEFAULT, null, Source.BUILTIN, System.currentTimeMillis()));
    }

    // ==================== 登记 ====================

    /**
     * 载入管控台创建的域（启动期从落盘恢复），按 {@link Source#MANUAL} 沿链补齐。
     *
     * <p>非法路径只跳过并告警，不阻断启动。</p>
     */
    public void loadManual(Collection<String> ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            LinkResult linked = link(id, Source.MANUAL);
            if (!linked.ok()) {
                log.warn("[域注册表] 落盘域 '{}' 非法（{}），已跳过。"
                                + "域标识须为从 {} 出发的完整路径，如 default.sales",
                        id, linked.reason(), Domains.DEFAULT);
            }
        }
    }

    /**
     * 管控台创建域：按 {@link Source#MANUAL} 沿链补齐。
     *
     * @return 成功；或失败原因（路径非法 / 已存在）
     */
    public CreateResult create(String domainId) {
        LinkResult linked = link(domainId, Source.MANUAL);
        if (!linked.ok()) {
            return CreateResult.fail(linked.reason());
        }
        if (linked.created().isEmpty()) {
            return CreateResult.fail("域已存在：" + domainId.trim()
                    + "（来源 " + sourceOf(domainId) + "）");
        }
        log.info("[域注册表] 已创建域 {}（新建节点 {}，来源 MANUAL）",
                linked.domain(), linked.created());
        return CreateResult.ok();
    }

    /**
     * 工具声明派生：按 {@link Source#DERIVED} 沿链补齐。已存在节点保持原来源不变。
     *
     * <p>幂等 —— 重复声明同一个域不会改写它。</p>
     */
    public LinkResult ensureChain(String domainId) {
        LinkResult linked = link(domainId, Source.DERIVED);
        if (linked.ok() && !linked.created().isEmpty()) {
            log.info("[域注册表] 工具声明派生域 {}（新建节点 {}，来源 DERIVED）",
                    linked.domain(), linked.created());
        }
        return linked;
    }

    /** 批量派生；非法路径只跳过并告警 */
    public void ensureChains(Collection<String> ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            LinkResult linked = ensureChain(id);
            if (!linked.ok()) {
                log.warn("[域注册表] 工具声明的域 '{}' 非法（{}），已跳过。"
                                + "须为从 {} 出发的完整路径，如 default.sales",
                        id, linked.reason(), Domains.DEFAULT);
            }
        }
    }

    /**
     * 沿链登记：路径上每一段都确保存在，缺失的按 {@code source} 建出，已存在的原样保留。
     */
    private synchronized LinkResult link(String domainId, Source source) {
        String reason = Domains.validatePath(domainId);
        if (reason != null) {
            return LinkResult.fail(reason);
        }
        String id = domainId.trim();
        List<String> created = new ArrayList<>();
        for (String step : Domains.chainOf(id)) {
            if (domains.containsKey(step)) {
                continue;
            }
            domains.put(step, new Domain(step, Domains.parentOf(step), source, System.currentTimeMillis()));
            created.add(step);
        }
        return LinkResult.ok(id, created);
    }

    // ==================== 删除 ====================

    /**
     * 删除域及其<b>全部子孙</b>（递归，不向上提升层级）。
     *
     * <p>只有根域拒绝删除 —— 它是整棵树的起点。其余域不论来源都可删；
     * 派生域删掉后重启会随工具声明重建，这是它生命周期的固有结果，不另设限制。</p>
     */
    public DeleteResult delete(String domainId) {
        String reason = Domains.validatePath(domainId);
        if (reason != null) {
            return DeleteResult.fail(reason);
        }
        String id = domainId.trim();
        if (Domains.DEFAULT.equals(id)) {
            return DeleteResult.fail("根域不可删除：" + id + "（它是整棵域树的起点）");
        }
        if (!domains.containsKey(id)) {
            return DeleteResult.fail("域不存在：" + id);
        }
        String prefix = id + Domains.SEPARATOR;
        List<String> removed = new ArrayList<>();
        for (String candidate : domains.keySet()) {
            if (candidate.equals(id) || candidate.startsWith(prefix)) {
                removed.add(candidate);
            }
        }
        removed.forEach(domains::remove);
        removed.sort(Comparator.naturalOrder());
        log.info("[域注册表] 已删除域 {} 及其 {} 个子孙 {}", id, removed.size() - 1, removed);
        return DeleteResult.ok(removed);
    }

    // ==================== 查询 ====================

    /** 该域是否已被登记 */
    public boolean contains(String domainId) {
        return domains.containsKey(Domains.normalize(domainId));
    }

    /** 已登记的域标识（全部来源） */
    public Set<String> ids() {
        return Set.copyOf(domains.keySet());
    }

    /** 全部条目，按标识排序，便于展示 */
    public List<Domain> all() {
        List<Domain> list = new ArrayList<>(domains.values());
        list.sort(Comparator.comparing(Domain::id));
        return List.copyOf(list);
    }

    /** 该域的登记来源；未登记返回 {@code null} */
    public Source sourceOf(String domainId) {
        Domain domain = domains.get(Domains.normalize(domainId));
        return domain == null ? null : domain.source();
    }

    /** 是否根域（{@code null} / 空白视为根域） */
    public boolean isBuiltin(String domainId) {
        return sourceOf(domainId) == Source.BUILTIN;
    }

    /**
     * 该域的<b>全部子孙</b>（不含自身），按标识排序。
     */
    public List<String> descendantsOf(String domainId) {
        String id = Domains.normalize(domainId);
        String prefix = id + Domains.SEPARATOR;
        return domains.keySet().stream()
                .filter(candidate -> candidate.startsWith(prefix))
                .sorted()
                .collect(Collectors.toList());
    }

    /** 管控台创建的域标识（落盘用） */
    public Set<String> manualIds() {
        return domains.values().stream()
                .filter(d -> d.source() == Source.MANUAL)
                .map(Domain::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 树形结构的缩进深度（根为 0），供管控台展示 */
    public static int depthOf(String domainId) {
        return Domains.ancestorsOf(domainId).size();
    }

    /** 根域的直接子节点数，供启动日志陈述规模 */
    public long size() {
        return domains.size();
    }
}
