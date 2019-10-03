
### SegmentIDGenImpl#init
　　初始化有两步：

- 将数据库中所有的业务 tag 加载到缓存中，并且删除缓存中没用的业务 tag，这时才算初始化成功；
- 创建一个线程，每隔 60 秒执行 updateCacheFromDb，将数据库新加的 key 添加到缓存中。

```java
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
```

### SegmentIDGenImpl#updateCacheFromDb

- 为数据库中每个业务 key（tag），初始建立双 buffer（buffer 用于发号），并添加到缓存 map 中；
- 遍历检查，将不存在于数据库的缓存业务 key 删去。

```java
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
```

### SegmentBuffer
　　构造函数，初始 Segment 数组为 2，创建双 buffer，另一个 buffer 会使用线程池异步调用。当前 buffer 的号码发完，就会调用另外一个 buffer 来发号，保证有一个 buffer 准备好发号。

```java
    public SegmentBuffer() {
        segments = new Segment[]{new Segment(this), new Segment(this)};
        // 指向 buffer 的指针，0 或 1，因为只有两个 buffer
        currentPos = 0;
        // 创建线程，异步调用另一个 buffer，是否已从数据库配置好，可进行发号
        initOk = false;
        threadRunning = new AtomicBoolean(false);
        lock = new ReentrantReadWriteLock();
    }
```

### SegmentIDGenImpl#updateCacheFromDbAtEveryMinute
　　创建一个核心为 1 的线程池，定时执行 updateCacheFromDb 方法，即每隔 60 秒将数据库中新加的业务 key 初始化双 buffer，并添加到缓存，同时删去没用的 key。

```java
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
```
