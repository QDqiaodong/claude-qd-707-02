package com.marina.berth.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 船主名录。
 *
 * <p>电话 / 证件号 / 内部备注属于敏感联系资料：只对码头运营人员（STAFF）完整可见，
 * 任何其他船主（OWNER）都不能看到非本人的这些字段（见 AttributionService 的脱敏）。
 */
@Getter
@Setter
@Entity
@Table(name = "owner_profile")
public class OwnerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_name", nullable = false, length = 32)
    private String ownerName;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "id_card", length = 32)
    private String idCard;

    @Column(name = "remark", length = 256)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
