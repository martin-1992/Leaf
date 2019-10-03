package com.sankuai.inf.leaf.segment;

import com.sankuai.inf.leaf.IDGen;
import com.sankuai.inf.leaf.common.Result;
import com.sankuai.inf.leaf.common.Status;
import com.sankuai.inf.leaf.segment.dao.IDAllocDao;
import com.sankuai.inf.leaf.segment.model.*;
import org.perf4j.StopWatch;
import org.perf4j.slf4j.Slf4JStopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class SegmentIDGenImpl implements IDGen {
    private static final Logger logger = LoggerFactory.getLogger(SegmentIDGenImpl.class);

    /**
     * IDCache 未初始化成功时的异常码
     */
    private static final long EXCEPTION_ID_IDCACHE_INIT_FALSE = -1;

    /**
     * key 不存在时的异常码
     */
    private static final long EXCEPTION_ID_KEY_NOT_EXISTS = -2;

    /**
     * SegmentBuffer 中的两个 Segment 均未从 DB 中装载时的异常码
     */
    private static final long EXCEPTION_ID_TWO_SEGMENTS_ARE_NULL = -3;

    /**
     * 最大步长不超过 100,0000
     */
    private static final int MAX_STEP = 1000000;

    /**
     * 一个 Segment 维持时间为15分钟
     */
    private static final long SEGMENT_DURATION = 15 * 60 * 1000L;

    /**
     * 创建核心线程为 5 的线程池，用于执行双 buffer 中的另外一个 buffer
     */
    private ExecutorService service = new ThreadPoolExecutor(5, Integer.MAX_VALUE, 60L,
            TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), new UpdateThreadFactory());

    /**
     * 初始化是否成功标记
     */
    private volatile boolean initOK = false;

    /**
     * 存储每个业务 key 的双 buffer，存储所有业务key对应双buffer号段，所以是基于内存的发号方式
     */
    private Map<String, SegmentBuffer> cache = new ConcurrentHashMap<String, SegmentBuffer>();

    private IDAllocDao dao;

    /**
     * 线程工厂，返回一个线程，通过继承 ThreadFactory 接口，对线程名重命名
     */
    public static class UpdateThreadFactory implements ThreadFactory {

        private static int threadInitNumber = 0;

        private static synchronized int nextThreadNum() {
            return threadInitNumber++;
        }

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "Thread-Segment-Update-" + nextThreadNum());
        }
    }

    @Override
    public boolean init() {
        logger.info("Init ...");
        // 将数据库中所有的业务 tag 加载到缓存中，并且删除缓存中没用的业务 tag，
        // 这时才算初始化成功
        updateCacheFromDb();
        initOK = true;
        // 创建一个线程，每隔 60 秒执行 updateCacheFromDb，将数据库新加的 key 添加到缓存中
        updateCacheFromDbAtEveryMinute();
        return initOK;
    }

    /**
     * 创建一个核心为 1 的线程池，定时执行 updateCacheFromDb 方法，即每隔 60 秒将
     * 数据库中新加的业务 key 初始化双 buffer，并添加到缓存，同时删去没用的 key
     */
    private void updateCacheFromDbAtEveryMinute() {
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("check-idCache-thread");
                t.setDaemon(true);
                return t;
            }
        });
        service.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                updateCacheFromDb();
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 1. 为数据库中每个业务 key（tag），初始双 buffer，并添加到缓存 map 中；
     * 2. 遍历检查，将不存在于数据库的缓存 key 删去。
     */
    private void updateCacheFromDb() {
        logger.info("update cache from db");
        StopWatch sw = new Slf4JStopWatch();
        try {
            // 获取数据库中所有的业务 key，即 biz_tag
            List<String> dbTags = dao.getAllTags();
            if (dbTags == null || dbTags.isEmpty()) {
                return;
            }
            List<String> cacheTags = new ArrayList<String>(cache.keySet());
            List<String> insertTags = new ArrayList<String>(dbTags);
            List<String> removeTags = new ArrayList<String>(cacheTags);

            // db 中新加的 tags 灌进 cache
            // 将缓存中已存在的 tag 从 dbTage 中移除，添加新的 tag 和初
            // 始化对应的双 buffer 到缓存中
            insertTags.removeAll(cacheTags);
            for (String tag : insertTags) {
                SegmentBuffer buffer = new SegmentBuffer();
                buffer.setKey(tag);
                // 默认获取第一个 buffer
                Segment segment = buffer.getCurrent();
                segment.setValue(new AtomicLong(0));
                segment.setMax(0);
                segment.setStep(0);
                cache.put(tag, buffer);
                logger.info("Add tag {} from db to IdCache, SegmentBuffer {}", tag, buffer);
            }
            // cache中已失效的 tags 从 cache 删除，遍历 dbTags，将不
            // 存在于数据库中 的 tag 从缓存中移除
            removeTags.removeAll(dbTags);
            for (String tag : removeTags) {
                cache.remove(tag);
                logger.info("Remove tag {} from IdCache", tag);
            }
        } catch (Exception e) {
            logger.warn("update cache from db exception", e);
        } finally {
            sw.stop("updateCacheFromDb");
        }
    }

    @Override
    public Result get(final String key) {
        // 先检查初始化是否成功，初始化包含：将数据库中的业务 key 添加到缓存中、
        // 删去缓存中没用的业务 key 和创建一个线程定时添加新的业务 key 到缓存中
        if (!initOK) {
            return new Result(EXCEPTION_ID_IDCACHE_INIT_FALSE, Status.EXCEPTION);
        }
        // 如果该缓存包含业务 key
        if (cache.containsKey(key)) {
            SegmentBuffer buffer = cache.get(key);
            // 使用双重检查锁，检查 SegmentBuffer 是否已用构造函数创建，并进行零值初始化
            if (!buffer.isInitOk()) {
                synchronized (buffer) {
                    if (!buffer.isInitOk()) {
                        try {
                            // 从数据库配置更新 buffer 的当前 ID 值、最大 ID 值，以及动态调整步长
                            updateSegmentFromDb(key, buffer.getCurrent());
                            logger.info("Init buffer. Update leafkey {} {} from db", key, buffer.getCurrent());
                            buffer.setInitOk(true);
                        } catch (Exception e) {
                            logger.warn("Init buffer {} exception", buffer.getCurrent(), e);
                        }
                    }
                }
            }
            // 发号和准备另一个 buffer，当前 buffer 发号完了，就切换到另一个 buffer 进行发号
            return getIdFromSegmentBuffer(cache.get(key));
        }
        // 缓存不包含业务 key，返回异常错误
        return new Result(EXCEPTION_ID_KEY_NOT_EXISTS, Status.EXCEPTION);
    }

    /**
     * 从数据库配置更新 buffer 的当前 ID 值、最大 ID 值和步长。当第三次及以后调用，
     * 会有更新时间戳，使用当前时间减去更新时间戳，来动态调整步长 step，即增加或减少
     * 发号 ID 数，目的是降低数据库的访问频率，因为取号是从数据库取的
     *
     * @param key 业务 key
     * @param segment ID 号段
     */
    public void updateSegmentFromDb(String key, Segment segment) {
        StopWatch sw = new Slf4JStopWatch();
        // 获取 buffer
        SegmentBuffer buffer = segment.getBuffer();
        LeafAlloc leafAlloc;
        // buffer 为 false，表示还没初始化，进行第一次初始化
        if (!buffer.isInitOk()) {
            // 从数据库更新该业务 key 的 maxId
            leafAlloc = dao.updateMaxIdAndGetLeafAlloc(key);
            // 获取数据库中已配置好的 step
            buffer.setStep(leafAlloc.getStep());
            // leafAlloc 中的 step 为 DB 中的 step
            buffer.setMinStep(leafAlloc.getStep());
        } else if (buffer.getUpdateTimestamp() == 0) {
            // buffer 的更新时间戳为 0，表示为第二次调用 updateSegmentFromDb，
            // 同上更新该业务 key 的 maxId
            leafAlloc = dao.updateMaxIdAndGetLeafAlloc(key);
            // 更新 buffer 当前时间
            buffer.setUpdateTimestamp(System.currentTimeMillis());
            // leafAlloc 中的 step 为 DB 中的 step
            buffer.setMinStep(leafAlloc.getStep());
        } else {
            // 第三次及之后调用 updateSegmentFromDb，动态调整步长 step，即增加或减少发号 ID 数，
            // 目的是降低数据库的访问频率，因为取号是从数据库取的
            long duration = System.currentTimeMillis() - buffer.getUpdateTimestamp();
            int nextStep = buffer.getStep();
            // 表示现在的步长太小，在 SEGMENT_DURATION（默认 15 分钟）内发完，
            // 将步长乘以 2，即发号的数量增加，但不能超过 MAX_STEP
            if (duration < SEGMENT_DURATION) {
                if (nextStep * 2 > MAX_STEP) {
                    //do nothing
                } else {
                    nextStep = nextStep * 2;
                }
            } else if (duration < SEGMENT_DURATION * 2) {
                // 在 15 ~ 30 分钟则不用
                //do nothing with nextStep
            } else {
                // 大于 30 分钟，则将步长减少一半
                nextStep = nextStep / 2 >= buffer.getMinStep() ? nextStep / 2 : nextStep;
            }
            logger.info("leafKey[{}], step[{}], duration[{}mins], nextStep[{}]", key, buffer.getStep(), String.format("%.2f",((double)duration / (1000 * 60))), nextStep);
            LeafAlloc temp = new LeafAlloc();
            temp.setKey(key);
            temp.setStep(nextStep);
            // 更新数据库的 maxId
            leafAlloc = dao.updateMaxIdByCustomStepAndGetLeafAlloc(temp);
            buffer.setUpdateTimestamp(System.currentTimeMillis());
            // 设置动态调整好的步长（根据发号消耗完的时间调整步长大小）
            buffer.setStep(nextStep);
            // leafAlloc 的 step 为 DB 中的 step
            buffer.setMinStep(leafAlloc.getStep());
        }
        // must set value before set max
        // 设置当前号段的值，包括 ID 值、最大 ID、步长等
        long value = leafAlloc.getMaxId() - buffer.getStep();
        segment.getValue().set(value);
        segment.setMax(leafAlloc.getMaxId());
        segment.setStep(buffer.getStep());
        sw.stop("updateSegmentFromDb", key + " " + segment);
    }

    /**
     * 发号和准备另一个 buffer，当前 buffer 发号完了，就切换到另一个 buffer 进行发号
     *
     * @param buffer
     * @return
     */
    public Result getIdFromSegmentBuffer(final SegmentBuffer buffer) {
        // 使用自旋，当前 buffer 号发完就会切换到另一个 buffer，然后在判断一次，获取 ID 值
        while (true) {
            try {
                // 读锁
                buffer.rLock().lock();
                // 获取当前 Segment，判断另外一个 buffer 是否准备好
                final Segment segment = buffer.getCurrent();
                // 如果下面条件满足，则创建另外一个线程配置好另一个 buffer 的当前 ID 值、最大 ID 值和步长
                // 另一个 buffer 号段没准备好；
                // 当前 buffer 号段发号已超过 10%；
                // 使用 CAS，判断 buffer 是否已经启动另一个线程，false 表示没启动
                if (!buffer.isNextReady() && (segment.getIdle() < 0.9 * segment.getStep()) && buffer.getThreadRunning().compareAndSet(false, true)) {
                    service.execute(new Runnable() {
                        @Override
                        public void run() {
                            // buffer 数组中 nextPos，对应另一个 buffer
                            Segment next = buffer.getSegments()[buffer.nextPos()];
                            boolean updateOk = false;
                            try {
                                // 从数据库配置更新 buffer 的当前 ID 值、最大 ID 值，以及动态调整步长
                                updateSegmentFromDb(buffer.getKey(), next);
                                updateOk = true;
                                logger.info("update segment {} from db {}", buffer.getKey(), next);
                            } catch (Exception e) {
                                logger.warn(buffer.getKey() + " updateSegmentFromDb exception", e);
                            } finally {
                                if (updateOk) {
                                    // 写锁，这是线程池创建另外一个线程去获取写锁，因为
                                    // 读写锁不允许锁升级，即获取读锁后在获取写锁
                                    buffer.wLock().lock();
                                    // buffer 配置好，则设为 true
                                    buffer.setNextReady(true);
                                    // 线程是否在运行中，false
                                    buffer.getThreadRunning().set(false);
                                    buffer.wLock().unlock();
                                } else {
                                    buffer.getThreadRunning().set(false);
                                }
                            }
                        }
                    });
                }
                // 发号
                long value = segment.getValue().getAndIncrement();
                // 如果当前 ID 值小于最大 ID 值，表示号没发完
                if (value < segment.getMax()) {
                    return new Result(value, Status.SUCCESS);
                }
            } finally {
                buffer.rLock().unlock();
            }
            // 当发号完了，先判断 buffer 的另外一个线程是否已配置好另一个 buffer
            waitAndSleep(buffer);
            try {
                // 当前 buffer 上写锁，因为当前 buffer 号段发完了，于是要切换另一个 buffer
                buffer.wLock().lock();
                // 获取当前 buffer 号段
                final Segment segment = buffer.getCurrent();
                // 先判断 buffer 号段的值是否小于最大 ID 值，在多线程情况下，可能已经切换到另一个
                // buffer，所以先上锁判断该 buffer 是否发完号了
                long value = segment.getValue().getAndIncrement();
                if (value < segment.getMax()) {
                    return new Result(value, Status.SUCCESS);
                }
                // 另一个 buffer 已准备好，将指向当前 buffer
                // 的指针改为指向另一个 buffer 的指针
                if (buffer.isNextReady()) {
                    buffer.switchPos();
                    buffer.setNextReady(false);
                } else {
                    // 另外一个 buffer 没准备好，则报异常
                    logger.error("Both two segments in {} are not ready!", buffer);
                    return new Result(EXCEPTION_ID_TWO_SEGMENTS_ARE_NULL, Status.EXCEPTION);
                }
            } finally {
                buffer.wLock().unlock();
            }
        }
    }

    /**
     * 获取当前 buffer 的 threadRunning 属性，为 true，表示有线程正在配置另一个 buffer，
     * 则循环计数等待，直到超时异常。为 false，表示已经配置好另个一 buffer。
     * @param buffer 当前发号号段
     */
    private void waitAndSleep(SegmentBuffer buffer) {
        int roll = 0;
        while (buffer.getThreadRunning().get()) {
            roll += 1;
            if(roll > 10000) {
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                    break;
                } catch (InterruptedException e) {
                    logger.warn("Thread {} Interrupted",Thread.currentThread().getName());
                    break;
                }
            }
        }
    }

    public List<LeafAlloc> getAllLeafAllocs() {
        return dao.getAllLeafAllocs();
    }

    public Map<String, SegmentBuffer> getCache() {
        return cache;
    }

    public IDAllocDao getDao() {
        return dao;
    }

    public void setDao(IDAllocDao dao) {
        this.dao = dao;
    }
}
