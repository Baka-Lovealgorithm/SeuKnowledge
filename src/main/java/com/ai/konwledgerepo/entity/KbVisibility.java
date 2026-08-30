package com.ai.konwledgerepo.entity;

/**
 * 知识库可见性（DB 存储 value() 字符串）。
 * PUBLIC：空间内成员按角色访问（现状行为）；
 * RESTRICTED：仅显式授权用户（kb_access）可访问，空间 ADMIN/OWNER 与创建者始终可访问。
 */
public enum KbVisibility {

    PUBLIC,
    RESTRICTED;

    public String value() {
        return name();
    }

    public boolean is(String v) {
        return name().equals(v);
    }

    public static boolean isRestricted(String v) {
        return RESTRICTED.name().equals(v);
    }
}
