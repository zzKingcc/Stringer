package com.zzkingcc.stringer.server.model;

import org.junit.jupiter.api.Test;

/**
 * 探测的入参与失败路径 —— 真正的端点探测要连外部服务商，这里只测不联网的部分。
 *
 * @author zzkingcc
 */
class ModelProbeTest {

    private final ModelProbe probe = new ModelProbe();

    @Test
    void 三个入参任何一个缺失都直接报错() {
        assertBlank("地址空", "", "sk-1", "m");
        assertBlank("Key 空", "https://api.example.com/v1", " ", "m");
        assertBlank("模型名空", "https://api.example.com/v1", "sk-1", null);
    }

    @Test
    void 连不上服务商时抛异常且说清原因() {
        // 用必然拒绝连接的本地端口，避免用例真的去连外网（慢且不稳）
        try {
            probe.probe("http://127.0.0.1:1/v1", "sk-1", "m");
            assert false : "连不上服务商时应抛异常，不该返回半成品结果";
        } catch (IllegalStateException expected) {
            assert expected.getMessage() != null && !expected.getMessage().isBlank()
                    : "失败原因要说清，实际：" + expected.getMessage();
        }
    }

    private void assertBlank(String what, String baseUrl, String apiKey, String model) {
        try {
            probe.probe(baseUrl, apiKey, model);
            assert false : what + "时应当抛异常";
        } catch (IllegalStateException expected) {
            assert expected.getMessage().contains("不能为空")
                    : what + "时的提示应点名缺什么，实际：" + expected.getMessage();
        }
    }
}
