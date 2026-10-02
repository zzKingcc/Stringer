package com.zzkingcc.stringer.server.model;

import com.zzkingcc.stringer.api.agent.Domains;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 模型档案注册表 —— 档案与「域 → 档案」绑定的唯一真相。
 *
 * <p>两条规则（与产品口径一致）：</p>
 * <ol>
 *   <li><b>档案不自动绑定任何域</b>：绑定只能由管控台显式写入；新档案建完就是"未绑定"状态；</li>
 *   <li><b>一个域只绑一组模型</b>：绑定是<b>有序列表</b>，整体覆盖，列表首个为当前使用、其余留给多 agent / 降级；
 *       空列表即"无可调用模型"。</li>
 * </ol>
 *
 * <p><b>没有内置对话模型</b>：对话模型只来自「模型设置」页里用户自建的模型档案。域未绑定时沿<b>域链</b>向上找
 * 最近一个绑了模型的祖先（回落的是域链，不是任何内置默认值）；整条链都没绑即"无可调用"，
 * 调用时直接报"未配置"（由 {@code DefaultModelResolver} 抛出）。</p>
 *
 * <p>内存态 + 落盘：每次写操作先改内存、再整体落盘（写盘失败会抛异常，调用方必须感知）。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class ModelProfileRegistry {

    private final ModelProfileStore store;

    /** 当前配置（写操作整体替换，读操作拿不可变副本） */
    private volatile ModelProfileSettings settings;

    public ModelProfileRegistry(ModelProfileStore store) {
        this.store = store;
        this.settings = store.load();
        log.info("[模型档案] 初始化：档案 {} 个 {}，域绑定 {} 条",
                settings.getProfiles().size(), settings.getProfiles().keySet(),
                settings.getDomainBindings().size());
    }

    // ==================== 解析 ====================

    /**
     * 解析该域的可调用模型列表，<b>沿域链回落</b>：自身没绑就向上找最近一个绑了模型的祖先。
     *
     * <p>回落的是<b>域链</b>，不是任何内置默认值 —— 根域也未绑定时返回空，
     * 调用方据此判断"无可调用"。于是根域绑一次，整棵树都能用，不必逐域配一遍。</p>
     */
    public ModelProfileSettings.Binding resolve(String domain) {
        return settings.resolveAlong(domain);
    }

    /**
     * 取档案。全部对话 / 向量模型都存于本注册表，没有"内置 default"那一类了。
     */
    public Optional<ModelProfile> profile(String alias) {
        if (!hasText(alias)) {
            return Optional.empty();
        }
        ModelProfileSettings.ProfileData data = settings.getProfiles().get(alias.trim());
        return data == null ? Optional.empty() : Optional.of(data.toProfile(alias.trim()));
    }

    /** 全部档案（按别名排序） */
    public List<ModelProfile> profiles() {
        List<ModelProfile> list = new ArrayList<>();
        settings.getProfiles().forEach((alias, data) -> list.add(data.toProfile(alias)));
        list.sort(java.util.Comparator.comparing(ModelProfile::alias));
        return List.copyOf(list);
    }

    /** 是否存在任意可用的对话类模型（供「对话模型是否就绪」这类全局判据使用） */
    public boolean hasChatModel() {
        return profiles().stream().anyMatch(p -> p.isChat() && p.isUsable());
    }

    /** 域 → 别名列表 的绑定视图（不可变副本，保序） */
    public Map<String, List<String>> domainBindings() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        settings.getDomainBindings().forEach((domain, aliases) -> copy.put(domain, List.copyOf(aliases)));
        return Collections.unmodifiableMap(copy);
    }

    // ==================== 写操作 ====================

    /**
     * 新建或整体覆盖一个档案（同名即覆盖，语义同"后设定的顶替之前的"）。
     *
     * @return 失败原因；{@code null} 表示成功
     */
    public synchronized String save(ModelProfile profile) {
        if (profile == null || !hasText(profile.alias())) {
            return "别名不能为空";
        }
        String alias = profile.alias().trim();
        if (!profile.isUsable()) {
            return "服务商地址、API Key、模型名都不能为空";
        }

        ModelProfileSettings next = copyOf(settings);
        next.getProfiles().put(alias, ModelProfileSettings.ProfileData.from(profile));
        persist(next);
        log.info("[模型档案] 已保存档案 {}（model={}，baseUrl={}）—— 绑定它的域立即生效",
                alias, profile.modelName(), profile.baseUrl());
        return null;
    }

    /**
     * 删除档案，并<b>级联清理所有域对该别名的绑定</b>。
     *
     * <p>不再"仍被绑定就拒绝"：级联摘掉后，那些域立即进入"无可调用"状态，由管控台（域空间页）明确提示，
     * 而不是把删除拦在半路。返回结果里带出被清掉了绑定的域，便于前端提示。</p>
     *
     * @return 删除结果（含被级联清理的域）
     */
    public synchronized DeleteResult delete(String alias) {
        if (!hasText(alias)) {
            return new DeleteResult(false, "别名不能为空", List.of());
        }
        String key = alias.trim();
        if (!settings.getProfiles().containsKey(key)) {
            return new DeleteResult(false, "档案不存在：" + key, List.of());
        }

        /* 级联：先把该别名从所有域的绑定里摘掉，再删档案。
           copyOf 已深拷贝，摘掉的是副本，写失败也不会脏内存。 */
        ModelProfileSettings next = copyOf(settings);
        Set<String> affected = new LinkedHashSet<>();
        next.getDomainBindings().forEach((domain, aliases) -> {
            if (aliases != null && aliases.remove(key)) {
                affected.add(domain);
            }
        });
        /* 摘空了的域绑定直接移除，保持落盘干净 */
        next.getDomainBindings().entrySet().removeIf(e -> e.getValue() == null || e.getValue().isEmpty());
        next.getProfiles().remove(key);
        persist(next);
        log.info("[模型档案] 已删除档案 {}；级联清理了 {} 个域的绑定：{}",
                key, affected.size(), affected);
        return new DeleteResult(true, null, List.copyOf(affected));
    }

    /**
     * 设置域的可调用模型列表。<b>整体覆盖</b>，列表顺序即优先级。
     *
     * @param aliases 空/空白表示<b>解绑</b>（解绑后该域即"无可调用模型"）
     * @return 失败原因；{@code null} 表示成功
     */
    public synchronized String bind(String domain, List<String> aliases) {
        if (!hasText(domain)) {
            return "域标识不能为空";
        }
        // 域标识必须是从根域出发的完整路径：绑到一个树里不存在的域，要等到调用时才炸 10004，
        // 不如在写入时就拒绝 —— 绑定是唯一写入口，校验放这里最集中
        String pathReason = Domains.validatePath(domain);
        if (pathReason != null) {
            return pathReason;
        }
        String key = Domains.normalize(domain);
        ModelProfileSettings next = copyOf(settings);

        List<String> cleaned = cleanAliases(aliases);
        if (cleaned.isEmpty()) {
            List<String> previous = next.getDomainBindings().remove(key);
            persist(next);
            log.info("[模型档案] 已解绑域 {}（原可调用列表 {}），该域进入无可调用状态",
                    key, previous);
            return null;
        }

        for (String target : cleaned) {
            /* default 已不再作为可绑定的模型别名；绑定只能指向自建档案 */
            if (Domains.DEFAULT.equals(target)) {
                return "default 已不再作为可绑定的模型别名，请选择自建模型档案";
            }
            if (!settings.getProfiles().containsKey(target)) {
                return "档案不存在：" + target + "（请先到「模型设置」创建该档案）";
            }
        }
        List<String> previous = next.getDomainBindings().put(key, cleaned);
        persist(next);
        if (previous == null) {
            log.info("[模型档案] 域 {} 的可调用列表设为 {}", key, cleaned);
        } else {
            log.info("[模型档案] 域 {} 的可调用列表由 {} 改为 {}（整体覆盖）", key, previous, cleaned);
        }
        return null;
    }

    /**
     * 删域时清理：摘掉这些域的模型绑定。
     *
     * <p>不清理的话，绑定会变成孤儿 —— 同路径域将来重建时会沿链<b>静默继承</b>旧绑定。</p>
     */
    public synchronized void unbindDomains(Collection<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        Set<String> toRemove = new LinkedHashSet<>(domains);
        ModelProfileSettings next = copyOf(settings);
        boolean changed = next.getDomainBindings().keySet().removeIf(toRemove::contains);
        if (changed) {
            persist(next);
            log.info("[模型档案] 已清理 {} 个被删域的模型绑定", toRemove.size());
        }
    }

    /** 去空白、去重、保序 */
    private static List<String> cleanAliases(List<String> aliases) {
        List<String> out = new ArrayList<>();
        if (aliases == null) {
            return out;
        }
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            String value = alias.trim();
            if (!out.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }

    /** 删除档案时的结果 */
    public record DeleteResult(boolean deleted, String reason, List<String> usedByDomains) {
    }

    // ==================== 内部 ====================

    /** 可调用列表里含该别名的域 */
    public Set<String> domainsUsing(String alias) {
        Set<String> result = new LinkedHashSet<>();
        settings.getDomainBindings().forEach((domain, aliases) -> {
            if (alias != null && aliases != null && aliases.contains(alias)) {
                result.add(domain);
            }
        });
        return result;
    }

    /**
     * 深拷贝当前配置后修改 —— 避免写失败时内存态已被改脏。
     */
    private static ModelProfileSettings copyOf(ModelProfileSettings source) {
        ModelProfileSettings copy = new ModelProfileSettings();
        Map<String, List<String>> bindings = new LinkedHashMap<>();
        source.getDomainBindings().forEach((domain, aliases) ->
                bindings.put(domain, new ArrayList<>(aliases)));
        copy.setDomainBindings(bindings);
        Map<String, ModelProfileSettings.ProfileData> profiles = new LinkedHashMap<>();
        source.getProfiles().forEach((alias, data) -> profiles.put(alias,
                ModelProfileSettings.ProfileData.from(data.toProfile(alias))));
        copy.setProfiles(profiles);
        return copy;
    }

    /** 落盘成功后整体替换内存态（写失败时抛异常，内存态保持原样） */
    private void persist(ModelProfileSettings next) {
        store.save(next);
        this.settings = next;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
