package com.mall.service;

import com.mall.common.BusinessException;
import com.mall.entity.Order;
import com.mall.enums.OrderEvent;
import com.mall.enums.OrderStatusEnum;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 订单状态机：集中定义"状态 × 事件 → 目标状态"的转移规则。
 *
 * <p>为什么需要它（阶段 5 核心）：
 * <ul>
 *   <li><b>规则单一来源</b>：取消/发货/支付/确认收货/超时/关闭的合法流转集中在一张表里，
 *       业务代码不再各自写 if 判断，避免规则散落、互相矛盾；</li>
 *   <li><b>非法流转快速失败</b>：在动手改库之前先校验，把"非法操作"挡在业务入口；
 *       （SQL 层还有"条件 UPDATE + 状态前置"兜底，防止并发下状态已变）</li>
 *   <li><b>新增流转零风险</b>：后续加状态/事件只需改这一张表。</li>
 * </ul>
 *
 * <p>注意：本组件只负责"算目标状态 + 校验合法性"，不碰数据库；
 * 持久化（条件 UPDATE）、状态日志、副作用（恢复库存）由 {@link OrderService} 编排。
 */
@Component
public class OrderStateMachine {

    /**
     * 转移表：事件 → (当前状态 → 目标状态)。
     *
     * <p>状态机全景（与阶段 3 起的数据约定一致）：
     * <pre>
     *   0 待付款 --PAY-------> 1 待发货
     *   1 待发货 --SHIP------> 2 待收货
     *   2 待收货 --CONFIRM---> 3 已完成
     *   0 待付款 --CANCEL----> 4 已取消
     *   1 待发货 --CANCEL----> 4 已取消
     *   0 待付款 --TIMEOUT---> 4 已取消   （定时任务）
     *   0 待付款 --CLOSE-----> 4 已取消   （管理端关闭）
     * </pre>
     */
    private static final Map<OrderEvent, Map<Integer, Integer>> TRANSITIONS = Map.of(
            OrderEvent.PAY, Map.of(OrderStatusEnum.UNPAID.getCode(), OrderStatusEnum.PAID.getCode()),
            OrderEvent.SHIP, Map.of(OrderStatusEnum.PAID.getCode(), OrderStatusEnum.SHIPPED.getCode()),
            OrderEvent.CONFIRM, Map.of(OrderStatusEnum.SHIPPED.getCode(), OrderStatusEnum.COMPLETED.getCode()),
            OrderEvent.CANCEL, Map.of(
                    OrderStatusEnum.UNPAID.getCode(), OrderStatusEnum.CANCELLED.getCode(),
                    OrderStatusEnum.PAID.getCode(), OrderStatusEnum.CANCELLED.getCode()),
            OrderEvent.TIMEOUT, Map.of(OrderStatusEnum.UNPAID.getCode(), OrderStatusEnum.CANCELLED.getCode()),
            OrderEvent.CLOSE, Map.of(OrderStatusEnum.UNPAID.getCode(), OrderStatusEnum.CANCELLED.getCode())
    );

    /**
     * 执行状态转移校验，返回目标状态。
     *
     * @param order 待流转的订单（只需 status 字段；orderId 由调用方用于持久化）
     * @param event 触发事件
     * @return 目标状态码
     * @throws BusinessException 当前状态 + 事件组合不合法时抛出（400）
     */
    public int transition(Order order, OrderEvent event) {
        int from = order.getStatus() == null ? -1 : order.getStatus();
        Map<Integer, Integer> rules = TRANSITIONS.get(event);
        Integer to = rules == null ? null : rules.get(from);
        if (to == null) {
            throw new BusinessException(BusinessException.PARAM_ERROR,
                    "订单当前状态（" + OrderStatusEnum.textOf(from) + "）不可执行「" + event.name() + "」操作");
        }
        return to;
    }

    /**
     * 判断某个状态 + 事件是否合法（不做抛异常的只读判断，定时任务等场景用）。
     *
     * @param status 当前状态码
     * @param event  触发事件
     * @return true=合法流转，false=非法
     */
    public boolean canTransition(int status, OrderEvent event) {
        Map<Integer, Integer> rules = TRANSITIONS.get(event);
        return rules != null && rules.containsKey(status);
    }
}
