package com.shimeta.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 分享链接的一个匿名访客：浏览器里保存访客 ID，访客令牌只存哈希 */
@Entity
@Table(name = "agent_share_visitors")
public class AgentShareVisitor {

    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "link_id", nullable = false, length = 64)
    private String linkId;
    @Column(name = "token_hash", length = 128)
    private String tokenHash;
    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;
    @Column(name = "message_count", nullable = false)
    private long messageCount;
    @Column(name = "token_count", nullable = false)
    private long tokenCount;
    @Column(name = "last_ip", length = 64)
    private String lastIp;
    @Column(name = "first_seen_at")
    private LocalDateTime firstSeenAt;
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLinkId() { return linkId; }
    public void setLinkId(String linkId) { this.linkId = linkId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public LocalDateTime getTokenExpiresAt() { return tokenExpiresAt; }
    public void setTokenExpiresAt(LocalDateTime tokenExpiresAt) { this.tokenExpiresAt = tokenExpiresAt; }
    public long getMessageCount() { return messageCount; }
    public void setMessageCount(long messageCount) { this.messageCount = messageCount; }
    public long getTokenCount() { return tokenCount; }
    public void setTokenCount(long tokenCount) { this.tokenCount = tokenCount; }
    public String getLastIp() { return lastIp; }
    public void setLastIp(String lastIp) { this.lastIp = lastIp; }
    public LocalDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(LocalDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
