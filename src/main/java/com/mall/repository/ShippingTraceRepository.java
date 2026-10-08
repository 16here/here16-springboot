package com.mall.repository;

import com.mall.entity.ShippingTrace;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 物流轨迹数据访问层（阶段 9 物流跟踪）。
 *
 * <p>轨迹写入策略：同步前先删旧轨迹再批量插入（快递 API 返回的是"截至当前的全部轨迹"，
 * 全量覆盖保证轨迹与第三方一致，不产生重复节点）；查询按时间正序供时间轴渲染。
 */
@Mapper
public interface ShippingTraceRepository {

    /** 新增一条轨迹 */
    @Insert("""
            INSERT INTO shipping_traces (shipping_id, track_time, track_info, location)
            VALUES (#{shippingId}, #{trackTime}, #{trackInfo}, #{location})
            """)
    int insert(ShippingTrace trace);

    /** 批量新增轨迹（同步一批节点） */
    @Insert("""
            <script>
            INSERT INTO shipping_traces (shipping_id, track_time, track_info, location)
            VALUES
            <foreach collection="list" item="t" separator=",">
              (#{t.shippingId}, #{t.trackTime}, #{t.trackInfo}, #{t.location})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<ShippingTrace> traces);

    /** 按运单查全部轨迹（时间正序，用户端时间轴直接渲染） */
    @Select("SELECT * FROM shipping_traces WHERE shipping_id = #{shippingId} ORDER BY track_time ASC, id ASC")
    List<ShippingTrace> findByShippingId(@Param("shippingId") Long shippingId);

    /** 某运单的轨迹条数（同步推进的"当前节点数"依据） */
    @Select("SELECT COUNT(*) FROM shipping_traces WHERE shipping_id = #{shippingId}")
    long countByShippingId(@Param("shippingId") Long shippingId);

    /** 清空某运单全部轨迹（同步前调用，全量覆盖） */
    @Delete("DELETE FROM shipping_traces WHERE shipping_id = #{shippingId}")
    int deleteByShippingId(@Param("shippingId") Long shippingId);
}
