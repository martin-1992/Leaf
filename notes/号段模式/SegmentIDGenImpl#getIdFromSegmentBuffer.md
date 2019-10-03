
### SegmentIDGenImpl#getIdFromSegmentBuffer
　　发号和准备另一个 buffer，当前 buffer 发号完了，就切换到另一个 buffer 进行发号。

- 上读锁，获取当前 Segment，判断另外一个 buffer 是否准备好，满足下面条件，则创建另外一个线程对另一个 buffer 进行配置；
    1. 另一个 buffer 号段没准备好；
    2. 当前 buffer 号段发号已超过 10%；
    3. 使用 CAS，判断 buffer 是否已经启动另一个线程，false 表示没启动；
- 调用 [updateSegmentFromDb]()，从数据库配置更新 buffer 的当前 ID 值、最大 ID 值，以及动态调整步长；
- 上写锁（注意写锁和读锁不是同一个线程，写锁是另起的线程，因为读写锁不允许锁升级，即读锁升级为写锁），将当前 buffer 属性 nextReady 设置为 true，表示另外一个 buffer 从数据库加载好配置了；
- 当前 buffer 进行发号，使用原子类，保证线程安全。同时对发号 ID 进行判断，如果当前 ID 值小于最大 ID 值，表示号没发完，返回该发号 ID 值；
- 如果发号完了，即当前 buffer 的 ID 值大于最大 ID 值，则调用 waitAndSleep 判断另外一个 buffer 是否已配置好；
- 当前 buffer 上写锁，因为当前 buffer 号段发完了，于是要切换另一个 buffer；
- 先判断 buffer 号段的值是否小于最大 ID 值，在多线程情况下，可能已经切换到另一个 buffer，所以先上锁判断该 buffer 是否发完号了；
- 另一个 buffer 已准备好，将指向当前 buffer 的指针改为指向另一个 buffer 的指针。

```java
    /**
     * 创建核心线程为 5 的线程池，用于执行双 buffer 中的另外一个 buffer
     */
    private ExecutorService service = new ThreadPoolExecutor(5, Integer.MAX_VALUE, 60L,
            TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), new UpdateThreadFactory());
     
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
            // 当发号完了，先判断另一个 buffer 是否已配置好
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
    
    // 使用当前指针 + 1 整除 2 获取余数，来获取下个指针
    public int nextPos() {
        return (currentPos + 1) % 2;
    }
```

### SegmentIDGenImpl#waitAndSleep
　　获取当前 buffer 的 threadRunning 属性，为 true，表示有线程正在配置另一个 buffer，则循环计数等待，直到超时异常。为 false，表示已经配置好另个一 buffer。

```java
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
```
