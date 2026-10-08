package com.mall.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理端审核售后请求体（阶段 7）。
 *
 * <p>approve=true 审核通过（仅退款直接退款；退货退款进入"待寄回"），
 * approve=false 审核拒绝（拒绝意见必填，写入 audit_remark）。
 */
@Data
public class AfterSaleAuditDTO {

    /** 是否审核通过 */
    @NotNull(message = "请选择审核结果")
    private Boolean approve;

    /** 审核意见（拒绝时必填，通过时可为空/留提示） */
    private String remark;
}
