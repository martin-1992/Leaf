package com.sankuai.inf.leaf.snowflake;

import com.google.common.base.Preconditions;
import com.sankuai.inf.leaf.IDGen;
import com.sankuai.inf.leaf.common.Result;
import com.sankuai.inf.leaf.common.Status;
import com.sankuai.inf.leaf.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

public class SnowflakeIDGenImpl implements IDGen {

    @Override
    public boolean init() {
        return true;
    }

    static private final Logger LOGGER = LoggerFactory.getLogger(SnowflakeIDGenImpl.class);

    private final long twepoch = 1288834974657L;
    private final long workerIdBits = 10L;

    // 最大能够分配的 workerid = 1023
    private final long maxWorkerId = -1L ^ (-1L << workerIdBits);
    private final long sequenceBits = 12L;
    private final long workerIdShift = sequenceBits;
    private final long timestampLeftShift = sequenceBits + workerIdBits;
    // sequenceBits 为 12 时，sequenceMask = 4095
    private final long sequenceMask = -1L ^ (-1L << sequenceBits);
    private long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;
    public boolean initFlag = false;
    private static final Random RANDOM = new Random();
    private int port;

    /**
     * snowflake 算法生成
     * @param zkAddress
     * @param port
     */
    public SnowflakeIDGenImpl(String zkAddress, int port) {
        this.port = port;
        // 创建对象
        SnowflakeZookeeperHolder holder = new SnowflakeZookeeperHolder(Utils.getIp(), String.valueOf(port), zkAddress);
        // 初始化
        initFlag = holder.init();
        if (initFlag) {
            workerId = holder.getWorkerID();
            LOGGER.info("START SUCCESS USE ZK WORKERID-{}", workerId);
        } else {
            Preconditions.checkArgument(initFlag, "Snowflake Id Gen is not init ok");
        }
        Preconditions.checkArgument(workerId >= 0 && workerId <= maxWorkerId, "workerID must gte 0 and lte 1023");
    }

    /**
     * 获取 ID 时，先判断机器时间有没回拨
     *
     * @param key
     * @return
     */
    @Override
    public synchronized Result get(String key) {
        long timestamp = timeGen();
        if (timestamp < lastTimestamp) {
            // 当前时间小于最近一次上报时间 lastTimestamp，发生回拨，计算时间差
            long offset = lastTimestamp - timestamp;
            if (offset <= 5) {
                try {
                    // 时间差小于等于 5 秒，则等待两倍时间，<< 1 表示左移一位，
                    // 即乘以 2，使用位运算速度更快
                    wait(offset << 1);
                    timestamp = timeGen();
                    // 再次判断当前时间是否小于最近一次上报时间 lastTimestamp，是则写入异常
                    if (timestamp < lastTimestamp) {
                        return new Result(-1, Status.EXCEPTION);
                    }
                } catch (InterruptedException e) {
                    LOGGER.error("wait interrupted");
                    return new Result(-2, Status.EXCEPTION);
                }
            } else {
                // 时间差大于 5 秒，则不等待当前时间超过最近一次上报时间，直接写入异常
                return new Result(-3, Status.EXCEPTION);
            }
        }
        // 最近一次上报时间等于当前时间
        if (lastTimestamp == timestamp) {
            // 如果大于 sequenceMask，则是求余数
            sequence = (sequence + 1) & sequenceMask;
            if (sequence == 0) {
                // seq 为 0 的时候是初始值，即第一次开始，对
                // seq 做随机，sequence 用于生成 ID 值
                sequence = RANDOM.nextInt(100);
                // 获取大于 lastTimestamp 的当前时间
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            // 当前时间大于最近一次上报时间，随机生成 100 以内的 sequence
            sequence = RANDOM.nextInt(100);
        }
        // 执行到这里，表明当前时间大于最近一次上报时间，将当前时间赋值
        // 为 lastTimestamp，用于上报数据到 zookeeper
        lastTimestamp = timestamp;
        // 生成 ID 值
        long id = ((timestamp - twepoch) << timestampLeftShift) | (workerId << workerIdShift) | sequence;
        return new Result(id, Status.SUCCESS);

    }

    /**
     * 返回大于 lastTimestamp 的当前时间
     *
     * @param lastTimestamp
     * @return
     */
    protected long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    /**
     * 方法保护，防止篡改
     * @return
     */
    protected long timeGen() {
        return System.currentTimeMillis();
    }

    public long getWorkerId() {
        return workerId;
    }

}
