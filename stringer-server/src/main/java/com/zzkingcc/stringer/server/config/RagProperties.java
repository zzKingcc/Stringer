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
     *
     * <p>本轮只支持纯文本 {@code txt} —— md / pdf / html 的解析各自需要一套处理，
     * 与其半吊子支持，不如先只做能做好的一种。</p>
     */
    private List<String> allowedExtensions = List.of("txt");

    /**
     * 切片参数（只这三项可调，其余阈值都是代码常量）。
     */
    private Chunking chunking = new Chunking();

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

    /**
     * 切片参数。
     *
     * <p>{@code maxChars} 是长度上限而不是固定长度：切点永远落在标题或句子上 ——
     * 撞到标题立刻断、整段不足上限就是实际字数。想强行凑满只能跨标题拼内容，反而让模型看不出层级。</p>
     */
    @Data
    public static class Chunking {

        /** 单切片长度上限（字）。400 个中文字约合 400 token，对上限 512 token 的向量模型也安全 */
        private int maxChars = 400;

        /** 相邻切片重叠几句 */
        private int overlapSentences = 1;

        /** 尾片短于该值就并进前一片 */
        private int minChars = 60;
    }
}
