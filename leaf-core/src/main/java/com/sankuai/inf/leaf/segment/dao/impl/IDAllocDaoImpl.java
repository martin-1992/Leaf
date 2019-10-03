package com.sankuai.inf.leaf.segment.dao.impl;

import com.sankuai.inf.leaf.segment.dao.IDAllocDao;
import com.sankuai.inf.leaf.segment.dao.IDAllocMapper;
import com.sankuai.inf.leaf.segment.model.LeafAlloc;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import javax.sql.DataSource;
import java.util.List;

/**
 * 调用数据库，用于查询 key、和更新 key 的发号配置
 */
public class IDAllocDaoImpl implements IDAllocDao {

    SqlSessionFactory sqlSessionFactory;

    /**
     * 初始化 sqlSessionFactory，用于创建 SqlSession
     * @param dataSource
     */
    public IDAllocDaoImpl(DataSource dataSource) {
        TransactionFactory transactionFactory = new JdbcTransactionFactory();
        Environment environment = new Environment("development", transactionFactory, dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(IDAllocMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /**
     * 调用 IDAllocMapper#getAllLeafAllocs，获取所有业务 key 对应的发号配置
     * @return
     */
    @Override
    public List<LeafAlloc> getAllLeafAllocs() {
        // 打开 session
        SqlSession sqlSession = sqlSessionFactory.openSession(false);
        try {
            return sqlSession.selectList("com.sankuai.inf.leaf.segment.dao.IDAllocMapper.getAllLeafAllocs");
        } finally {
            sqlSession.close();
        }
    }

    /**
     * 1. 从数据库更新该业务 tag 的 maxId；
     * 2. 再从数据库查询该 tag 的结果，将查询结果封装成对象 LeafAlloc，为数据库表的实体类；
     * 3. 封装成功，提交事务，返回封装对象。
     *
     * @param tag
     * @return
     */
    @Override
    public LeafAlloc updateMaxIdAndGetLeafAlloc(String tag) {
        SqlSession sqlSession = sqlSessionFactory.openSession();
        try {
            // tag 为数据库的 biz_tag，更新该 tag 的 maxId，从 sql 语句可看出 maxId = max_id + step
            sqlSession.update("com.sankuai.inf.leaf.segment.dao.IDAllocMapper.updateMaxId", tag);
            // 更新完后，从数据库获取该 tag 的查询结果，封装成 LeafAlloc 对象
            LeafAlloc result = sqlSession.selectOne("com.sankuai.inf.leaf.segment.dao.IDAllocMapper.getLeafAlloc", tag);
            // 封装成功，提交事务
            sqlSession.commit();
            return result;
        } finally {
            sqlSession.close();
        }
    }

    /**
     * 传入 LeafAlloc 对象，可自定义 step，根据业务需要可设置更大的 step，maxId = max_id + step
     *
     * @param leafAlloc
     * @return
     */
    @Override
    public LeafAlloc updateMaxIdByCustomStepAndGetLeafAlloc(LeafAlloc leafAlloc) {
        SqlSession sqlSession = sqlSessionFactory.openSession();
        try {
            sqlSession.update("com.sankuai.inf.leaf.segment.dao.IDAllocMapper.updateMaxIdByCustomStep", leafAlloc);
            LeafAlloc result = sqlSession.selectOne("com.sankuai.inf.leaf.segment.dao.IDAllocMapper.getLeafAlloc", leafAlloc.getKey());
            sqlSession.commit();
            return result;
        } finally {
            sqlSession.close();
        }
    }

    /**
     * 获取数据库中所有的业务 key，即 biz_tag
     *
     * @return
     */
    @Override
    public List<String> getAllTags() {
        SqlSession sqlSession = sqlSessionFactory.openSession(false);
        try {
            return sqlSession.selectList("com.sankuai.inf.leaf.segment.dao.IDAllocMapper.getAllTags");
        } finally {
            sqlSession.close();
        }
    }
}
