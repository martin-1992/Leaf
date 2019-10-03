package com.sankuai.inf.leaf;

import com.sankuai.inf.leaf.common.Result;

public interface IDGen {

    /**
     * 获取该业务的 key 的下一个 ID，如果没开启 snowflake，则 ID 为 0
     * @param key
     * @return
     */
    Result get(String key);

    /**
     * 主要是对 snowflake 算法进行初始化
     * @return
     */
    boolean init();
}
