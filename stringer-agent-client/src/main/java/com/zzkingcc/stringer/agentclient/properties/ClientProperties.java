package com.zzkingcc.stringer.agentclient.properties;

import com.zzkingcc.stringer.sdkcore.config.ServerProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 客户端调用行为配置（{@code stringer.client.*}）
 *
 * <p>只管"怎么调"，不管"调哪里"——服务端地址与账号在 {@link ServerProperties}。</p>
 *
 * @author zzkingcc
 */
@Data
@ConfigurationProperties(prefix = "stringer.client")
public class ClientProperties {

    /**
     * 启动期连通性探测的超时时间（默认 5s）。
     */
    private Duration healthCheckTimeout = Duration.ofSeconds(5);

    /** 连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 响应读取超时（SSE 为长连接，可适当放宽；0 表示不超时） */
    private Duration readTimeout = Duration.ofMinutes(10);
}
