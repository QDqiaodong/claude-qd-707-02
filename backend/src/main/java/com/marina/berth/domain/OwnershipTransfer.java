package com.marina.berth.domain;

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
 * 船只过户交割单（append-only，不可删除；更正只能追加 transfer_correction）。
 *
 * <p>保存双方资料（名录快照外键）、责任时间边界 {@code effectiveAt}、交割说明与办理操作者。
 */
@Getter
@Setter
@Entity
@Table(name = "ownership_transfer")
public class OwnershipTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 交割单号 GH-yyyyMM-#### */
    @Column(name = "transfer_no", nullable = false, length = 24)
    private String transferNo;

    @ManyToOne(optional = false)
    @JoinColumn(name = "vessel_id", nullable = false)
    private Vessel vessel;

    @ManyToOne(optional = false)
    @JoinColumn(name = "old_owner_id", nullable = false)
    private OwnerProfile oldOwner;

    @ManyToOne(optional = false)
    @JoinColumn(name = "new_owner_id", nullable = false)
    private OwnerProfile newOwner;

    /** 交割生效时点（责任分界） */
    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    /** 交割说明 */
    @Column(name = "note", length = 512)
    private String note;

    /** DONE 已完成（无回退态；更正只能追加） */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 办理交割的码头工作人员 */
    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at", nullable = false)
    private LocalDateTime completedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
