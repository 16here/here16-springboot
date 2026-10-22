package com.mall.repository;

import com.mall.entity.Shipping;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 运单数据访问层（阶段 9 物流跟踪）。
 *
 * <p>一个订单一张运单（uk_order 唯一键兜底，防止重复发货）。
 * 状态约定：0 已发货 / 1 运输中 / 2 已签收。
 */
@Mapper
public interface ShippingRepository {

    /** 创建运单（主键回填；order_id 唯一键拦截同订单重复发货） */
    @Insert("""
            INSERT INTO shipping (order_id, order_no, shipping_company, tracking_no, status)
            VALUES (#{orderId}, #{orderNo}, #{shippingCompany}, #{trackingNo}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Shipping shipping);

    /** 按订单 ID 查运单（用户端查物流：先查运单再查轨迹） */
    @Select("SELECT * FROM shipping WHERE order_id = #{orderId}")
    Shipping findByOrderId(@Param("orderId") Long orderId);

    /** 按运单主键查 */
    @Select("SELECT * FROM shipping WHERE id = #{id}")
    Shipping selectById(@Param("id") Long id);

    /** 按运单主键查（管理端详情用，收货人信息由 Service 层广播查订单补上，
     *  避免 LEFT JOIN orders 落到 base 表查不到雪花订单） */
    @Select("SELECT * FROM shipping WHERE id = #{id}")
    Shipping selectByIdWithOrder(@Param("id") Long id);

    /** 管理端运单分页（支持物流状态筛选；收货人信息由 Service 层批量补查） */
    @Select("""
            <script>
            SELECT * FROM shipping s
            <where>
              <if test="status != null">
                AND s.status = #{status}
              </if>
            </where>
            ORDER BY s.id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Shipping> adminPage(@Param("status") Integer status,
                             @Param("offset") int offset,
                             @Param("pageSize") int pageSize);

    /** 管理端运单总数（与分页条件一致） */
    @Select("""
            <script>
            SELECT COUNT(*) FROM shipping s
            <where>
              <if test="status != null">
                AND s.status = #{status}
              </if>
            </where>
            </script>
            """)
    long adminCount(@Param("status") Integer status);

    /**
     * 更新物流状态：仅允许向前推进（newStatus > 当前 status），
     * 条件 UPDATE 防止轨迹同步并发时状态倒退/重复推进。
     */
    @Update("""
            UPDATE shipping
            SET status = #{newStatus}
            WHERE id = #{id} AND status < #{newStatus}
            """)
    int advanceStatus(@Param("id") Long id, @Param("newStatus") Integer newStatus);

    /** 查询所有未签收运单（定时任务轮询用：status < 2，按发货时间升序） */
    @Select("SELECT * FROM shipping WHERE status < 2 ORDER BY id LIMIT #{limit}")
    List<Shipping> selectPendingSync(@Param("limit") int limit);
}
