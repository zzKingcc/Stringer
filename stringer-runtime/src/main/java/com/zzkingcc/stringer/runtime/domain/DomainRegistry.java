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

/**
 * 域注册表 —— 域作为一等实体的登记处。
 *
 * <p>与"域由工具声明派生"的既有模型<b>并存</b>，两者在
 * {@code ToolRouter#acceptsProfile} 处合并判定：</p>
 * <ul>
 *   <li>{@link Source#BUILTIN}：平台预置的兜底域 {@code default} —— 启动即存在、不可删除；</li>
 *   <li>{@link Source#MANUAL}：人工创建的域 —— 落盘 {@code config/domains.json}，可删除；</li>
 *   <li>工具声明的域（派生域）不在此登记，由 {@code ToolRegistry} 维护。</li>
 * </ul>
 *
 * <p>有了它，"先建域、再启动应用"才成立：一个暂时没有任何工具的域也能被创建并放行，
 * 而不必等某个工具来声明它。</p>
 *
 * @author zzkingcc
 */
public class DomainRegistry {

    private static final Logger log = LoggerFactory.getLogger(DomainRegistry.class);

    /** 域标识长度上限 */
    private static final int MAX_ID_LENGTH = 64;

    /** 域的来源 */
    public enum Source {
        /** 平台预置（当前只有兜底域 default），不可删除 */
        BUILTIN,
        /** 人工创建，落盘后重启仍在，可删除 */
        MANUAL
    }

    /**
     * 域条目
     *
     * @param id        域标识
     * @param source    来源
     * @param createdAt 创建时间戳（毫秒）
     */
    public record Domain(String id, Source source, long createdAt) {
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

    /** 删除结果 */
    public record DeleteResult(boolean deleted, String reason) {
        public static DeleteResult ok() {
            return new DeleteResult(true, null);
        }

        public static DeleteResult fail(String reason) {
            return new DeleteResult(false, reason);
        }
    }

    private final Map<String, Domain> domains = new ConcurrentHashMap<>();

    public DomainRegistry() {
        // 预置兜底域：工具声明留空与调用未指定域都落到它，因此它必须永远在
        domains.put(Domains.DEFAULT,
                new Domain(Domains.DEFAULT, Source.BUILTIN, System.currentTimeMillis()));
    }

    /**
     * 载入人工创建的域（启动期从落盘恢复）。非法标识只跳过并告警，不阻断启动。
     */
    public void loadManual(Collection<String> ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            String reason = validate(id);
            if (reason != null) {
                log.warn("[域注册表] 落盘域 '{}' 非法（{}），已跳过", id, reason);
                continue;
            }
            String trimmed = id.trim();
            domains.putIfAbsent(trimmed, new Domain(trimmed, Source.MANUAL, System.currentTimeMillis()));
        }
    }

    /**
     * 人工创建域。
     *
     * @return 成功；或失败原因（标识非法 / 已存在）
     */
    public CreateResult create(String domainId) {
        String reason = validate(domainId);
        if (reason != null) {
            return CreateResult.fail(reason);
        }
        String id = domainId.trim();
        Domain existing = domains.putIfAbsent(id,
                new Domain(id, Source.MANUAL, System.currentTimeMillis()));
        if (existing != null) {
            return CreateResult.fail("域已存在：" + id + "（来源 " + existing.source() + "）");
        }
        log.info("[域注册表] 已创建域 {}（来源 MANUAL）", id);
        return CreateResult.ok();
    }

    /**
     * 删除域。<b>只有人工创建的域可删</b>：
     * 内置域是兜底，删掉它兜底就无处可落；派生域的生命周期归工具，删掉它下次扫描又会出现。
     */
    public DeleteResult delete(String domainId) {
        if (domainId == null || domainId.isBlank()) {
            return DeleteResult.fail("域标识不能为空");
        }
        String id = domainId.trim();
        Domain existing = domains.get(id);
        if (existing == null) {
            return DeleteResult.fail("域不存在：" + id + "（派生域由工具声明，不在可删除之列）");
        }
        if (existing.source() == Source.BUILTIN) {
            return DeleteResult.fail("内置域不可删除：" + id + "（它是留空工具与未指定域调用的兜底）");
        }
        domains.remove(id);
        log.info("[域注册表] 已删除域 {}", id);
        return DeleteResult.ok();
    }

    /** 该域是否已被登记（内置 / 人工创建）；不含工具派生域 */
    public boolean contains(String domainId) {
        return domains.containsKey(Domains.normalize(domainId));
    }

    /** 已登记的域标识（内置 + 人工创建） */
    public Set<String> ids() {
        return Set.copyOf(domains.keySet());
    }

    /** 全部条目，按标识排序，便于展示 */
    public List<Domain> all() {
        List<Domain> list = new ArrayList<>(domains.values());
        list.sort(Comparator.comparing(Domain::id));
        return List.copyOf(list);
    }

    /** 人工创建的域标识（落盘用） */
    public Set<String> manualIds() {
        return domains.values().stream()
                .filter(d -> d.source() == Source.MANUAL)
                .map(Domain::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 是否内置域 */
    public boolean isBuiltin(String domainId) {
        Domain domain = domains.get(domainId == null ? null : domainId.trim());
        return domain != null && domain.source() == Source.BUILTIN;
    }

    /**
     * 校验域标识。
     *
     * @return {@code null} 表示合法；否则返回不合法原因
     */
    private static String validate(String domainId) {
        if (domainId == null || domainId.isBlank()) {
            return "域标识不能为空";
        }
        String id = domainId.trim();
        if (id.length() > MAX_ID_LENGTH) {
            return "域标识过长（上限 " + MAX_ID_LENGTH + " 字符）";
        }
        if (Domains.ANY.equals(id)) {
            return "域标识不能是通配符 " + Domains.ANY + "（它只用于工具的域声明）";
        }
        for (int i = 0; i < id.length(); i++) {
            if (Character.isWhitespace(id.charAt(i))) {
                return "域标识不能包含空白字符";
            }
        }
        return null;
    }
}
