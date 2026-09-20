package com.example.shortlink.persistence.mapper;

import com.example.shortlink.persistence.entity.LinkClickLogEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LinkClickLogMapper {

    /**
     * {@code IGNORE} absorbs the duplicate an at-least-once redelivery produces, against
     * {@code uk_event (event_id, click_time)}. It is one statement per code group rather than one per
     * batch because MySQL reports a single affected-row count per statement, and that count is what
     * stops {@code click_count} from advancing for a replayed event.
     */
    @Insert("<script>INSERT IGNORE INTO t_link_click_log "
            + "(event_id, code, click_time, client_ip, user_agent, referer) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.eventId}, #{r.code}, #{r.clickTime}, INET6_ATON(#{r.clientIp}), "
            + "#{r.userAgent}, #{r.referer})"
            + "</foreach></script>")
    int insertIgnore(@Param("rows") List<LinkClickLogEntity> rows);

    @Update("UPDATE t_short_link SET click_count = click_count + #{delta} WHERE code = #{code}")
    int addClickCount(@Param("code") String code, @Param("delta") int delta);
}
