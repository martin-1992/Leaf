### 介绍
　　本项目为美团的 Leaf 框架的中文注释版，包含 Leaf 框架的代码注释及笔记，美团 Leaf 有两种 ID 生成模式：号段模式、snowflake 模式。

### 使用场景

- 分布式；
- 分库分表时，保证全局唯一性；

### ID 生成方法

- **UUID；**
  1. 生成的 ID 无序，InnoDB 存储使用 B+ 树存储索引数据是有序的，主键 ID 在 InnoDB 也是有序排列。但因为生成的 ID 无序，比如前一个 ID 值是 23，后一个 ID 值是 12，则需要找到插入位置插入，有多余的数据移动开销；
  2. 因为是无序的，机器磁盘在随机写时，要先做 “寻道” 找到要写入的位置，即让磁头找到对应的磁道。而顺序写就不需要寻道，提升写入性能；
  3. 生成 ID 太长，存储消耗空间大。
- **数据库自增 ID；**
  1. 在分布式下，无法保证全局唯一性；
  2. ID 可计算，不适用于订单场景；
  3. 发号性能受数据库性能限制。
- **snowflake；**
  1. 全局唯一；
  2. 单调递增；
  3. 不可计算，可用于订单场景；
  4. 具有一定业务含义，比如前面几十位为时间戳；
- **leaf-segment；**
  1. 支持分布式，保证全局唯一性；
  2. 动态获取批量 ID 号；
  3. 数据库挂了，还可以支撑一段时间发号；
  4. ID 可计算，不适用于订单场景。
- **微信序列号生成器。**

### [号段模式](https://github.com/martin-1992/Leaf/blob/master/notes/%E5%8F%B7%E6%AE%B5%E6%A8%A1%E5%BC%8F/README.md)
　　低位趋势增长，高可用，采用从数据库获取发号的起始 ID 值、最大 ID 值，发号是在本机进行，即使数据库宕机，也能保证一段时间内正常发号，ID 可计算，不适用于订单 ID 生成场景。

![avatar](/notes/photo_2.png)
  
- 先判断初始化是否成功，在 [SegmentIDGenImpl#init](https://github.com/martin-1992/Leaf/blob/master/notes/%E5%8F%B7%E6%AE%B5%E6%A8%A1%E5%BC%8F/SegmentIDGenImpl%23init.md) 方法中初始化包括将数据库中的业务 key 添加到缓存中、删去缓存中没用的业务 key 和创建一个线程定时添加新的业务 key 到缓存中；
- 判断缓存（多线程下使用 ConcurrentHashMap）是否包含该业务 key，不包含则抛出异常；
- 使用双重检查锁，检查 SegmentBuffer 是否已用构造函数创建，并进行零值初始化；
  1. SegmentBuffer 为包含两个 Segment 对象的数组，Segment 对象属性有原子类的 value，用来获取 ID 值。使用 volatile 修饰的最大 ID 值 max，用于当前 ID 值是否小于最大 ID 值。step 为步长，当前最大 ID 值 max 减去 step 步长，即为初始的 ID 值；
  2. SegmentBuffer 的 currentPos 属性指向下个 buffer，切换时使用 (currentPos + 1) % 2；
- 调用 [updateSegmentFromDb](https://github.com/martin-1992/Leaf/blob/master/notes/%E5%8F%B7%E6%AE%B5%E6%A8%A1%E5%BC%8F/SegmentIDGenImpl%23updateSegmentFromDb.md)，从数据库配置更新 buffer 的当前 ID 值、最大 ID 值，以及动态调整步长；
  1. 假设数据库初始状态，maxId=0，step=1000，
  2. 机器 A 和机器 B 要获取同个业务 key 的 ID（业务 key 缓存在本地），同个业务 key 获取 ID 的服务需要先获取锁（synchronized (buffer)），然后机器 B 抢到了锁；
  3. 机器 B 会检查本机号段 buffer 是否更新，没更新先让数据库更新，即 maxId += step = 1000，则机器 B 的 maxId=1000，step=1000，初始发号 ID 值为 maxId-step=0，所以从 0 发起；
  4. 当机器 B 的 buffer 配置好后，会解锁。然后机器 A 获取锁，也是重复上面流程，先从数据库更新 maxId += step，maxId 为 2000。机器 A 在从数据库中获取到的 maxId 为 2000，当前发号的初始 ID 值为 maxId-step=2000-1000=1000，即从 1000 开始发起。
- 调用 [getIdFromSegmentBuffer](https://github.com/martin-1992/Leaf/blob/master/notes/%E5%8F%B7%E6%AE%B5%E6%A8%A1%E5%BC%8F/SegmentIDGenImpl%23getIdFromSegmentBuffer.md)，当本机 buffer 从数据库中获取到发号配置后，进行发放 ID 和准备另一个 buffer。当前 buffer 发号完了，就切换到另一个 buffer 进行发号。
  1. 自旋操作（while true），当前业务 key 上读锁，表示多个线程可进行读取；
  2. 接着判断当前 buffer 是否发号超过 10%（id、maxId、step 都用 volatile 修饰，保证了多线程下的可见性），且另一个 buffer（volatile 修饰） 没准备好，则使用 CAS 启动另外一个线程加载另外一个 buffer，还是调用 [updateSegmentFromDb](https://github.com/martin-1992/Leaf/blob/master/notes/%E5%8F%B7%E6%AE%B5%E6%A8%A1%E5%BC%8F/SegmentIDGenImpl%23updateSegmentFromDb.md) 方法，从数据库加载配置到本地；
  3. 同时继续发号，发号 ID 为原子类 AtomicInteger，对于简单的加一操作，使用锁来操作太笨重，线程切换消耗的时间可能大于 CAS 操作（无锁，加一操作失败，则自旋重试，直到完成加一操作）。发号 ID 自增完成后，检查是否超过最大 ID，没有则发出 ID 号（成功），解除读锁；
  4. 如果当前 ID 值大于最大 ID 值，表示发号完了，先判断 buffer 的另外一个线程是否已配置好另一个 buffer，会使用计数等待一段时间，再规定时间没配置好，则超时；
  5. 上写锁，用于切换 buffer。先判断 buffer 的发号 id 是不是大于 maxId（多线程情况下，可能已经切换到另外一个 buffer），是则进行切换，不是则直接发号；
  6. 使用 (currentPos + 1) % 2 切换 buffer，currentPos 为指向当前 buffer 的指针，最后解掉写锁；
  7. 在第三次及以后调用 [updateSegmentFromDb](https://github.com/martin-1992/Leaf/blob/master/notes/%E5%8F%B7%E6%AE%B5%E6%A8%A1%E5%BC%8F/SegmentIDGenImpl%23updateSegmentFromDb.md) 会根据发完一个 buffer 的 ID 号耗时时长来动态调整 step，即本次获取的 ID 号。当前时间减去上次 buffer 的更新时间，即为上次 buffer 发号完的耗时。
    - 默认 15 分钟，少于 15 分钟，但大于最低 1000 秒，则将步长 step * 2；
    - 15 ~ 30 分钟，不用调整步长 step；
    - 大于 30 分钟，步长 step 减少一半。

![avatar](/notes/photo_1.png)

### [snowflake 模式](https://github.com/martin-1992/Leaf/blob/master/notes/snowflake/README.md)
　　完全分布式，ID 不可计算，可适用于订单 ID 生成场景。<br />

- 创建对象 SnowflakeZookeeperHolder，包含 IP、端口、zookeeper 地址；
- 核心是调用该对象 SnowflakeZookeeperHolder 的初始化 init 方法，获取 workerId，因为 snowflake 的 ID 是由时间戳 + 工作机器 ID（workerId）+ 序列号组成的。第一位是符号位，为 0 表示正数；
    1. 每当 leaf-snowflake 服务启动后，都会创建一个 zookeeper 连接实例，连接到 zookeeper，然后先检查 zookeeper 的根节点是否创建；
    2. 没则创建父节点 leaf_forever，同时在父节点下创建一个临时顺序节点 workerId（ID 号是顺序生成的），为本次连接的 leaf-snowflake 所属 ID 号，将 workerId 持久化到本地，重启时可直接获取；
    3. 有创建父节点的情况下，则先获取该父节点下的所有子节点存储到 Map 中，然后根据该 leaf-snowflake 服务的监听地址，判断 map 中是否也有注册的 workerId，有则取回该 workerId，启动服务。没则在父节点下创建一个临时顺序节点 workerId，启动服务。

![avatar](/notes/photo_4.png)

#### [解决机器时间回拨问题](https://github.com/martin-1992/Leaf/blob/master/notes/snowflake/%E8%A7%A3%E5%86%B3%E6%9C%BA%E5%99%A8%E6%97%B6%E9%97%B4%E5%9B%9E%E6%8B%A8%E9%97%AE%E9%A2%98.md)
　　snowflake 算法的第 2- 42 位为相对时间戳，依赖时间来产生 ID 的，如果机器时间发生回拨，则可能会发出重复的 ID 号，所以在发 ID 号前需检查机器时间有没回拨，有三种检查：

- 当 leaf-snowflake 服务连接 zookeeper 时，发现父节点已存在，同时在父节点下找到该服务的子节点和 workerId，则获取该 workerId，调用 checkInitTimeStamp 方法，检查当前服务的机器时间是否小于该节点最近一次上报时间，是则抛出异常；
- 在获取 ID 时会先判断机器时间是否发生回拨，检查服务的机器时间是否小于该节点最近一次上报时间；
- 如果是新服务节点，直接创建临时顺序节点 leaf_forever/{self} 并写入自身系统时间，接下来综合对比其余 Leaf 节点的系统时间来判断自身系统时间是否准确，具体做法是取 leaf_temporary 下的所有临时节点（所有运行中的 Leaf-snowflake 节点）的服务 IP：Port，然后通过 RPC 请求得到所有节点的系统时间，计算 sum(time)/ nodeSize（注意，这段来自[美团的原文](https://tech.meituan.com/2017/04/21/mt-leaf.html)，在代码里没找到该实现方法）。
    1. 若 abs ( 系统时间 - sum(time) / nodeSize ) < 阈值，认为当前系统时间准确，正常启动服务，同时写临时节点 leaf_temporary/{self} 维持租约；
    2. 否则认为本机系统时间发生大步长偏移，启动失败并报警。
 
 ### leaf-snowflake 两种部署方式
 
 #### 发号器服务
 　　把 snowflake 打包成服务，单独部署，发号的机器 workerId 写在配置文件中。比如以主备方式部署，这样只需要一台机器，可以减少发号机器 workderId 的位数，增加自增的位数。调用时通过内网调用，只是多一次网络调用。调用的，最简单就是 http，或使用 RPC 框架。

 - 将 leaf-core 打包成服务，部署到多台机器；
 - 连接 ZooKeeper，将各自服务注册到 ZooKeeper 集群上；
 - 消费者连接 ZooKeeper 集群，获取对应的提供服务列表；
 - 然后消费者根据负载均衡策略，连接一个 IP，获取服务。
 
 ![avatar](/notes/photo_3.png)
 
 　　注意，即使 ZooKeeper 挂掉了，消费者一样可以获取服务，因为消费者已缓存提供服务的列表。ZooKeeper 有心跳检测，当提供者的机器挂掉，会更新提供服务的列表，并推给各消费者。如果 ZooKeeper 挂了，且提供者的机器挂了，消费者才会获取不到服务。ZooKeeper 一般是以集群方式存在，不容易挂掉。

#### 本机部署
 　　即写在业务代码中，比如写成工具类，然后直接调用，省去一次网络调用，但需要预留更多的机器 ID 位置。

#### 注意事项
 　　如果发号服务的 QPS 不高，比如发号服务每毫秒只发一个 ID，这样生成的 ID 末位就都是 1。分库分表使用该 ID 进行一致性哈希时，会造成分库分表的不均匀。解决方案：

- 把时间位设置少点，时间戳记录到秒，同一个时间区可以多发几个号，避免造成分库分表的不均匀；
- 在自增 ID 位的起始做下随机，比如 000001 随机成 560001。

### reference

- [Leaf：美团分布式ID生成服务开源](https://tech.meituan.com/2019/03/07/open-source-project-leaf.html)；
- [Leaf——美团点评分布式ID生成系统](https://tech.meituan.com/2017/04/21/mt-leaf.html)
