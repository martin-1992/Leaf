package com.sankuai.inf.leaf.segment.model;

public class LeafAlloc {

    /**
     * 业务 key
     */
    private String key;

    /**
     * 最大 ID，maxId = maxId + step
     */
    private long maxId;

    /**
     * 步长，每次 maxId 增加的数
     */
    private int step;

    /**
     * 更新时间
     */
    private String updateTime;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public long getMaxId() {
        return maxId;
    }

    public void setMaxId(long maxId) {
        this.maxId = maxId;
    }

    public int getStep() {
        return step;
    }

    public void setStep(int step) {
        this.step = step;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }
}
