package com.shimeta.mcp;

public final class McpReviewStatus {
    /** 刚拉取到，还没审核 */
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    /** 上游改了名称、说明或参数格式，已自动停用，需要重新审核 */
    public static final String CHANGED = "CHANGED";
    /** 上游已经没有这个工具 */
    public static final String REMOVED = "REMOVED";

    private McpReviewStatus() {
    }
}
