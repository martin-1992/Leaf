package com.sankuai.inf.leaf.segment.model;

import java.util.concurrent.atomic.AtomicLong;

public class Segment {
    /**
     * 当前 ID 值，使用原子类，保证多线程下的线程安全
     */
    private AtomicLong value = new AtomicLong(0);

    /**
     * 最大 ID 值
     */
    private volatile long max;

    /**
     * 步长
     */
    private volatile int step;

    private SegmentBuffer buffer;

    public Segment(SegmentBuffer buffer) {
        this.buffer = buffer;
    }

    public AtomicLong getValue() {
        return value;
    }

    public void setValue(AtomicLong value) {
        this.value = value;
    }

    public long getMax() {
        return max;
    }

    public void setMax(long max) {
        this.max = max;
    }

    public int getStep() {
        return step;
    }

    public void setStep(int step) {
        this.step = step;
    }

    public SegmentBuffer getBuffer() {
        return buffer;
    }

    /**
     * 获取当前 buffer 剩余的 ID 号
     * @return
     */
    public long getIdle() {
        return this.getMax() - getValue().get();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Segment(");
        sb.append("value:");
        sb.append(value);
        sb.append(",max:");
        sb.append(max);
        sb.append(",step:");
        sb.append(step);
        sb.append(")");
        return sb.toString();
    }
}
