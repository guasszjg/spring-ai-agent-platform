package com.shimeta.mcp;

/** 审核、启用一个 MCP 工具 */
public class McpToolUpdateRequest {

    private Boolean enabled;
    /** 启用写操作类工具时必须为 true */
    private Boolean confirmWrite;
    private String category;
    private Boolean writeOperation;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getConfirmWrite() {
        return confirmWrite;
    }

    public void setConfirmWrite(Boolean confirmWrite) {
        this.confirmWrite = confirmWrite;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Boolean getWriteOperation() {
        return writeOperation;
    }

    public void setWriteOperation(Boolean writeOperation) {
        this.writeOperation = writeOperation;
    }
}
