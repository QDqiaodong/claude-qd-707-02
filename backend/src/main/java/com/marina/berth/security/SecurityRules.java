package com.marina.berth.security;

import com.marina.berth.common.BizException;

/**
 * 写操作身份门禁。
 *
 * <p>船主端（OWNER）在本系统里是受边界约束的只读/受限角色：交割、泊位、结算、封航、
 * 费用登记等运营动作一律只能由码头工作人员（STAFF）办理；船主仅可就自己当前名下船只
 * 发起出航申报（{@link #requireOwnerOfVessel}）。
 */
public final class SecurityRules {

    private SecurityRules() {
    }

    /** 仅码头工作人员可操作（交割、结算、封航、费用等运营写动作）。 */
    public static void requireStaff(String action) {
        if (!ActorContext.isStaff()) {
            throw new BizException("「" + action + "」只能由码头工作人员办理，船主端无此权限");
        }
    }

    /** 出航申报：工作人员不限；船主只能为自己当前名下的船只申报。 */
    public static void requireOwnerOfVessel(Long currentOwnerId, String vesselName) {
        ActorContext.Actor actor = ActorContext.current();
        if (actor.isStaff()) {
            return;
        }
        if (currentOwnerId == null || !currentOwnerId.equals(actor.ownerId())) {
            throw new BizException("船只 " + vesselName + " 当前不在您名下，不能以船主身份为其申报出航");
        }
    }
}
