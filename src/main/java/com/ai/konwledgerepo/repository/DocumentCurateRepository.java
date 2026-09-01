package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.DocumentCurate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 文档策展 md 分页版本存储。
 */
public interface DocumentCurateRepository extends JpaRepository<DocumentCurate, Long> {

    /** 取指定版本的全部页（按页码升序，供拼回整篇 md） */
    List<DocumentCurate> findByDocIdAndVersionOrderByPageNumAsc(Long docId, Integer version);

    /** 取最新版本的全部页（按页码升序） */
    List<DocumentCurate> findByDocIdOrderByVersionDescPageNumAsc(Long docId);

    /** 文档当前最大版本号（无记录返回 0） */
    @Query("select coalesce(max(c.version), 0) from DocumentCurate c where c.docId = :docId")
    int maxVersion(@Param("docId") Long docId);

    /** 文档是否存在任意策展版本 */
    boolean existsByDocId(Long docId);

    /** 文档删除/替换时整删（含全部版本） */
    void deleteByDocId(Long docId);
}
