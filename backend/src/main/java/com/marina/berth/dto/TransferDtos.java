package com.marina.berth.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 模块八：船只过户交割 —— 入参。 */
public final class TransferDtos {

    private TransferDtos() {
    }

    /** 新船主名录登记（交割时可顺带建档） */
    public record OwnerSaveReq(
            @NotBlank(message = "船主姓名不能为空") String ownerName,
            String phone,
            String idCard,
            String remark) {
    }

    /** 预览请求：选定船只、生效时点（新船主可选，预览主要为老 → ? 的责任面） */
    public record PreviewReq(
            @NotNull(message = "请选择过户船只") Long vesselId,
            @NotNull(message = "交割生效时点不能为空") LocalDateTime effectiveAt) {
    }

    /**
     * 工作人员对某一项事实的责任选择。
     * key 与预览返回的 key 对应；party=OLD/NEW，split=true 表示人工拆账并给金额。
     */
    public record DecisionReq(
            @NotBlank(message = "定责项缺少 key") String key,
            String party,
            Boolean split,
            java.math.BigDecimal amountOld,
            java.math.BigDecimal amountNew,
            String reason) {
    }

    /** 提交交割 */
    public record TransferSubmitReq(
            @NotNull(message = "请选择过户船只") Long vesselId,
            @NotNull(message = "请选择新船主") Long newOwnerId,
            @NotNull(message = "交割生效时点不能为空") LocalDateTime effectiveAt,
            String note,
            @NotBlank(message = "办理人不能为空") String operator,
            List<DecisionReq> decisions) {
    }

    /** 追加更正说明 */
    public record CorrectionReq(
            @NotBlank(message = "更正说明不能为空") String text,
            @NotBlank(message = "办理人不能为空") String operator) {
    }

    /** 历史归属待判补判：有交割单时选 OLD/NEW；无交割单的历史行用 ownerId 直接指定承担船主 */
    public record ReconcileReq(
            String party,
            Long ownerId,
            @NotBlank(message = "必须填写判定依据，禁止无依据按当前船主定责") String basis,
            @NotBlank(message = "办理人不能为空") String operator) {
    }
}
