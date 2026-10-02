package com.marina.berth.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出参视图。
 *
 * <p>全部用 record：这些对象只在 service 里构造、直接序列化给前端，不需要 setter。
 * record 的访问器（如 {@code berthNo()}）Jackson 原生支持。
 *
 * <p>其中带 {@code current*} 前缀的字段（泊位上的当前船只、船上的当前泊位）来自
 * 实体上的 {@code @JoinFormula} 派生关联，不是冗余列。
 */
public final class Views {

    private Views() {
    }

    /** 泊位（currentVessel* 来自 Berth.currentVessel 的 @JoinFormula 派生关联） */
    public record BerthView(
            Long id,
            String berthNo,
            String zoneCode,
            String pierNo,
            BigDecimal lengthM,
            BigDecimal maxDraftM,
            String allowType,
            String allowTypeNames,
            BigDecimal dailyRate,
            String utility,
            String utilityName,
            String status,
            String statusName,
            Long currentVesselId,
            String currentVesselName,
            String currentVesselType,
            String currentVesselTypeName,
            String currentVesselOwner,
            BigDecimal currentVesselLengthM,
            BigDecimal currentVesselDraftM,
            Long currentBerthingId,
            String currentBerthingNo,
            LocalDateTime currentBerthTime,
            LocalDateTime currentEtaDepart,
            LocalDateTime updatedAt) {
    }

    /** 船只台账（currentBerthNo 来自 Vessel.currentBerth 的 @JoinFormula 派生关联） */
    public record VesselView(
            Long id,
            String name,
            String regNo,
            Long ownerId,
            String ownerName,
            String ownerPhone,
            String ownerIdCard,
            String ownerRemark,
            Boolean heldByMe,
            String typeCode,
            String typeName,
            BigDecimal lengthM,
            BigDecimal widthM,
            BigDecimal draftM,
            LocalDate regDate,
            LocalDate insuranceDue,
            Long insuranceDays,
            String insuranceLevel,
            String status,
            String statusName,
            Long currentBerthId,
            String currentBerthNo,
            Integer berthingCount,
            BigDecimal berthingFee,
            String liabilityHint,
            LocalDateTime updatedAt) {
    }

    /** 靠泊记录 */
    public record BerthingView(
            Long id,
            String recordNo,
            Long berthId,
            String berthNo,
            Long vesselId,
            String vesselName,
            LocalDateTime berthTime,
            LocalDateTime etaDepart,
            LocalDateTime actualDepart,
            Integer days,
            BigDecimal fee,
            String status,
            String statusName,
            String party,
            String partyName,
            String attributionStatus,
            String attributionBasis,
            Boolean isSplitSegment,
            Long splitFromId,
            LocalDateTime updatedAt) {
    }

    /** 出航申报 */
    public record SailingPlanView(
            Long id,
            String planNo,
            Long vesselId,
            String vesselName,
            String vesselType,
            String vesselTypeName,
            String berthNo,
            String skipper,
            String phone,
            Boolean contactHandoff,
            LocalDateTime departTime,
            LocalDateTime etaReturn,
            String route,
            Integer crewCount,
            String weather,
            String status,
            String statusName,
            Long overdueHours,
            Boolean vesselAtSea,
            String party,
            String partyName,
            String attributionStatus,
            String attributionBasis,
            LocalDateTime approvedAt,
            LocalDateTime suspendedAt,
            String suspendedReason,
            LocalDateTime resumedAt,
            LocalDateTime updatedAt) {
    }

    /** 码头服务与费用记录 */
    public record ServiceRecordView(
            Long id,
            String serviceType,
            String serviceTypeName,
            String serviceKind,
            String serviceKindName,
            String serviceUnit,
            BigDecimal unitPrice,
            BigDecimal quantity,
            BigDecimal amount,
            LocalDate occurredDate,
            LocalDate startDate,
            LocalDate endDate,
            String month,
            Long berthId,
            String berthNo,
            Long vesselId,
            String vesselName,
            Long ownerId,
            String ownerName,
            String settleStatus,
            String settleStatusName,
            String party,
            String partyName,
            String attributionStatus,
            String attributionBasis,
            BigDecimal liableAmount,
            String debtNote,
            String internalRemark,
            String legacyNote,
            Boolean isSplitSegment,
            Long splitFromId,
            LocalDateTime updatedAt) {
    }

    /** 服务费月度汇总里的一项 */
    public record MonthSum(String month, BigDecimal amount, Long count, BigDecimal settledAmount) {
    }

    /** 服务费按类型汇总里的一项 */
    public record TypeSum(String serviceType, String serviceTypeName, BigDecimal amount, Long count) {
    }

    /** 服务费汇总：总额 + 未结 + 按月 + 按类型 */
    public record ServiceSummary(
            BigDecimal totalAmount,
            BigDecimal unsettledAmount,
            BigDecimal onAccountAmount,
            Long totalCount,
            List<MonthSum> byMonth,
            List<TypeSum> byType) {
    }

    /** 封航令列表项（带处置项计数，值班主管一眼看到风险面） */
    public record ClosureView(
            Long id,
            String orderNo,
            String reasonType,
            String reasonTypeName,
            String reasonDetail,
            LocalDateTime effectiveAt,
            String status,
            String statusName,
            String issuedBy,
            LocalDateTime createdAt,
            LocalDateTime activatedAt,
            LocalDateTime liftingStartedAt,
            String liftedBy,
            LocalDateTime archivedAt,
            String archivedBy,
            LocalDateTime updatedAt,
            Long dispTotal,
            Long dispToContact,
            Long dispConfirmed,
            Long dispEscalated,
            Long dispBerthed,
            Long suspendedPlanCount,
            LocalDateTime now) {
    }

    /** 受影响的出航申报（封航详情里：待审批 + 暂停放行两类同处可见） */
    public record AffectedPlanView(
            Long id,
            String planNo,
            Long vesselId,
            String vesselName,
            String vesselTypeName,
            String skipper,
            String phone,
            LocalDateTime departTime,
            LocalDateTime etaReturn,
            String route,
            Integer crewCount,
            String status,
            String statusName,
            LocalDateTime approvedAt,
            LocalDateTime suspendedAt,
            String suspendedReason,
            LocalDateTime resumedAt,
            String groupTag) {
    }

    /** 返港处置项 */
    public record DispositionView(
            Long id,
            Long closureId,
            String closureNo,
            Long vesselId,
            String vesselName,
            String vesselTypeName,
            Long sailingPlanId,
            String planNo,
            String skipper,
            String phone,
            LocalDateTime etaReturn,
            String status,
            String statusName,
            Long assignedBerthId,
            String assignedBerthNo,
            LocalDateTime assignedAt,
            String assignedBy,
            LocalDateTime confirmedAt,
            LocalDateTime escalatedAt,
            String escalateReason,
            LocalDateTime berthedAt,
            Long berthedBerthId,
            String berthedBerthNo,
            Long berthingId,
            String berthingNo,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<ContactView> contacts,
            Boolean fitAssignedBerth) {
    }

    /** 一次联系留痕 */
    public record ContactView(
            Long id,
            LocalDateTime contactAt,
            String resultCode,
            String resultName,
            String resultText,
            String remark,
            String operator,
            String requestId,
            LocalDateTime createdAt) {
    }

    /** 可接纳返港的泊位（适泊校验后的候选项） */
    public record BerthOptionView(
            Long id,
            String berthNo,
            String zoneCode,
            java.math.BigDecimal lengthM,
            java.math.BigDecimal maxDraftM,
            String allowTypeNames,
            java.math.BigDecimal dailyRate,
            String status) {
    }

    /** 码头总览计数器 */
    public record Overview(
            Long berthTotal,
            Long berthFree,
            Long berthInPort,
            Long berthReserved,
            Long berthDisabled,
            Long vesselTotal,
            Long vesselInPort,
            Long vesselAtSea,
            Long vesselMaintenance,
            Long planPending,
            Long planApproved,
            Long planOverdue,
            Long planSuspended,
            Long closureActive,
            Long closureEffective,
            Long dispRiskOpen,
            Long serviceUnsettled,
            BigDecimal serviceUnsettledAmount,
            BigDecimal monthServiceAmount,
            Long insuranceWarn) {
    }

    // ================================================================
    // 模块八：船只过户交割
    // ================================================================

    /** 船主名录（phone/idCard/remark 仅在码头运营人员视角回填，船主端为 null） */
    public record OwnerView(
            Long id,
            String ownerName,
            String phone,
            String idCard,
            String remark,
            java.time.LocalDateTime createdAt) {
    }

    /**
     * 交割预览/定责清单里的一项事实（靠泊/出航/服务）。
     *
     * <p>{@code suggestedParty/suggestedResolution} 是系统按生效时点给出的建议（旧/新/拆账/待判），
     * {@code auto} 表示该项是否能由系统自动判定；不可自动判定的项要求工作人员在提交时
     * 通过 decision 选承担方并填 reason（basis）。
     */
    public record PreviewItemView(
            String key,
            String factType,
            String factTypeName,
            Long factId,
            String factNo,
            String title,
            java.time.LocalDateTime periodStart,
            java.time.LocalDateTime periodEnd,
            java.time.LocalDate serviceStart,
            java.time.LocalDate serviceEnd,
            String settleStatus,
            String settleStatusName,
            BigDecimal amount,
            String crosses,
            String suggestedParty,
            String suggestedPartyName,
            String suggestedResolution,
            String suggestedResolutionName,
            String suggestedBasis,
            BigDecimal suggestedAmountOld,
            BigDecimal suggestedAmountNew,
            Boolean auto,
            Boolean pending,
            String pendingReason,
            String debtNote,
            String internalRemark,
            String legacyNote) {
    }

    /** 交割单视图（含定责项与更正说明） */
    public record TransferView(
            Long id,
            String transferNo,
            Long vesselId,
            String vesselName,
            String vesselRegNo,
            Long oldOwnerId,
            String oldOwnerName,
            String oldOwnerPhone,
            Long newOwnerId,
            String newOwnerName,
            String newOwnerPhone,
            java.time.LocalDateTime effectiveAt,
            String note,
            String status,
            String operator,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime completedAt,
            List<AttributionView> items,
            List<CorrectionView> corrections,
            Long pendingCount,
            BigDecimal oldAmountTotal,
            BigDecimal newAmountTotal) {
    }

    /** 一条事实责任归属（台账行） */
    public record AttributionView(
            Long id,
            Long transferId,
            String transferNo,
            Long vesselId,
            String vesselName,
            String factType,
            String factTypeName,
            Long factId,
            String factNo,
            Long splitFromId,
            String party,
            String partyName,
            String resolution,
            String resolutionName,
            String basis,
            java.time.LocalDateTime periodStart,
            java.time.LocalDateTime periodEnd,
            BigDecimal amountOld,
            BigDecimal amountNew,
            String status,
            String statusName,
            String source,
            String sourceName,
            Long supersededBy,
            String operator,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt) {
    }

    /** 一条交割更正说明（append-only） */
    public record CorrectionView(
            Long id,
            Long transferId,
            String text,
            String operator,
            java.time.LocalDateTime createdAt) {
    }

    /** 交割预览结果：头部信息 + 逐项建议 + 旧/新费用小计 */
    public record TransferPreview(
            Long vesselId,
            String vesselName,
            String vesselRegNo,
            String oldOwnerName,
            Long oldOwnerId,
            java.time.LocalDateTime effectiveAt,
            List<PreviewItemView> items,
            Long autoCount,
            Long manualCount,
            Long pendingCount,
            BigDecimal oldAmountTotal,
            BigDecimal newAmountTotal) {
    }
}
