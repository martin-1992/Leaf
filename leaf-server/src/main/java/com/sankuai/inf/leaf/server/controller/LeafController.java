package com.sankuai.inf.leaf.server.controller;

import com.sankuai.inf.leaf.common.Result;
import com.sankuai.inf.leaf.common.Status;
import com.sankuai.inf.leaf.server.exception.LeafServerException;
import com.sankuai.inf.leaf.server.exception.NoKeyException;
import com.sankuai.inf.leaf.server.service.SegmentService;
import com.sankuai.inf.leaf.server.service.SnowflakeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LeafController {

    private Logger logger = LoggerFactory.getLogger(LeafController.class);

    @Autowired
    SegmentService segmentService;

    @Autowired
    SnowflakeService snowflakeService;

    /**
     * 根据业务 key 获取 ID
     * @param key
     * @return
     */
    @RequestMapping(value = "/api/segment/get/{key}")
    public String getSegmentID(@PathVariable("key") String key) {
        // key 为数据库对应的 biz_tag
        return get(key, segmentService.getId(key));
    }

    /**
     * 获取 snowflake 算法生成的 ID
     *
     * @param key
     * @return
     */
    @RequestMapping(value = "/api/snowflake/get/{key}")
    public String getSnowflakeID(@PathVariable("key") String key) {
        return get(key, snowflakeService.getId(key));

    }

    private String get(@PathVariable("key") String key, Result id) {
        Result result;
        // 参数校验，key 为空，返回错误
        if (key == null || key.isEmpty()) {
            throw new NoKeyException();
        }

        result = id;
        // 判断该 id 的状态是否异常，不是则获取该 ID
        if (result.getStatus().equals(Status.EXCEPTION)) {
            throw new LeafServerException(result.toString());
        }
        return String.valueOf(result.getId());
    }
}
