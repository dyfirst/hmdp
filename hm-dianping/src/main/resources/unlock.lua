---@diagnostic disable: undefined-global
---
--- Created by Minjie.
--- DateTime: 2025/12/23 17:14
---
-- 比较线程标识与锁中的线程标识是否一致

if(redis.call('get',KEYS[i]) == ARGV[i]) then
    -- 释放锁 del key
    return redis.call('del',KEYS[i])
end
return 0