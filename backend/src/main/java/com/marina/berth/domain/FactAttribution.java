package com.marina.berth.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 事实责任归属台账（append-only）。
 *
 * <p>靠泊（BERTHING）/ 出航（SAILING）/ 服务（SERVICE）三类业务事实，每一项的责任归属
 * 都落在这张表，而不是靠「当前船主是谁」临时推断 —— 所以只改 vessel.owner_name 绝不会
 * 把旧费用错误地转给新船主。
 *
 * <p>定责方式 {@link #resolution}：
 * <ul>
 *   <li>AUTO_TIME：生效前完成的事实 → OLD；生效后发生的 → NEW</li>
 *   <li>AUTO_SPLIT_BERTHING：跨时点在泊记录，按日租金 × 双方实际停泊区间拆两段</li>
 *   <li>AUTO_SPLIT_DAYS：区间完整的持续服务，按自然天占比拆金额</li>
 *   <li>MANUAL / MANUAL_SPLIT：无法按区间拆分的事项，由工作人员选承担方/拆账并写理由</li>
 *   <li>LEGACY_RECONCILED：历史缺据数据补判（原为 PENDING）</li>
 * </ul>
 *
 * <p>事后改判不更新旧行：旧行置 {@code SUPERSEDED} 并由 {@link #supersededBy} 指向新行。
 */
@Getter
@Setter
@Entity
@Table(name = "fact_attribution")
public class FactAttribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "transfer_id")
    private OwnershipTransfer transfer;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    /** 无交割单的历史补判行，用此列钉住责任船主；交割产生的行用 transfer 上的 old/new 外键。 */
    @ManyToOne
    @JoinColumn(name = "owner_id")
    private OwnerProfile owner;

    /** BERTHING / SAILING / SERVICE */
    @Column(name = "fact_type", nullable = false, length = 16)
    private String factType;

    @Column(name = "fact_id", nullable = false)
    private Long factId;

    /** 本条是从哪条原记录拆出的新段（原记录保留为旧船主段） */
    @Column(name = "split_from_id")
    private Long splitFromId;

    /** OLD / NEW / SPLIT / PENDING */
    @Column(name = "party", nullable = false, length = 16)
    private String party;

    /** AUTO_TIME / AUTO_SPLIT_BERTHING / AUTO_SPLIT_DAYS / MANUAL / MANUAL_SPLIT / LEGACY_RECONCILED */
    @Column(name = "resolution", nullable = false, length = 24)
    private String resolution;

    /** 判定依据 / 人工理由（人工定责必填） */
    @Column(name = "basis", length = 512)
    private String basis;

    @Column(name = "period_start")
    private LocalDateTime periodStart;

    @Column(name = "period_end")
    private LocalDateTime periodEnd;

    @Column(name = "amount_old", precision = 12, scale = 2)
    private BigDecimal amountOld;

    @Column(name = "amount_new", precision = 12, scale = 2)
    private BigDecimal amountNew;

    /** PENDING / DONE / SUPERSEDED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** TRANSFER / LEGACY */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "superseded_by")
    private Long supersededBy;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
