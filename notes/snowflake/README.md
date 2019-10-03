
### ID 设计
　　以划分命名空间来生成 ID 的一种算法，即把 64 位的 ID 划分成多段，如下分别表示时间戳（41 位）、workerId（10 位）、序列号（12 位），位数可自行调整：

![avatar](photo_1.png)

- 第一位为 0；
- 第 2- 42 位为相对时间戳，即用当前时间戳减去一个固定的历史时间戳生成；
- 第 43-52 位为机器号 workerId，每个机器的 ID 不同；
- 第 53-64 位为自增 ID。

#### 优点

- 毫秒数在高位，自增序列在低位，整个 ID 都是趋势递增的；
- 不依赖数据库第三方系统，以服务方式部署，即本机可自行生成 ID，稳定性高；
- 可根据自身业务特性分配 bit 位，比如将机器位的 10-bit 分 5-bit 给 IDC，分 5-bit 给机器，这样就可以表示 32 个IDC，每个 IDC 下可以有 32 台机器。

#### 缺点

- 强依赖机器时钟，如果机器上时钟回拨，会导致发号重复或者服务会处于不可用状态。比如今天 10:05 分发过一次号，如果时间又倒回 10:05 分，则发的号会重复。

### SnowflakeService
　　Leaf-snowflake 服务启动：

- 解析配置文件，获取 zookeeper 地址和 snowflake 端口号；
- 根据 zookeeper 地址和 snowflake 端口调用 SnowflakeIDGenImpl 启动 snowflake 服务；

```java
    public SnowflakeService() throws InitException {
        Properties properties = PropertyFactory.getProperties();
        // 解析参数 leaf.snowflake.enable，是否开启 snowflake 算法
        boolean flag = Boolean.parseBoolean(properties.getProperty(Constants.LEAF_SNOWFLAKE_ENABLE, "true"));

        if (flag) {
            // 解析参数，获取 zookeeper 地址，比如 "192.168.1.159:2100,192.168.1.159:2101,192.168.1.159:2102"
            String zkAddress = properties.getProperty(Constants.LEAF_SNOWFLAKE_ZK_ADDRESS);
            // 解析参数，获取 snowflake 端口
            int port = Integer.parseInt(properties.getProperty(Constants.LEAF_SNOWFLAKE_PORT));
            // 根据 zookeeper 地址和 snowflake 端口生成
            idGen = new SnowflakeIDGenImpl(zkAddress, port);
            // snowflake 初始化成功
            if(idGen.init()) {
                logger.info("Snowflake Service Init Successfully");
            } else {
                throw new InitException("Snowflake Service Init Fail");
            }
        } else {
            // 不开启 snowflake 算法，则返回 ID 为 0
            idGen = new ZeroIDGen();
            logger.info("Zero ID Gen Service Init Successfully");
        }
    }
```

### [SnowflakeIDGenImpl#init](https://github.com/martin-1992/Leaf/blob/master/notes/snowflake/SnowflakeZookeeperHolder%23init.md)

- 创建对象 SnowflakeZookeeperHolder，包含 IP、端口、zookeeper 地址；
- 核心是调用该对象 SnowflakeZookeeperHolder 的初始化 init 方法。
    1. 每当 leaf-snowflake 服务启动后，都会创建一个 zookeeper 连接实例，连接到 zookeeper，然后先检查 zookeeper 的根节点是否创建；
    2. 没则创建父节点 leaf_forever，同时在父节点下创建一个永久顺序节点 workerId（ID 号是顺序生成的），为本次连接的 leaf-snowflake 所属 ID 号，将 workerId 持久化到本地，重启时可直接获取；
    3. 有创建父节点的情况下，则先获取该父节点下的所有子节点存储到 Map 中，然后根据该 leaf-snowflake 服务的监听地址，判断 map 中是否也有注册的 workerId，有则取回该 workerId，启动服务。没则在父节点下创建一个永久顺序节点 workerId，启动服务。

```java
    public SnowflakeIDGenImpl(String zkAddress, int port) {
        this.port = port;
        // 创建对象
        SnowflakeZookeeperHolder holder = new SnowflakeZookeeperHolder(Utils.getIp(), String.valueOf(port), zkAddress);
        // 初始化
        initFlag = holder.init();
        // ...
    }
```
### [解决机器时间回拨问题](https://github.com/martin-1992/Leaf/blob/master/notes/snowflake/%E8%A7%A3%E5%86%B3%E6%9C%BA%E5%99%A8%E6%97%B6%E9%97%B4%E5%9B%9E%E6%8B%A8%E9%97%AE%E9%A2%98.md)
　　snowflake 算法的第 2- 42 位为相对时间戳，依赖时间来产生 ID 的，如果机器时间发生回拨，则可能会发出重复的 ID 号，所以在发 ID 号前需检查机器时间有没回拨，有三种检查：

- 当 leaf-snowflake 服务连接 zookeeper 时，发现父节点已存在，同时在父节点下找到该服务的子节点和 workerId，则获取该 workerId，调用 checkInitTimeStamp 方法，检查当前服务的机器时间是否小于该节点最近一次上报时间，是则抛出异常；
- 在获取 ID 时会先判断机器时间是否发生回拨，检查服务的机器时间是否小于该节点最近一次上报时间；
- 如果是新服务节点，直接创建持久节点 leaf_forever/{self} 并写入自身系统时间，接下来综合对比其余 Leaf 节点的系统时间来判断自身系统时间是否准确，具体做法是取 leaf_temporary 下的所有临时节点（所有运行中的 Leaf-snowflake 节点）的服务 IP：Port，然后通过 RPC 请求得到所有节点的系统时间，计算 sum(time)/ nodeSize（注意，这段来自[美团的原文](https://tech.meituan.com/2017/04/21/mt-leaf.html)，在代码里没找到该实现方法）。
    1. 若 abs ( 系统时间 - sum(time) / nodeSize ) < 阈值，认为当前系统时间准确，正常启动服务，同时写临时节点 leaf_temporary/{self} 维持租约；
    2. 否则认为本机系统时间发生大步长偏移，启动失败并报警。
