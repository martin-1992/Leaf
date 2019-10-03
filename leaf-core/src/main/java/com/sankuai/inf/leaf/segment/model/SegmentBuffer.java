package com.sankuai.inf.leaf.segment.model;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 双 buffer，保证 DB 出问题，buffer 还可以正常发 ID 号码
 */
public class SegmentBuffer {
    /**
     * 业务 key
     */
    private String key;

    /**
     * 双 buffer
     */
    private Segment[] segments;

    /**
     * 当前的使用的 segment 的 index，即使用哪个 buffer
     */
    private volatile int currentPos;

    /**
     * 下一个 segment 是否处于可切换状态
     */
    private volatile boolean nextReady;

    /**
     * 是否初始化完成
     */
    private volatile boolean initOk;

    /**
     * 线程是否在运行中
     */
    private final AtomicBoolean threadRunning;

    /**
     * 读写锁
     */
    private final ReadWriteLock lock;

    /**
     * 步长
     */
    private volatile int step;

    /**
     * 最小步长
     */
    private volatile int minStep;

    /**
     * 更新时间
     */
    private volatile long updateTimestamp;

    /**
     * 构造函数，初始 Segment 数组为 2，即双 buffer
     */
    public SegmentBuffer() {
        // 创建双 buffer，另一个 buffer 会使用线程池异步调用。当前
        // buffer 的号码发完，就会调用另外一个 buffer 来发号，保证
        // 有一个 buffer 准备好发号
        segments = new Segment[]{new Segment(this), new Segment(this)};
        // 指向 buffer 的指针，0 或 1，因为只有两个 buffer
        currentPos = 0;
        // 创建线程，异步调用另一个 buffer，是否已从数据库配置好，可进行发号
        nextReady = false;
        initOk = false;
        threadRunning = new AtomicBoolean(false);
        lock = new ReentrantReadWriteLock();
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Segment[] getSegments() {
        return segments;
    }

    public Segment getCurrent() {
        return segments[currentPos];
    }

    public int getCurrentPos() {
        return currentPos;
    }

    public int nextPos() {
        return (currentPos + 1) % 2;
    }

    public void switchPos() {
        currentPos = nextPos();
    }

    public boolean isInitOk() {
        return initOk;
    }

    public void setInitOk(boolean initOk) {
        this.initOk = initOk;
    }

    public boolean isNextReady() {
        return nextReady;
    }

    public void setNextReady(boolean nextReady) {
        this.nextReady = nextReady;
    }

    public AtomicBoolean getThreadRunning() {
        return threadRunning;
    }

    public Lock rLock() {
        return lock.readLock();
    }

    public Lock wLock() {
        return lock.writeLock();
    }

    public int getStep() {
        return step;
    }

    public void setStep(int step) {
        this.step = step;
    }

    public int getMinStep() {
        return minStep;
    }

    public void setMinStep(int minStep) {
        this.minStep = minStep;
    }

    public long getUpdateTimestamp() {
        return updateTimestamp;
    }

    public void setUpdateTimestamp(long updateTimestamp) {
        this.updateTimestamp = updateTimestamp;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("SegmentBuffer{");
        sb.append("key='").append(key).append('\'');
        sb.append(", segments=").append(Arrays.toString(segments));
        sb.append(", currentPos=").append(currentPos);
        sb.append(", nextReady=").append(nextReady);
        sb.append(", initOk=").append(initOk);
        sb.append(", threadRunning=").append(threadRunning);
        sb.append(", step=").append(step);
        sb.append(", minStep=").append(minStep);
        sb.append(", updateTimestamp=").append(updateTimestamp);
        sb.append('}');
        return sb.toString();
    }
}
