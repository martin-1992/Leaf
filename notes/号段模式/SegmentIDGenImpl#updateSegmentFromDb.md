
### SegmentIDGenImpl#updateSegmentFromDb
　　从数据库配置更新 buffer 的当前 ID 值、最大 ID 值和步长。当第三次及以后调用，会有更新时间戳，使用当前时间减去更新时间戳的时间差，来动态调整步长 step，即增加或减少发号 ID 数，目的是降低数据库的访问频率，因为取号是从数据库取的。虽然能动态调整步长，但仍无法面对瞬时暴增的情况。

- 时间差小于 15 分钟，且没超过最大步长 MAX_STEP，则调整步长 step = step * 2；
- 时间差在 15 到 30 分钟，则不需进行调整；
- 时间差大于 15 分钟，且步长除以 2 仍大于最小步长，则调整步长 step = step / 2；

```java
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
```
