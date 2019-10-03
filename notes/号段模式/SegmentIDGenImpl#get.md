
### SegmentIDGenImpl#get

- 先判断初始化是否成功，在 [SegmentIDGenImpl#init](https://github.com/martin-1992/Leaf/blob/master/notes/%E5%8F%B7%E6%AE%B5%E6%A8%A1%E5%BC%8F/SegmentIDGenImpl%23init.md) 方法中初始化包括将数据库中的业务 key 添加到缓存中、删去缓存中没用的业务 key 和创建一个线程定时添加新的业务 key 到缓存中；
- 判断缓存（多线程下使用 ConcurrentHashMap）是否包含该业务 key，不包含则抛出异常；
- 使用双重检查锁，检查 SegmentBuffer 是否已用构造函数创建，并进行零值初始化；
- 调用 [updateSegmentFromDb](https://github.com/martin-1992/Leaf/blob/master/notes/%E5%8F%B7%E6%AE%B5%E6%A8%A1%E5%BC%8F/SegmentIDGenImpl%23updateSegmentFromDb.md)，从数据库配置更新 buffer 的当前 ID 值、最大 ID 值，以及动态调整步长；
- 调用 [getIdFromSegmentBuffer](https://github.com/martin-1992/Leaf/blob/master/notes/%E5%8F%B7%E6%AE%B5%E6%A8%A1%E5%BC%8F/SegmentIDGenImpl%23getIdFromSegmentBuffer.md)，发号和准备另一个 buffer，当前 buffer 发号完了，就切换到另一个 buffer 进行发号。


```java
    /**
     * 初始化是否成功标记
     */
    private volatile boolean initOK = false;

    /**
     * 存储每个业务 key 的双 buffer，存储所有业务key对应双buffer号段，所以是基于内存的发号方式
     */
    private Map<String, SegmentBuffer> cache = new ConcurrentHashMap<String, SegmentBuffer>();


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
```
