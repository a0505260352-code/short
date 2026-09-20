package com.example.shortlink.persistence.entity;

import java.time.LocalDateTime;

/**
 * One recorded redirect. Rows reach this table only from the batched click consumer, never from the
 * redirect path, and there is deliberately no {@code BaseMapper} behind it: {@code client_ip} is a
 * {@code VARBINARY(16)} filled by {@code INET6_ATON}, which generic mapping would write as text.
 */
public class LinkClickLogEntity {

    private String eventId;
    private String code;
    private LocalDateTime clickTime;
    private String clientIp;
    private String userAgent;
    private String referer;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public LocalDateTime getClickTime() {
        return clickTime;
    }

    public void setClickTime(LocalDateTime clickTime) {
        this.clickTime = clickTime;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getReferer() {
        return referer;
    }

    public void setReferer(String referer) {
        this.referer = referer;
    }
}
