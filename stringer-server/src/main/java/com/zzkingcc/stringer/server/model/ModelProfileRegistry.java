package com.zzkingcc.stringer.server.model;

import com.zzkingcc.stringer.api.agent.Domains;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 模型档案注册表 —— 档案与「域 → 档案」绑定的唯一真相。
 *
 * <p>三条规则（与产品口径一致）：</p>
 * <ol>
 *   <li><b>档案不自动绑定任何域</b>：绑定只能由管控台显式写入；新档案建完就是"未绑定"状态；</li>
 *   <li><b>一个域只绑一个模型</b>：绑定是单值，再次设置即<b>覆盖</b>前一次；</li>
 *   <li><b>未绑定的域走 {@link #BUILTIN_DEFAULT}</b>，即管控台「模型设置」页那一套 —— 于是"没配任何绑定"
 *       与升级前的行为完全一致。</li>
 * </ol>
 *
 * <p>内存态 + 落盘：每次写操作先改内存、再整体落盘（写盘失败会抛异常，调用方必须感知）。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class ModelProfileRegistry {

    /** 内置别名：指向管控台「模型设置」页那一套，由 {@code LlmModelHolder} 承载 */
    public static final String BUILTIN_DEFAULT = Domains.DEFAULT;

    private final ModelProfileStore store;

    /** 当前配置（写操作整体替换，读操作拿不可变副本） */
    private volatile ModelProfileSettings settings;

    public ModelProfileRegistry(ModelProfileStore store) {
        this.store = store;
        this.settings = store.load();
        log.info("[模型档案] 初始化：档案 {} 个 {}，域绑定 {} 条，默认别名 {}；未绑定的域走内置 {}",
                settings.getChatProfiles().size(), settings.getChatProfiles().keySet(),
                settings.getDomainBindings().size(), settings.getDefaultAlias(), BUILTIN_DEFAULT);
    }

    // ==================== 解析 ====================

    /**
     * 解析该域应使用的档案别名。
     *
     * <p>顺序：域绑定 → {@code defaultAlias} → 内置 {@code default}。命中即止。</p>
     */
    public String resolveAlias(String domain) {
        String key = Domains.normalize(domain);
        String bound = settings.getDomainBindings().get(key);
        if (hasText(bound)) {
            return bound.trim();
        }
        String fallback = settings.getDefaultAlias();
        return hasText(fallback) ? fallback.trim() : BUILTIN_DEFAULT;
    }

    /** 该别名是否指向内置 default（＝走 LlmModelHolder，享受模型设置页的热替换） */
    public boolean isBuiltinDefault(String alias) {
        return BUILTIN_DEFAULT.equals(alias);
    }

    /**
     * 取档案。内置 {@code default} 不在这里 —— 它由 {@code LlmModelHolder} 承载，不在本注册表内。
     */
    public Optional<ModelProfile> profile(String alias) {
        if (!hasText(alias)) {
            return Optional.empty();
        }
        ModelProfileSettings.ProfileData data = settings.getChatProfiles().get(alias.trim());
        return data == null ? Optional.empty() : Optional.of(data.toProfile(alias.trim()));
    }

    /** 全部档案（按别名排序） */
    public List<ModelProfile> profiles() {
        List<ModelProfile> list = new ArrayList<>();
        settings.getChatProfiles().forEach((alias, data) -> list.add(data.toProfile(alias)));
        list.sort(java.util.Comparator.comparing(ModelProfile::alias));
        return List.copyOf(list);
    }

    /** 域 → 别名 的绑定视图（不可变副本） */
    public Map<String, String> domainBindings() {
        return Map.copyOf(new LinkedHashMap<>(settings.getDomainBindings()));
    }

    /** 当前配置快照（供管控台展示） */
    public ModelProfileSettings snapshot() {
        return settings;
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
        if (BUILTIN_DEFAULT.equals(alias)) {
            return "别名 " + BUILTIN_DEFAULT + " 为内置保留（对应「模型设置」页那套配置），请换一个别名";
        }
        if (!profile.isUsable()) {
            return "服务商地址、API Key、模型名都不能为空";
        }

        ModelProfileSettings next = copyOf(settings);
        next.getChatProfiles().put(alias, ModelProfileSettings.ProfileData.from(profile));
        persist(next);
        log.info("[模型档案] 已保存档案 {}（model={}，baseUrl={}）—— 绑定它的域立即生效",
                alias, profile.modelName(), profile.baseUrl());
        return null;
    }

    /**
     * 删除档案。<b>仍被域绑定时拒绝</b> —— 否则那些域下次调用会直接失败。
     *
     * @return 删除结果（含拒绝原因与被哪些域引用）
     */
    public synchronized DeleteResult delete(String alias) {
        if (!hasText(alias)) {
            return new DeleteResult(false, "别名不能为空", List.of());
        }
        String key = alias.trim();
        if (BUILTIN_DEFAULT.equals(key)) {
            return new DeleteResult(false, "内置 " + BUILTIN_DEFAULT + " 不可删除（它是未绑定域的落点）", List.of());
        }
        if (!settings.getChatProfiles().containsKey(key)) {
            return new DeleteResult(false, "档案不存在：" + key, List.of());
        }
        Set<String> usedBy = domainsUsing(key);
        if (!usedBy.isEmpty()) {
            return new DeleteResult(false,
                    "档案 " + key + " 仍被以下域绑定，请先解绑或改绑：" + usedBy, List.copyOf(usedBy));
        }

        ModelProfileSettings next = copyOf(settings);
        next.getChatProfiles().remove(key);
        persist(next);
        log.info("[模型档案] 已删除档案 {}", key);
        return new DeleteResult(true, null, List.of());
    }

    /**
     * 绑定域到档案。<b>单值覆盖</b>：该域原先绑的别名会被顶替。
     *
     * @param alias 空白表示<b>解绑</b>（解绑后该域走默认别名）
     * @return 失败原因；{@code null} 表示成功
     */
    public synchronized String bind(String domain, String alias) {
        if (!hasText(domain)) {
            return "域标识不能为空";
        }
        String key = Domains.normalize(domain);
        ModelProfileSettings next = copyOf(settings);

        if (!hasText(alias)) {
            String previous = next.getDomainBindings().remove(key);
            persist(next);
            log.info("[模型档案] 已解绑域 {}（原绑定 {}），该域改走默认别名 {}",
                    key, previous, settings.getDefaultAlias());
            return null;
        }

        String target = alias.trim();
        if (!isBuiltinDefault(target) && !settings.getChatProfiles().containsKey(target)) {
            return "档案不存在：" + target + "（可绑定内置 " + BUILTIN_DEFAULT + "，或先创建该档案）";
        }
        String previous = next.getDomainBindings().put(key, target);
        persist(next);
        if (previous == null) {
            log.info("[模型档案] 域 {} 绑定档案 {}", key, target);
        } else {
            log.info("[模型档案] 域 {} 的绑定由 {} 改为 {}（单值覆盖）", key, previous, target);
        }
        return null;
    }

    /** 删除档案时的结果 */
    public record DeleteResult(boolean deleted, String reason, List<String> usedByDomains) {
    }

    // ==================== 内部 ====================

    /** 引用了该别名的域 */
    public Set<String> domainsUsing(String alias) {
        Set<String> result = new LinkedHashSet<>();
        settings.getDomainBindings().forEach((domain, bound) -> {
            if (alias != null && alias.equals(bound)) {
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
        copy.setDefaultAlias(source.getDefaultAlias());
        copy.setDomainBindings(new LinkedHashMap<>(source.getDomainBindings()));
        Map<String, ModelProfileSettings.ProfileData> profiles = new LinkedHashMap<>();
        source.getChatProfiles().forEach((alias, data) -> profiles.put(alias,
                ModelProfileSettings.ProfileData.from(data.toProfile(alias))));
        copy.setChatProfiles(profiles);
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
