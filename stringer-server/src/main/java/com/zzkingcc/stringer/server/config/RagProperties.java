package com.zzkingcc.stringer.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.List;

/**
 * 知识库上传配置（平台自有，统一前缀 {@code stringer.rag}）。
 *
 * <p>知识库索引是<b>一域一索引</b>，索引名由域路径确定性派生（见 {@code KbIndexes}），
 * 因此这里不再有「索引名」配置 —— 索引在首次上传时按需创建。</p>
 *
 * @author zzkingcc
 */
@Data
@ConfigurationProperties(prefix = "stringer.rag")
public class RagProperties {

    /**
     * 单个上传文件的大小上限（默认 10MB）。
     */
    private DataSize maxFileSize = DataSize.ofMegabytes(10);

    /**
     * 允许上传的扩展名白名单（小写）。
     */
    private List<String> allowedExtensions = List.of("md", "txt", "markdown", "text");

    /**
     * 排队等待导入串行锁的最长时间（秒，默认 60）
     */
    private long ingestLockWaitSeconds = 60;

    /** 知识库导入线程池线程数 */
    private int ingestPoolSize = 2;

    /**
     * 导入任务队列容量。
     */
    private int ingestQueueCapacity = 16;
}
