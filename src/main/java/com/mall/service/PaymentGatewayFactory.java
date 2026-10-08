package com.mall.service;

import com.mall.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 支付网关工厂：按支付方式返回对应的 {@link PaymentGateway} 实现。
 *
 * <p>Spring 会把所有 PaymentGateway 实现类注入列表，工厂建立
 * "支付方式 → 实现"映射；新增支付方式（如银联）只需加实现类，
 * 工厂与业务层代码零改动。
 */
@Component
public class PaymentGatewayFactory {

    /** 支付方式 → 网关实现（构造时一次性建立映射） */
    private final Map<String, PaymentGateway> gateways;

    public PaymentGatewayFactory(List<PaymentGateway> gatewayList) {
        this.gateways = gatewayList.stream()
                .collect(Collectors.toMap(PaymentGateway::method, Function.identity()));
    }

    /** 按支付方式取实现；不支持的方式直接抛业务异常 */
    public PaymentGateway get(String method) {
        PaymentGateway gateway = gateways.get(method);
        if (gateway == null) {
            throw new BusinessException(BusinessException.PARAM_ERROR, "不支持的支付方式：" + method);
        }
        return gateway;
    }
}
