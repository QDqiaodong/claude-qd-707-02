package com.marina.berth.security;

/**
 * 当前请求的访问身份（演示系统没有登录体系，身份由请求头带入）。
 *
 * <ul>
 *   <li>{@link #STAFF} 码头运营人员：可查看完整交割链、电话、欠费说明、内部备注，可办理交割与各项业务。</li>
 *   <li>{@link #OWNER} 船主端：只能看到自己持有期间的船只与费用；看不到其他船主的电话、欠费说明、内部备注；
 *       业务写操作受限（只可就自己当前名下船只发起出航申报）。</li>
 * </ul>
 *
 * <p>身份通过两个请求头传入：{@code X-Actor-Role=STAFF|OWNER}、{@code X-Actor-Id=<owner_profile.id>}。
 * 不传时按 {@link #STAFF} 处理（运营台内部调用/页面默认）。
 */
public final class ActorContext {

    public static final String STAFF = "STAFF";
    public static final String OWNER = "OWNER";

    public static final String HEADER_ROLE = "X-Actor-Role";
    public static final String HEADER_OWNER_ID = "X-Actor-Id";

    private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

    private ActorContext() {
    }

    public record Actor(String role, Long ownerId) {
        public boolean isStaff() {
            return STAFF.equals(role);
        }

        public boolean isOwner() {
            return OWNER.equals(role);
        }
    }

    public static void set(Actor actor) {
        CURRENT.set(actor);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 当前身份；未设置时视为码头运营人员。 */
    public static Actor current() {
        Actor actor = CURRENT.get();
        return actor == null ? new Actor(STAFF, null) : actor;
    }

    public static boolean isStaff() {
        return current().isStaff();
    }

    /** 当前船主 id（仅 OWNER 身份有值）。 */
    public static Long ownerId() {
        Actor actor = current();
        return actor.isOwner() ? actor.ownerId() : null;
    }
}
