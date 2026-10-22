package com.mall.aspect;

import com.mall.annotation.DS;
import com.mall.common.DataSourceContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;

/**
 * 数据源路由切面（Phase 3 读写分离）。
 *
 * <p>切住 {@code com.mall.service} 包下所有方法，在方法执行前决定本次调用的数据源：
 * <ul>
 *   <li>方法标注 {@code @DS("master"/"slave")} → 按注解值路由（最高优先）；</li>
 *   <li>未标注 → 方法名以读前缀开头（get/list/find/select/count/query/page/detail…）
 *       且当前不在事务中 → 从库（把读压力分流到 slave）；</li>
 *   <li>其余（写方法、或已在事务中）→ 主库（事务内写后读必须主库，见 DynamicDataSource）。</li>
 * </ul>
 *
 * <p>为什么 {@code @Order(0)}：必须让本切面先于 @Transactional 切面执行——
 * 事务管理器在开启事务时就要 getConnection() 决定数据源，
 * 只有先设置好路由 key，事务才能绑定到正确的数据源。
 *
 * <p>切面只影响"路由 key 的设置与清理"，不拦截具体 SQL；
 * 真正拿连接时 DynamicDataSource 才根据 key 选择连接池。
 */
@Aspect
@Component
@Order(0)
public class DataSourceAspect {

    /** 读方法名前缀：命中且无事务 → 走从库 */
    private static final String[] READ_PREFIXES = {
            "get", "list", "find", "select", "count", "query", "page",
            "detail", "recent", "hot", "search", "top", "toVO"
    };

    @Around("execution(* com.mall.service..*.*(..))")
    public Object route(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();

        // 1. 显式注解优先（接口方法上的注解）
        DS ds = method.getAnnotation(DS.class);
        if (ds == null) {
            // 实现类方法上可能也标了注解（Spring AOP 拿的是接口方法签名时）
            try {
                Method implMethod = pjp.getTarget().getClass()
                        .getMethod(method.getName(), method.getParameterTypes());
                ds = implMethod.getAnnotation(DS.class);
            } catch (NoSuchMethodException ignored) {
                // 无实现方法：保持 null
            }
        }
        if (ds != null) {
            DataSourceContextHolder.set(ds.value());
            try {
                return pjp.proceed();
            } finally {
                DataSourceContextHolder.clear();
            }
        }

        // 2. 无注解自动路由：读方法 + 不在事务 → 从库；否则主库
        boolean inTransaction = TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive();
        if (isReadMethod(method.getName()) && !inTransaction) {
            DataSourceContextHolder.set(DataSourceContextHolder.SLAVE);
        } else {
            DataSourceContextHolder.set(DataSourceContextHolder.MASTER);
        }
        try {
            return pjp.proceed();
        } finally {
            DataSourceContextHolder.clear();
        }
    }

    /** 方法名是否为读前缀（粗略但够用：写方法都不会以这些前缀开头） */
    private boolean isReadMethod(String name) {
        for (String prefix : READ_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
