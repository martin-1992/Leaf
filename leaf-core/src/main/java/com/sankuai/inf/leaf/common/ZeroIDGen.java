package com.sankuai.inf.leaf.common;

import com.sankuai.inf.leaf.IDGen;

/**
 * 不开启 snowflake 算法，则返回 id 为 0
 */
public class ZeroIDGen implements IDGen {
    @Override
    public Result get(String key) {
        return new Result(0, Status.SUCCESS);
    }

    @Override
    public boolean init() {
        return true;
    }
}
