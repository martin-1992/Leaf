package com.sankuai.inf.leaf.server.service;

import com.alibaba.druid.pool.DruidDataSource;
import com.sankuai.inf.leaf.IDGen;
import com.sankuai.inf.leaf.common.PropertyFactory;
import com.sankuai.inf.leaf.common.Result;
import com.sankuai.inf.leaf.common.ZeroIDGen;
import com.sankuai.inf.leaf.segment.SegmentIDGenImpl;
import com.sankuai.inf.leaf.segment.dao.IDAllocDao;
import com.sankuai.inf.leaf.segment.dao.impl.IDAllocDaoImpl;
import com.sankuai.inf.leaf.server.Constants;
import com.sankuai.inf.leaf.server.exception.InitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.Properties;

@Service("SegmentService")
public class SegmentService {

    private Logger logger = LoggerFactory.getLogger(SegmentService.class);
    IDGen idGen;
    DruidDataSource dataSource;

    /**
     * 1. 载入配置文件 leaf.properties；
     * 2. 解析配置文件，判断是否开启号段模式，不开启则 ID 返回为 0；
     * 3. 创建 Druid 数据库连接池，根据配置文件配置；
     * 4. 初始化 sqlSessionFactory，用于创建 SqlSession；
     * 5. 创建 ID 生成器，并初始化
     *
     * @throws SQLException
     * @throws InitException
     */
    public SegmentService() throws SQLException, InitException {
        // 载入配置文件 leaf.properties
        Properties properties = PropertyFactory.getProperties();
        // 是否开启号段模式，默认开启
        boolean flag = Boolean.parseBoolean(properties.getProperty(Constants.LEAF_SEGMENT_ENABLE, "true"));
        if (flag) {
            // Config dataSource
            // 创建 Druid 数据库连接池，并配置
            dataSource = new DruidDataSource();
            dataSource.setUrl(properties.getProperty(Constants.LEAF_JDBC_URL));
            dataSource.setUsername(properties.getProperty(Constants.LEAF_JDBC_USERNAME));
            dataSource.setPassword(properties.getProperty(Constants.LEAF_JDBC_PASSWORD));
            dataSource.init();

            // Config Dao
            // 初始化 sqlSessionFactory，用于创建 SqlSession
            IDAllocDao dao = new IDAllocDaoImpl(dataSource);

            // Config ID Gen
            // 创建 ID 生成器，并初始化
            idGen = new SegmentIDGenImpl();
            ((SegmentIDGenImpl) idGen).setDao(dao);
            if (idGen.init()) {
                logger.info("Segment Service Init Successfully");
            } else {
                throw new InitException("Segment Service Init Fail");
            }
        } else {
            // 不开启，则返回 ID 为 0
            idGen = new ZeroIDGen();
            logger.info("Zero ID Gen Service Init Successfully");
        }
    }

    /**
     * 根据 key 获取 id，key 为数据库对应的 biz_tag
     * @param key
     * @return
     */
    public Result getId(String key) {
        return idGen.get(key);
    }

    /**
     * 获取 ID 生成器，如果不是 SegmentIDGenImpl 的实例，则返回 null
     * @return
     */
    public SegmentIDGenImpl getIdGen() {
        if (idGen instanceof SegmentIDGenImpl) {
            return (SegmentIDGenImpl) idGen;
        }
        return null;
    }
}
