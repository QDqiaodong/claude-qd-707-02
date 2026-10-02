package com.marina.berth.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 码头服务与费用记录（接电 / 加水 / 加油 / 船体清洗 / 吊机上下排 / 维修 / 污油水接收）。
 *
 * <p>关联对象可挂在泊位上，也可直接挂在船上：两个外键都可空，但至少要有其一。
 */
@Data
@Entity
@Table(name = "service_record")
public class ServiceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 服务类型：POWER 接电 / WATER 加水 / FUEL 加油 / HULL_WASH 船体清洗 /
     * LIFT 吊机上下排 / REPAIR 维修 / WASTE 污油水接收
     */
    @Column(name = "service_type", nullable = false, length = 16)
    private String serviceType;

    /** POINT 一次性服务 / CONTINUOUS 持续服务（持续服务才有 start_date/end_date 区间） */
    @Column(name = "service_kind", nullable = false, length = 16)
    private String serviceKind;

    /** 单价 */
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    /** 数量 */
    @Column(name = "quantity", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantity;

    /** 金额 = 单价 × 数量 */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** 发生日期（一次性服务的发生日；持续服务为入账/开始参考日） */
    @Column(name = "occurred_date", nullable = false)
    private LocalDate occurredDate;

    /** 持续服务起（可空：历史导入缺据） */
    @Column(name = "start_date")
    private LocalDate startDate;

    /** 持续服务止（NULL=仍在续） */
    @Column(name = "end_date")
    private LocalDate endDate;

    @ManyToOne
    @JoinColumn(name = "berth_id")
    private Berth berth;

    @ManyToOne
    @JoinColumn(name = "vessel_id")
    private Vessel vessel;

    /**
     * 责任船主快照（交割/定责时写入）。NULL 表示历史导入、责任以 fact_attribution 台账为准，
     * 页面按归属台账判定，绝不因当前船主变化而改写。
     */
    @ManyToOne
    @JoinColumn(name = "owner_id")
    private OwnerProfile owner;

    /** UNSETTLED 未结 / SETTLED 已结 / ON_ACCOUNT 挂账 */
    @Column(name = "settle_status", nullable = false, length = 16)
    private String settleStatus;

    /** 欠费说明（仅码头运营人员可见，船主端脱敏） */
    @Column(name = "debt_note", length = 256)
    private String debtNote;

    /** 内部备注（仅码头运营人员可见，任何船主都不可见） */
    @Column(name = "internal_remark", length = 256)
    private String internalRemark;

    /** 历史导入缺据说明 */
    @Column(name = "legacy_note", length = 256)
    private String legacyNote;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
