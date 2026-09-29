package com.zzkingcc.stringer.example.tools;

import com.zzkingcc.stringer.api.annotation.Tool;
import com.zzkingcc.stringer.api.annotation.ToolAdvanced;
import com.zzkingcc.stringer.api.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 示例工具提供方 —— 让本示例进程同时扮演"工具实例"
 *
 * @author zzkingcc
 */
@Component
public class ExampleAnnotatedTools {

    private static final Logger log = LoggerFactory.getLogger(ExampleAnnotatedTools.class);

    /** 演示用订单表：订单号 → 状态（关单会改它，用来演示"工具有副作用"） */
    private static final Map<String, String> ORDERS = new ConcurrentHashMap<>(Map.of(
            "FR2024001", "已发货（预计明天送达）",
            "FR2024002", "待发货（已付款，仓库处理中）"));

    /** 演示用物流表：订单号 → 物流状态 */
    private static final Map<String, String> LOGISTICS = new ConcurrentHashMap<>(Map.of(
            "FR2024001", "已到达【杭州中转站】，预计明天送达",
            "FR2024002", "仓库拣货中，预计 24 小时内发出"));

    // ==================== ① 全域可用：显式声明通配 * ====================
    // 注意：留空 domains 不等于全域可见 —— 那表示"只属于兜底域 default"，两个面板都看不到它。

    @Tool(desc = "查询某个城市当前天气。用户询问天气、气温、是否下雨时调用",
            value = "queryWeather", domains = {"*"})
    public String queryWeather(@ToolParam("城市名，如 杭州") String city) {
        log.info("[示例工具] queryWeather city={}", city);
        return city + "：多云转晴，26℃，东南风 2 级（示例数据）";
    }

    // ==================== ② 客服域专属 ====================

    @Tool(desc = "按订单号查询订单状态。用户追问自己订单的发货 / 物流情况时调用",
            value = "queryOrder", domains = {"customer"})
    public String queryOrder(@ToolParam("订单号，如 FR2024001") String orderNo) {
        log.info("[示例工具] queryOrder orderNo={}", orderNo);
        String status = ORDERS.get(orderNo);
        if (status == null) {
            return "没有找到订单 " + orderNo + "。示例数据里只有 " + ORDERS.keySet();
        }
        return "订单 " + orderNo + " 当前状态：" + status;
    }

    @Tool(desc = "按订单号查询物流轨迹。用户追问包裹到哪了、什么时候送到时调用；"
            + "问订单状态时优先用 queryOrder",
            value = "queryLogistics", domains = {"customer"})
    public String queryLogistics(@ToolParam("订单号，如 FR2024001") String orderNo) {
        log.info("[示例工具] queryLogistics orderNo={}", orderNo);
        return LOGISTICS.getOrDefault(orderNo, "没有查到订单 " + orderNo + " 的物流记录");
    }

    // ==================== ③ 管理域专属 ====================

    @Tool(desc = "查询今日经营指标（订单量 / 成交额 / 退款 / 异常单数）。"
            + "仅管理视角可用；用户问今天经营情况、卖了多少时调用",
            value = "businessReport", domains = {"admin"})
    public String businessReport() {
        log.info("[示例工具] businessReport");
        return "今日订单 128 单，成交额 ¥18,640，退款 3 单，异常 2 单（示例数据）";
    }

    // ==================== ④ 管理域专属 + 有副作用 ⇒ 需人工二次确认 + 参数脱敏 ====================

    @Tool(desc = "关闭一笔订单。仅在用户明确要求取消 / 关闭订单时调用，必须给出原因",
            value = "closeOrder", domains = {"admin"},
            effect = Tool.Effect.WRITE, approval = Tool.Approval.ALWAYS,
            approvalReason = "关单不可逆，需人工确认")
    public String closeOrder(@ToolParam("要关闭的订单号") String orderNo,
                             @ToolParam("关闭原因，如 用户申请退款") String reason) {
        log.info("[示例工具] closeOrder orderNo={} reason={}", orderNo, reason);

        String current = ORDERS.get(orderNo);
        if (current == null) {
            return "没有找到订单 " + orderNo + "，无法关闭";
        }
        // 有副作用的工具应当自己防重：模型可能因上下文重复调用同一个工具
        if (current.startsWith("已关闭")) {
            return "订单 " + orderNo + " 已经是关闭状态，无需重复关单";
        }
        ORDERS.put(orderNo, "已关闭（原因：" + reason + "）");
        return "订单 " + orderNo + " 已关闭，原因：" + reason + "。如需确认可再次查询该订单状态。";
    }

    // ==================== ⑤ 高级可选：示例值 + 参数脱敏（@ToolAdvanced） ====================

    @Tool(desc = "修改订单的收货联系电话。仅在用户明确要求更换号码时调用，改前需与用户核对号码",
            value = "updateDeliveryPhone", domains = {"admin"},
            effect = Tool.Effect.WRITE, approval = Tool.Approval.ALWAYS,
            approvalReason = "修改收货联系方式需人工核对")
    @ToolAdvanced(example = {"phone=13800000000"}, sensitive = {"phone"})
    public String updateDeliveryPhone(@ToolParam("订单号") String orderNo,
                                      @ToolParam("新的联系电话，11 位手机号") String phone) {
        log.info("[示例工具] updateDeliveryPhone orderNo={} phone={}", orderNo, phone);
        if (!ORDERS.containsKey(orderNo)) {
            return "没有找到订单 " + orderNo + "，未做任何修改";
        }
        return "订单 " + orderNo + " 的收货电话已更新为 " + phone;
    }
}
