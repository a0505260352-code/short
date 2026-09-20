package com.example.shortlink.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.shortlink.persistence.entity.ShortLinkEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ShortLinkMapper extends BaseMapper<ShortLinkEntity> {

    default boolean existsByCode(String code) {
        return exists(new LambdaQueryWrapper<ShortLinkEntity>().eq(ShortLinkEntity::getCode, code));
    }

    default ShortLinkEntity findByCode(String code) {
        return selectOne(new LambdaQueryWrapper<ShortLinkEntity>().eq(ShortLinkEntity::getCode, code));
    }

    /**
     * Flips at most {@code batchSize} due rows to EXPIRED and reports how many it changed.
     *
     * <p>The cutoff is bound rather than taken from the database's {@code NOW()} so a test — and any
     * instance whose session timezone differs from the column's UTC convention — sees the same
     * predicate the redirect path applies in {@code ShortLinkService}. Rows leave the predicate as they
     * are updated, so repeated calls drain the backlog and concurrent callers cannot double-count work.
     */
    @Update("UPDATE t_short_link SET status = " + ShortLinkEntity.STATUS_EXPIRED
            + " WHERE status = " + ShortLinkEntity.STATUS_ACTIVE
            + " AND valid_until <= #{cutoff} LIMIT #{batchSize}")
    int expireDueBefore(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}
