package com.zzkingcc.stringer.server.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 域声明落盘：可调用性必须能跨重启保持，且旧格式能被读进来（升级不丢域）。
 */
class DomainStoreTest {

    @TempDir
    Path tempDir;

    private DomainStore store() {
        return new DomainStore(storageLocations());
    }

    private com.zzkingcc.stringer.server.env.StorageLocations storageLocations() {
        Environment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("stringer.settings.path", tempDir.resolve("config").toString());
        props.put("stringer.logging.path", tempDir.resolve("log").toString());
        props.put("stringer.export.path", tempDir.resolve("export").toString());
        ((StandardEnvironment) env).getPropertySources().addFirst(new MapPropertySource("test", props));
        return new com.zzkingcc.stringer.server.env.StorageLocations(env);
    }

    private Path settingsFile() {
        return tempDir.resolve("config").resolve("domains.json");
    }

    @Test
    void missingFileIsAnEmptyConfigThatStillNeedsMigration() {
        DomainStore.StoredDomains stored = store().load();

        assertTrue(stored.callables().isEmpty());
        assertFalse(stored.callableMigrated(), "从未落盘过 → 迁移尚未执行");
    }

    @Test
    void declarationsRoundTripWithCallableFlag() {
        DomainStore store = store();
        Map<String, Boolean> callables = new LinkedHashMap<>();
        callables.put("default.sales", true);
        callables.put("default.sales.order", false);

        store.save(callables, true);
        DomainStore.StoredDomains stored = store().load();

        assertTrue(stored.callableMigrated());
        assertEquals(Boolean.TRUE, stored.callables().get("default.sales"));
        assertEquals(Boolean.FALSE, stored.callables().get("default.sales.order"),
                "装配节点必须原样读回来，否则重启后又会变成可调用");
    }

    @Test
    void legacyIdentifierListIsReadAsCallable() throws IOException {
        // 上一版只存一个标识列表；升级后这些域一律按可调用处理，再由一次性迁移收掉父域
        Files.createDirectories(settingsFile().getParent());
        Files.writeString(settingsFile(),
                "{\n  \"manualDomains\" : [ \"default.sales\", \"default.hr\" ]\n}\n",
                StandardCharsets.UTF_8);

        DomainStore.StoredDomains stored = store().load();

        assertEquals(Boolean.TRUE, stored.callables().get("default.sales"));
        assertEquals(Boolean.TRUE, stored.callables().get("default.hr"));
        assertFalse(stored.callableMigrated(), "旧格式没有迁移标记 → 需要跑一次迁移");
    }

    @Test
    void brokenFileFallsBackToEmptyInsteadOfFailingStartup() throws IOException {
        Files.createDirectories(settingsFile().getParent());
        Files.writeString(settingsFile(), "{ this is not json", StandardCharsets.UTF_8);

        DomainStore.StoredDomains stored = store().load();

        assertTrue(stored.callables().isEmpty(), "读不动就按空配置启动，由管控台重建");
    }

    @Test
    void saveDeclarationsKeepsTheMigrationFlag() {
        DomainStore store = store();
        store.save(Map.of("default.sales", true), true);

        store.saveDeclarations(Map.of("default.sales", true, "default.hr", false));

        DomainStore.StoredDomains stored = store().load();
        assertTrue(stored.callableMigrated(), "管理端改域不该把迁移标记清掉，否则每次重启都会重跑迁移");
        assertEquals(Boolean.FALSE, stored.callables().get("default.hr"));
    }
}
