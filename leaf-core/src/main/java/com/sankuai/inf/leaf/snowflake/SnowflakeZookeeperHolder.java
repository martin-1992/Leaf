package com.sankuai.inf.leaf.snowflake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sankuai.inf.leaf.snowflake.exception.CheckLastTimeException;
import org.apache.commons.io.FileUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.retry.RetryUntilElapsed;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.sankuai.inf.leaf.common.*;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.zookeeper.CreateMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class SnowflakeZookeeperHolder {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnowflakeZookeeperHolder.class);

    // 保存自身的 key ip:port-000000001
    private String zk_AddressNode = null;

    // listenAddress 为 key，为 ip:port
    private String listenAddress = null;
    private int workerID;
    private static final String PREFIX_ZK_PATH = "/snowflake/" + PropertyFactory.getProperties().getProperty("leaf.name");
    private static final String PROP_PATH = System.getProperty("java.io.tmpdir") + File.separator + PropertyFactory.
            getProperties().getProperty("leaf.name") + "/leafconf/{port}/workerID.properties";

    // 保存所有数据持久的节点
    private static final String PATH_FOREVER = PREFIX_ZK_PATH + "/forever";
    private String ip;
    private String port;
    private String connectionString;
    private long lastUpdateTime;

    public SnowflakeZookeeperHolder(String ip, String port, String connectionString) {
        this.ip = ip;
        this.port = port;
        this.listenAddress = ip + ":" + port;
        this.connectionString = connectionString;
    }

    /**
     * 初始化流程：
     * - 基于配置信息，创建 zookeeper 的连接实例；
     * - 检查根节点 forever 是否存在；
     * - 不存在根节点；
     *     1. 创建根节点 forever，为第一次启动，创建永久顺序节点, 并把节点数据放入 value；
     *     2. 持久化，将该端口对应的 workerID 写入文件系统中，重启可获取该 workerID，保证正常启动；
     *     3. 创建节点，使用一个核心线程为 1 的线程池，该线程每 3s 将本机数据上报给根节点 forever，数据为由 ip、端口、本机时间戳组成的 json
     * - 存在根节点；
     *     1. 获取根节点 forever 的所有子节点，存入 Map 中；
     *     2. 根据监听地址，从 Map 中获取节点；
     *     3. 监听地址有节点，则判断该节点的时间是否小于最后一次上报的时间，创建临时节点，workderId 持久化；
     *     4. 监听地址没有节点，无需判断节点时间，创建永久节点，workderId 持久化。
     *
     * @return
     */
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
                    // 启动 worder 时使用会使用
                    workerID = workerid;
                    // 先检查，该节点的时间不能小于最后一次上报的时间
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

    // /snowflake_forever/ip:port-000000001
    private void doService(CuratorFramework curator) {
        ScheduledUploadData(curator, zk_AddressNode);
    }

    /**
     * 创建一个核心线程为 1 的线程池，该线程每 3s 将本机数据上报给根节点 forever，
     * 数据为由 ip、端口、本机时间戳组成的 json
     */
    private void ScheduledUploadData(final CuratorFramework curator, final String zk_AddressNode) {
        Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "schedule-upload-time");
                thread.setDaemon(true);
                return thread;
            }
        }).scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                updateNewData(curator, zk_AddressNode);
            }
            // 每 3s 上报数据
        }, 1L, 3L, TimeUnit.SECONDS);

    }

    /**
     * 检查该节点
     * @param curator
     * @param zk_AddressNode
     * @return
     * @throws Exception
     */
    private boolean checkInitTimeStamp(CuratorFramework curator, String zk_AddressNode) throws Exception {
        // 获取节点的数据
        byte[] bytes = curator.getData().forPath(zk_AddressNode);
        // 将节点数据转为 endPoint 对象
        Endpoint endPoint = deBuildData(new String(bytes));
        // 该节点的时间不能小于最后一次上报的时间
        return !(endPoint.getTimestamp() > System.currentTimeMillis());
    }

    /**
     * 创建持久顺序节点 ,并把节点数据放入 value
     *
     * @param curator
     * @return
     * @throws Exception
     */
    private String createNode(CuratorFramework curator) throws Exception {
        try {
            return curator.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT_SEQUENTIAL).forPath(PATH_FOREVER + "/" + listenAddress + "-", buildData().getBytes());
        } catch (Exception e) {
            LOGGER.error("create node error msg {} ", e.getMessage());
            throw e;
        }
    }

    private void updateNewData(CuratorFramework curator, String path) {
        try {
            if (System.currentTimeMillis() < lastUpdateTime) {
                return;
            }
            // 设置节点数据，数据为由 ip、端口、时间戳组成的 json
            curator.setData().forPath(path, buildData().getBytes());
            lastUpdateTime = System.currentTimeMillis();
        } catch (Exception e) {
            LOGGER.info("update init data error path is {} error is {}", path, e);
        }
    }

    /**
     * 构建需要上传的数据，由 ip、端口、时间戳组成的对象 endpoint
     *
     * @return
     */
    private String buildData() throws JsonProcessingException {
        Endpoint endpoint = new Endpoint(ip, port, System.currentTimeMillis());
        ObjectMapper mapper = new ObjectMapper();
        // 对象数据转为 json
        String json = mapper.writeValueAsString(endpoint);
        return json;
    }

    private Endpoint deBuildData(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Endpoint endpoint = mapper.readValue(json, Endpoint.class);
        return endpoint;
    }

    /**
     * 在节点文件系统上缓存一个 workid 值, zk 失效, 机器重启时保证能够正常启动
     * 持久化，以端口命名的文件，写入该端口对应的 workerID，重启时获取对应端口的 workerID
     *
     * @param workerID
     */
    private void updateLocalWorkerID(int workerID) {
        File LeafconfFile = new File(PROP_PATH.replace("{port}", port));
        boolean exists = LeafconfFile.exists();
        LOGGER.info("file exists status is {}", exists);
        if (exists) {
            try {
                // 持久化，将 workerID 写入文件，重启可重新读取该文件获取 workerID
                FileUtils.writeStringToFile(LeafconfFile, "workerID=" + workerID, false);
                LOGGER.info("update file cache workerID is {}", workerID);
            } catch (IOException e) {
                LOGGER.error("update file cache error ", e);
            }
        } else {
            // 不存在文件, 父目录页肯定不存在
            try {
                // 判断该文件的父目录是否存在
                boolean mkdirs = LeafconfFile.getParentFile().mkdirs();
                LOGGER.info("init local file cache create parent dis status is {}, worker id is {}", mkdirs, workerID);
                if (mkdirs) {
                    // 存在则判断 LeafconfFile 是否来创建成功，成功则写入文件
                    if (LeafconfFile.createNewFile()) {
                        FileUtils.writeStringToFile(LeafconfFile, "workerID=" + workerID, false);
                        LOGGER.info("local file cache workerID is {}", workerID);
                    }
                } else {
                    LOGGER.warn("create parent dir error===");
                }
            } catch (IOException e) {
                LOGGER.warn("craete workerID conf file error", e);
            }
        }
    }

    private CuratorFramework createWithOptions(String connectionString, RetryPolicy retryPolicy, int connectionTimeoutMs, int sessionTimeoutMs) {
        return CuratorFrameworkFactory.builder().connectString(connectionString)
                .retryPolicy(retryPolicy)
                .connectionTimeoutMs(connectionTimeoutMs)
                .sessionTimeoutMs(sessionTimeoutMs)
                .build();
    }

    /**
     * 上报数据结构
     */
    static class Endpoint {
        private String ip;
        private String port;
        private long timestamp;

        public Endpoint() {
        }

        public Endpoint(String ip, String port, long timestamp) {
            this.ip = ip;
            this.port = port;
            this.timestamp = timestamp;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public String getPort() {
            return port;
        }

        public void setPort(String port) {
            this.port = port;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    }

    public String getZk_AddressNode() {
        return zk_AddressNode;
    }

    public void setZk_AddressNode(String zk_AddressNode) {
        this.zk_AddressNode = zk_AddressNode;
    }

    public String getListenAddress() {
        return listenAddress;
    }

    public void setListenAddress(String listenAddress) {
        this.listenAddress = listenAddress;
    }

    public int getWorkerID() {
        return workerID;
    }

    public void setWorkerID(int workerID) {
        this.workerID = workerID;
    }

}
