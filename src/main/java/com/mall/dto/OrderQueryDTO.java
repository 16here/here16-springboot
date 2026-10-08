package com.mall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 订单列表查询参数（用户端 + 管理端共用）。
 */
@Data
public class OrderQueryDTO {

    /** 状态筛选：0待付款 1待发货 2待收货 3已完成 4已取消；null=全部 */
    private Integer status;

    /** 订单号模糊搜索（管理端用；用户端不传） */
    private String keyword;

    /** 页码，从 1 开始 */
    @Min(value = 1, message = "页码不能小于 1")
    private Integer page = 1;

    /** 每页数量，上限 100 */
    @Min(value = 1, message = "每页数量不能小于 1")
    @Max(value = 100, message = "每页数量不能超过 100")
    private Integer pageSize = 10;
}
