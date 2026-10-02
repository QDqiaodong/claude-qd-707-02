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
 * 船只持有区间（append-only 时间轴）。
 *
 * <p>查询「某艘船在某一时刻的船主」= 取 {@code start_at <= t < end_at}（end_at 为空表示持有至今）
 * 的那条区间。过户只把旧区间收口到生效时点、再开一条新区间，<b>从不修改或删除旧区间</b>，
 * 因此旧船主在自己持有期间的记录永远可查、不会被新船主覆盖。
 */
@Getter
@Setter
@Entity
@Table(name = "vessel_owner_period")
public class VesselOwnerPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "vessel_id", nullable = false)
    private Vessel vessel;

    @ManyToOne(optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private OwnerProfile owner;

    /** 自何时起持有（含） */
    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    /** 至何时止（不含）；NULL=持有至今 */
    @Column(name = "end_at")
    private LocalDateTime endAt;

    /** 区间依据：初始登记 / 交割单 GH-xxxx */
    @Column(name = "basis", nullable = false, length = 256)
    private String basis;

    @ManyToOne
    @JoinColumn(name = "transfer_id")
    private OwnershipTransfer transfer;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
