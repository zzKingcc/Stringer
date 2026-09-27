package com.zzkingcc.stringer.sdkcore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stringer 服务端连接配置（{@code stringer.server.*}）
 *
 * <p>对话 SDK 与工具 SDK 读的是同一份前缀：服务端只有一个账号、工具注册表也只有一份，
 * 因此不存在"对话连一个服务端、工具实例连另一个"的用法 —— 地址与账号只写一份。</p>
 *
 * @author zzkingcc
 */
@ConfigurationProperties(prefix = "stringer.server")
public class ServerProperties {

    /** Stringer 服务端主机（主机名或 IP，不含协议与端口） */
    private String host = "localhost";

    /** Stringer 服务端端口（服务端固定 9527） */
    private int port = 9527;

    /** 接入账号（服务端账号，默认种子是 stringer / stringer） */
    private String username = "stringer";

    /** 接入密码 */
    private String password = "stringer";

    /** 服务端 baseUrl（{@code http://host:port}） */
    public String getServerUrl() {
        return "http://" + host + ":" + port;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
