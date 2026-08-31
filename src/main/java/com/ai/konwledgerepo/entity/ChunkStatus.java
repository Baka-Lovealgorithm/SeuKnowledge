package com.ai.konwledgerepo.entity;

/**
 * chunk 向量化状态（DB 存储 value() 字符串，保持与旧数据一致）。
 * FILTERED：清洗判定丢弃（AUTO-DROP），记录保留但永不向量化进 ES（新检索不召回）。
 */
public enum ChunkStatus {

    EMBEDDING,
    INDEXED,
    FAILED,
    FILTERED;

    public String value() {
        return name();
    }

    public boolean is(String v) {
        return name().equals(v);
    }

    public static ChunkStatus of(String v) {
        if (v == null) {
            return null;
        }
        for (ChunkStatus s : values()) {
            if (s.name().equals(v)) {
                return s;
            }
        }
        return null;
    }
}
