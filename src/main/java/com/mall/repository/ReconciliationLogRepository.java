package com.mall.repository;

import com.mall.entity.ReconciliationLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 对账日志数据访问层（Phase 3 对账体系）。
 *
 * <p>reconciliation_logs 表<b>不分表</b>（对账结果数据量小、需要跨库全局查询，
 * 单表 + 日期索引足够）。提供：写入对账问题、按日期/类型分页查询、查最近一次对账。
 */
@Mapper
public interface ReconciliationLogRepository {

    /** 写入一条对账问题 */
    @Insert("""
            INSERT INTO reconciliation_logs (biz_date, item_type, ref_id, ref_no, issue, status)
            VALUES (#{bizDate}, #{itemType}, #{refId}, #{refNo}, #{issue}, #{status})
            """)
    int insert(ReconciliationLog log);

    /** 管理端对账日志分页（日期/类型/状态筛选） */
    @Select("""
            <script>
            SELECT * FROM reconciliation_logs
            <where>
              <if test="bizDate != null">
                AND biz_date = #{bizDate}
              </if>
              <if test="itemType != null and itemType != ''">
                AND item_type = #{itemType}
              </if>
              <if test="status != null and status != ''">
                AND status = #{status}
              </if>
            </where>
            ORDER BY id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<ReconciliationLog> page(@Param("bizDate") LocalDate bizDate,
                                 @Param("itemType") String itemType,
                                 @Param("status") String status,
                                 @Param("offset") int offset,
                                 @Param("pageSize") int pageSize);

    /** 对账日志总数（与分页条件一致） */
    @Select("""
            <script>
            SELECT COUNT(*) FROM reconciliation_logs
            <where>
              <if test="bizDate != null">
                AND biz_date = #{bizDate}
              </if>
              <if test="itemType != null and itemType != ''">
                AND item_type = #{itemType}
              </if>
              <if test="status != null and status != ''">
                AND status = #{status}
              </if>
            </where>
            </script>
            """)
    long count(@Param("bizDate") LocalDate bizDate,
               @Param("itemType") String itemType,
               @Param("status") String status);

    /** 某日期的未处理问题数（对账结果摘要用） */
    @Select("SELECT COUNT(*) FROM reconciliation_logs WHERE biz_date = #{bizDate} AND status = 'OPEN'")
    long countOpenByDate(@Param("bizDate") LocalDate bizDate);

    /** 最近一次有对账记录的日期（"最近一次对账"入口） */
    @Select("SELECT MAX(biz_date) FROM reconciliation_logs")
    LocalDate latestBizDate();
}
