package com.zzkingcc.stringer.server.model;

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

/**
 * 模型档案的持久化存储（{@code config/models.json}）。
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class ModelProfileStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path settingsFile;

    public ModelProfileStore(StorageLocations storage) {
        this.settingsFile = storage.settingsDir().resolve("models.json");
    }

    /**
     * 读取档案配置；文件不存在或解析失败时返回空配置（不阻断启动）。
     */
    public ModelProfileSettings load() {
        if (!Files.exists(settingsFile)) {
            log.debug("[模型档案] 未找到 {}，按「未配置任何档案」处理（所有域走内置 default）",
                    settingsFile.toAbsolutePath());
            return new ModelProfileSettings();
        }
        try {
            String json = Files.readString(settingsFile, StandardCharsets.UTF_8);
            ModelProfileSettings settings = MAPPER.readValue(json, ModelProfileSettings.class);
            return settings == null ? new ModelProfileSettings() : settings;
        } catch (IOException e) {
            log.error("[模型档案] 读取 {} 失败，按空档案继续（可进管控台修正）: {}",
                    settingsFile.toAbsolutePath(), e.getMessage());
            return new ModelProfileSettings();
        }
    }

    /**
     * 保存（覆盖写）。
     *
     * @throws IllegalStateException 写盘失败 —— 必须让调用方感知，静默失败会让用户以为已生效
     */
    public void save(ModelProfileSettings settings) {
        try {
            AtomicFiles.write(settingsFile, MAPPER.writeValueAsBytes(settings));
            log.info("[模型档案] 已保存：档案 {} 个、域绑定 {} 条、默认别名 {}",
                    settings.getChatProfiles().size(),
                    settings.getDomainBindings().size(),
                    settings.getDefaultAlias());
        } catch (IOException e) {
            throw new IllegalStateException("模型档案保存失败：" + settingsFile.toAbsolutePath(), e);
        }
    }

    /** 落盘路径（供管控台展示） */
    public String filePath() {
        return settingsFile.toAbsolutePath().toString();
    }
}
