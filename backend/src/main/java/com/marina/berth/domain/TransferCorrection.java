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

/** 过户交割更正说明（append-only；交割单本身不得删除或改写，更正一律追加在这里）。 */
@Getter
@Setter
@Entity
@Table(name = "transfer_correction")
public class TransferCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "transfer_id", nullable = false)
    private OwnershipTransfer transfer;

    @Column(name = "text", nullable = false, length = 512)
    private String text;

    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
