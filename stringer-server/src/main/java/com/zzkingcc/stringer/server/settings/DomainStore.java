package com.zzkingcc.stringer.server.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zzkingcc.stringer.common.util.AtomicFiles;
import com.zzkingcc.stringer.server.env.StorageLocations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 域声明与可调用性的持久化存储（{@code config/domains.json}）。
 *
 * <p>只存<b>管控台声明</b>的域：内置根域由代码预置，派生域由工具声明决定，两者都不落盘。</p>
 *
 * <p>落盘结构与可调用性一一对应：域是不是"可调用单元"是显式声明，不靠"有没有子域"这种
 * 派生事实推断 —— 后者会在新增子域时静默改变调用方的可用性。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class DomainStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path settingsFile;

    public DomainStore(StorageLocations storage) {
        this.settingsFile = storage.settingsDir().resolve("domains.json");
    }

    /**
     * 读取落盘的域声明。
     *
     * <p>兼容上一版的纯标识列表（{@code manualDomains}）：那些条目一律按<b>可调用</b>处理，
     * 随后由启动期的一次性迁移把它们之中"已经有子域的"改成装配节点。</p>
     *
     * <p>文件不存在或解析失败时返回空配置（不阻断启动）。</p>
     */
    public StoredDomains load() {
        if (!Files.exists(settingsFile)) {
            log.debug("[域] 未找到 {}，按空配置处理", settingsFile.toAbsolutePath());
            return StoredDomains.empty();
        }
        try {
            String json = Files.readString(settingsFile, StandardCharsets.UTF_8);
            DomainSettings settings = MAPPER.readValue(json, DomainSettings.class);
            if (settings == null) {
                return StoredDomains.empty();
            }
            Map<String, Boolean> callables = new LinkedHashMap<>();
            // 旧格式优先落进来：没有显式条目的历史域按"可调用"处理，保持升级前后行为一致
            if (settings.getManualDomains() != null) {
                for (String id : settings.getManualDomains()) {
                    if (id != null && !id.isBlank()) {
                        callables.put(id.trim(), Boolean.TRUE);
                    }
                }
            }
            if (settings.getDomains() != null) {
                for (DomainEntry entry : settings.getDomains()) {
                    if (entry == null || entry.getId() == null || entry.getId().isBlank()) {
                        continue;
                    }
                    callables.put(entry.getId().trim(), entry.isCallable());
                }
            }
            return new StoredDomains(callables, settings.isCallableMigrated());
        } catch (IOException e) {
            log.error("[域] 读取 {} 失败，按空配置继续（可进管控台重建）: {}",
                    settingsFile.toAbsolutePath(), e.getMessage());
            return StoredDomains.empty();
        }
    }

    /**
     * 覆盖写域声明，并<b>保留已有的一次性迁移标记</b>（管理端改域时用）。
     */
    public void saveDeclarations(Map<String, Boolean> callables) {
        save(callables, load().callableMigrated());
    }

    /**
     * 覆盖写域声明。
     *
     * @param callables        域标识 → 是否可调用（顺序即落盘顺序）
     * @param callableMigrated 是否已完成"非叶子→装配节点"的一次性迁移
     * @throws IllegalStateException 写盘失败 —— 必须让调用方感知，静默失败会让用户以为已生效
     */
    public void save(Map<String, Boolean> callables, boolean callableMigrated) {
        DomainSettings settings = new DomainSettings();
        settings.setCallableMigrated(callableMigrated);
        List<DomainEntry> entries = new ArrayList<>();
        if (callables != null) {
            callables.forEach((id, callable) -> {
                DomainEntry entry = new DomainEntry();
                entry.setId(id);
                entry.setCallable(callable == null || callable);
                entries.add(entry);
            });
        }
        settings.setDomains(entries);
        try {
            AtomicFiles.write(settingsFile, MAPPER.writeValueAsBytes(settings));
            log.info("[域] 已保存 {} 个域声明（可调用 {} 个）到 {}",
                    entries.size(), entries.stream().filter(DomainEntry::isCallable).count(),
                    settingsFile.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("域配置保存失败：" + settingsFile.toAbsolutePath(), e);
        }
    }

    /** 落盘路径（供管控台展示） */
    public String filePath() {
        return settingsFile.toAbsolutePath().toString();
    }

    /**
     * 读到的域声明。
     *
     * @param callables        域标识 → 是否可调用
     * @param callableMigrated 是否已完成一次性迁移
     */
    public record StoredDomains(Map<String, Boolean> callables, boolean callableMigrated) {

        static StoredDomains empty() {
            return new StoredDomains(Map.of(), false);
        }
    }

    /** 落盘结构 */
    public static class DomainSettings {

        /** 域声明（当前格式） */
        private List<DomainEntry> domains = new ArrayList<>();

        /** 上一版的纯标识列表，只读兼容 */
        private List<String> manualDomains;

        /** 一次性迁移标记：为 false 时启动期会执行"有子域的域 → 装配节点" */
        private boolean callableMigrated;

        public List<DomainEntry> getDomains() {
            return domains;
        }

        public void setDomains(List<DomainEntry> domains) {
            this.domains = domains == null ? new ArrayList<>() : domains;
        }

        public List<String> getManualDomains() {
            return manualDomains;
        }

        public void setManualDomains(List<String> manualDomains) {
            this.manualDomains = manualDomains;
        }

        public boolean isCallableMigrated() {
            return callableMigrated;
        }

        public void setCallableMigrated(boolean callableMigrated) {
            this.callableMigrated = callableMigrated;
        }
    }

    /** 单个域的声明 */
    public static class DomainEntry {

        private String id;

        /** 是否可调用单元（默认 true：显式创建一个域，本意就是拿它当入口） */
        private boolean callable = true;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public boolean isCallable() {
            return callable;
        }

        public void setCallable(boolean callable) {
            this.callable = callable;
        }
    }
}
