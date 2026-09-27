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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 人工创建的域的持久化存储（{@code config/domains.json}）。
 *
 * <p>只存<b>人工创建</b>的域标识：内置域由代码预置，派生域由工具声明决定，
 * 两者都不需要落盘。</p>
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
     * 读取人工创建的域；文件不存在或解析失败时返回空集合（不阻断启动）。
     */
    public Set<String> loadManualDomains() {
        if (!Files.exists(settingsFile)) {
            log.debug("[域] 未找到 {}，按空集合处理", settingsFile.toAbsolutePath());
            return Set.of();
        }
        try {
            String json = Files.readString(settingsFile, StandardCharsets.UTF_8);
            DomainSettings settings = MAPPER.readValue(json, DomainSettings.class);
            if (settings == null || settings.getManualDomains() == null) {
                return Set.of();
            }
            return new LinkedHashSet<>(settings.getManualDomains());
        } catch (IOException e) {
            log.error("[域] 读取 {} 失败，按空集合继续（可进管控台重建）: {}",
                    settingsFile.toAbsolutePath(), e.getMessage());
            return Set.of();
        }
    }

    /**
     * 保存人工创建的域（覆盖写）。
     *
     * @throws IllegalStateException 写盘失败 —— 必须让调用方感知，静默失败会让用户以为已生效
     */
    public void saveManualDomains(Set<String> ids) {
        DomainSettings settings = new DomainSettings();
        settings.setManualDomains(ids == null ? new ArrayList<>() : new ArrayList<>(ids));
        try {
            AtomicFiles.write(settingsFile, MAPPER.writeValueAsBytes(settings));
            log.info("[域] 已保存 {} 个人工创建的域到 {}",
                    settings.getManualDomains().size(), settingsFile.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("域配置保存失败：" + settingsFile.toAbsolutePath(), e);
        }
    }

    /** 落盘路径（供管控台展示） */
    public String filePath() {
        return settingsFile.toAbsolutePath().toString();
    }

    /** 落盘结构 */
    public static class DomainSettings {

        private List<String> manualDomains = new ArrayList<>();

        public List<String> getManualDomains() {
            return manualDomains;
        }

        public void setManualDomains(List<String> manualDomains) {
            this.manualDomains = manualDomains;
        }
    }
}
