package com.mall.repository;

import com.mall.entity.AfterSale;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 售后单数据访问层（阶段 7）。
 *
 * <p>关键设计：
 * <ul>
 *   <li>用户侧按 id 操作全部带 userId 条件（防越权）；</li>
 *   <li>状态流转全部用"条件 UPDATE + 状态前置"，由数据库保证并发下
 *       不会被重复审核 / 重复退款；</li>
 *   <li>用户列表/管理端列表 JOIN users 带用户名，便于运营侧查看。</li>
 * </ul>
 */
@Mapper
public interface AfterSaleRepository {

    /** 新增售后单（主键回填） */
    @Insert("""
            INSERT INTO after_sales (after_sale_no, order_id, order_no, user_id,
                                     type, reason, amount, status)
            VALUES (#{afterSaleNo}, #{orderId}, #{orderNo}, #{userId},
                    #{type}, #{reason}, #{amount}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AfterSale afterSale);

    /** 按主键 + 用户查（用户详情，归属校验） */
    @Select("""
            SELECT a.*, o.status AS order_status, o.total_amount AS order_total_amount
            FROM after_sales a
            LEFT JOIN orders o ON a.order_id = o.id
            WHERE a.id = #{id} AND a.user_id = #{userId}
            """)
    AfterSale selectByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 按主键查（管理端用，JOIN 用户名与订单状态） */
    @Select("""
            SELECT a.*, u.username, o.status AS order_status, o.total_amount AS order_total_amount
            FROM after_sales a
            LEFT JOIN users u ON a.user_id = u.id
            LEFT JOIN orders o ON a.order_id = o.id
            WHERE a.id = #{id}
            """)
    AfterSale selectById(@Param("id") Long id);

    /** 查订单的进行中售后单（待审核/待寄回），申请时防重复 */
    @Select("""
            SELECT * FROM after_sales
            WHERE order_id = #{orderId} AND status IN (0, 1)
            ORDER BY id DESC LIMIT 1
            """)
    AfterSale selectActiveByOrderId(@Param("orderId") Long orderId);

    /** 用户售后分页（状态筛选，状态为 null 查全部） */
    @Select("""
            <script>
            SELECT a.*, o.status AS order_status, o.total_amount AS order_total_amount
            FROM after_sales a
            LEFT JOIN orders o ON a.order_id = o.id
            WHERE a.user_id = #{userId}
            <if test="status != null">
              AND a.status = #{status}
            </if>
            ORDER BY a.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<AfterSale> selectPageByUser(@Param("userId") Long userId,
                                     @Param("status") Integer status,
                                     @Param("offset") int offset,
                                     @Param("pageSize") int pageSize);

    /** 用户售后总数 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM after_sales a
            WHERE a.user_id = #{userId}
            <if test="status != null">
              AND a.status = #{status}
            </if>
            </script>
            """)
    long countByUser(@Param("userId") Long userId, @Param("status") Integer status);

    /** 管理端售后分页（类型/状态筛选） */
    @Select("""
            <script>
            SELECT a.*, u.username, o.status AS order_status, o.total_amount AS order_total_amount
            FROM after_sales a
            LEFT JOIN users u ON a.user_id = u.id
            LEFT JOIN orders o ON a.order_id = o.id
            <where>
              <if test="type != null">
                AND a.type = #{type}
              </if>
              <if test="status != null">
                AND a.status = #{status}
              </if>
            </where>
            ORDER BY a.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<AfterSale> adminPage(@Param("type") Integer type,
                              @Param("status") Integer status,
                              @Param("offset") int offset,
                              @Param("pageSize") int pageSize);

    /** 管理端售后总数 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM after_sales a
            <where>
              <if test="type != null">
                AND a.type = #{type}
              </if>
              <if test="status != null">
                AND a.status = #{status}
              </if>
            </where>
            </script>
            """)
    long adminCount(@Param("type") Integer type, @Param("status") Integer status);

    /**
     * 用户撤销申请：仅待审核（0）可撤销 → 4。
     */
    @Update("UPDATE after_sales SET status = 4 WHERE id = #{id} AND user_id = #{userId} AND status = 0")
    int cancelApply(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 用户填写退货物流单号：仅退货退款（type=2）且待寄回（status=1）可填。
     */
    @Update("UPDATE after_sales SET logistics_no = #{logisticsNo} WHERE id = #{id} AND user_id = #{userId} AND status = 1")
    int fillLogistics(@Param("id") Long id, @Param("userId") Long userId, @Param("logisticsNo") String logisticsNo);

    /**
     * 审核拒绝：仅待审核（0）→ 3（已拒绝），写审核意见与时间。
     */
    @Update("UPDATE after_sales SET status = 3, audit_remark = #{remark}, audit_at = NOW() WHERE id = #{id} AND status = 0")
    int reject(@Param("id") Long id, @Param("remark") String remark);

    /**
     * 审核通过（退货退款）：仅待审核（0）→ 1（待寄回）。
     */
    @Update("UPDATE after_sales SET status = 1, audit_remark = #{remark}, audit_at = NOW() WHERE id = #{id} AND status = 0")
    int approveReturn(@Param("id") Long id, @Param("remark") String remark);

    /**
     * 退款完成：仅退款（type=1）审核通过时 0→2；退货退款确认收货退款时 1→2。
     * 条件 UPDATE 由状态前置保证"只退一次"，配合售后 Service 的事务整体完成。
     */
    @Update("UPDATE after_sales SET status = 2, refund_at = NOW() WHERE id = #{id} AND status IN (0, 1)")
    int markRefunded(@Param("id") Long id);
}
