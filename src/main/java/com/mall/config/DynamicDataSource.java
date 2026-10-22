package com.mall.config;

import com.mall.common.DataSourceContextHolder;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 动态数据源（Phase 3 读写分离核心）。
 *
 * <p>继承 Spring 的 {@link AbstractRoutingDataSource}：内部持有 master/slave
 * 两个真实连接池，每次 {@code getConnection()} 时通过
 * {@link #determineCurrentLookupKey()} 决定走哪个池。
 *
 * <p>路由优先级（从高到低）：
 * <ol>
 *   <li>方法上 {@code @DS("master"/"slave")} 注解设置的 key（显式声明最高优先）；</li>
 *   <li>当前线程在事务中（@Transactional 已开启）→ 强制主库：
 *       主从复制有秒级延迟，事务里"刚写完立刻读"必须读主库，否则会读到旧数据；</li>
 *   <li>未设置任何路由 → 默认主库（写操作安全兜底，宁慢勿错）。</li>
 * </ol>
 *
 * <p>为什么单独判断事务：Spring 事务管理器在开启事务时就调用
 * {@code determineCurrentLookupKey()} 绑定数据源。若事务绑到从库，
 * 事务内写操作会因从库 read_only 直接报错，所以"事务 = 主库"是硬规则。
 */
public class DynamicDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        // 1. 显式注解路由（@DS）最高优先
        String explicit = DataSourceContextHolder.get();
        if (explicit != null) {
            return explicit;
        }
        // 2. 事务内强制主库（防止主从延迟读到旧数据 / 从库只读导致写失败）
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            return DataSourceContextHolder.MASTER;
        }
        // 3. 兜底默认主库（无路由声明时，写操作安全第一）
        return DataSourceContextHolder.MASTER;
    }
}
