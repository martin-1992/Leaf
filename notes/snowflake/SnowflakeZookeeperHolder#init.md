
### SnowflakeZookeeperHolder#init
　　初始化流程：

- 基于配置信息，创建 zookeeper 的连接实例；
- 检查根节点 forever 是否存在；
- 不存在根节点；
    1. 创建根节点 forever，为第一次启动，创建永久顺序节点 workerID（zookeeper 顺序节点生成的 int 类型的 ID 号）, 并把节点数据放入 value；
    2. 持久化，将该端口对应的 workerID 写入文件系统中，重启可获取该 workerID，保证正常启动；
    3. 使用一个核心线程为 1 的线程池，该线程每 3s 将本机数据上报给根节点 forever，数据为由 ip、端口、本机时间戳组成的 json。
- 存在根节点；
    1. 获取根节点 forever 的所有子节点，存入 Map 中；
    2. 根据监听地址，从 Map 中获取节点；
    3. 监听地址有节点，则判断该节点的时间是否小于最后一次上报的时间，创建临时节点，workerId 持久化；
    4. 监听地址没有节点，无需判断节点时间，创建永久节点，workerId 持久化。

```java
    public boolean init() {
        try {
            // 基于 Apache Curator 框架的 ZooKeeper 的介绍 https://www.zifangsky.cn/1166.html
            // new RetryUntilElapsed(1000, 4)，调用默认的重试策略，重试的时间超过最大重试时间 1000 就不再重试，否则间隔 4 进行重试
            // 基于配置信息，创建连接实例
            CuratorFramework curator = createWithOptions(connectionString, new RetryUntilElapsed(1000, 4), 10000, 6000);
            curator.start();
            // 检查根节点 forever 是否存在
            Stat stat = curator.checkExists().forPath(PATH_FOREVER);
            if (stat == null) {
                // 不存在根节点,机器第一次启动,创建 /snowflake/ip:port-000000000,并上传数据
                // 创建持久顺序节点 ,并把节点数据放入 value
                zk_AddressNode = createNode(curator);
                // worker id 默认是 0
                updateLocalWorkerID(workerID);
                // 定时上报本机时间给 forever 节点
                ScheduledUploadData(curator, zk_AddressNode);
                return true;
            } else {
                // key 为 ip:port，value 为 00001，00001 表示 workderid
                Map<String, Integer> nodeMap = Maps.newHashMap();
                // key 为 ip:port，value 为 ipport-000001
                Map<String, String> realNode = Maps.newHashMap();
                // 存在根节点, 先检查是否有属于自己的根节点
                // 获取根节点 forever 的所有子节点
                List<String> keys = curator.getChildren().forPath(PATH_FOREVER);

                // 遍历，将子节点存入 map 中
                for (String key : keys) {
                    String[] nodeKey = key.split("-");
                    realNode.put(nodeKey[0], key);
                    // key 为 ip+port，value 为 workerid
                    nodeMap.put(nodeKey[0], Integer.parseInt(nodeKey[1]));
                }

                // 监听地址为当前 IP + 端口，判断当前地址是否有节点
                Integer workerid = nodeMap.get(listenAddress);
                if (workerid != null) {
                    // 有自己的节点, zk_AddressNode = ip: port
                    zk_AddressNode = PATH_FOREVER + "/" + realNode.get(listenAddress);
                    // 启动 worker 时使用会使用
                    workerID = workerid;
                    // 已经存在节点，则先检查，当前节点的时间是否小于最近一次上报时间，
                    // 是则表明机器时间已经回拨了，于是服务启动失败，抛出异常
                    if (!checkInitTimeStamp(curator, zk_AddressNode))
                        throw new CheckLastTimeException("init timestamp check error,forever node timestamp gt this node time");
                    // 准备创建临时节点
                    doService(curator);
                    // workerID 持久化
                    updateLocalWorkerID(workerID);
                    LOGGER.info("[Old NODE]find forever node have this endpoint ip-{} port-{} workid-{} childnode and start SUCCESS", ip, port, workerID);
                } else {
                    //表示新启动的节点,创建持久节点 ,不用check时间
                    String newNode = createNode(curator);
                    zk_AddressNode = newNode;
                    String[] nodeKey = newNode.split("-");
                    workerID = Integer.parseInt(nodeKey[1]);
                    // 创建永久节点
                    doService(curator);
                    // 持久化 workderId
                    updateLocalWorkerID(workerID);
                    LOGGER.info("[New NODE]can not find node on forever node that endpoint ip-{} port-{} workid-{},create own node on forever node and start SUCCESS ", ip, port, workerID);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Start node ERROR {}", e);
            try {
                Properties properties = new Properties();
                properties.load(new FileInputStream(new File(PROP_PATH.replace("{port}", port + ""))));
                workerID = Integer.valueOf(properties.getProperty("workerID"));
                LOGGER.warn("START FAILED ,use local node file properties workerID-{}", workerID);
            } catch (Exception e1) {
                LOGGER.error("Read file error ", e1);
                return false;
            }
        }
        return true;
    }
```

　　下图为 Leaf-snowflake 的启动服务：

- 监听地址有节点，则创建临时节点，否则创建永久节点；
- 新节点的创建无需判断当前时间是否小于最近一次上报时间，只有旧节点才会有最近一次上报时间。上报时间是该节点另起一个线程进行上报的，如果当前时间小于最近一次上报时间，则返回不上报；
- 每当 leaf-snowflake 服务启动后，都会创建一个 zookeeper 连接实例，连接到 zookeeper，然后先检查 zookeeper 的根节点是否创建。
    1. 没则创建父节点 leaf_forever，同时在父节点下创建一个永久顺序节点 workerId（ID 号是顺序生成的），为本次连接的 leaf-snowflake 所属 ID 号，将 workerId 持久化到本地，重启时可直接获取；
    2. 有创建父节点的情况下，则先获取该父节点下的所有子节点存储到 Map 中，然后根据该 leaf-snowflake 服务的监听地址，判断 map 中是否也有注册的 workerId，有则取回该 workerId，启动服务。没则在父节点下创建一个永久顺序节点 workerId，启动服务。

![avatar](photo_2.png)
