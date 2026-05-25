package com.hmdp.config;

import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@EnableScheduling
public class BloomFilterConfig implements CommandLineRunner {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ShopMapper shopMapper;

    @Override
    public void run(String... args) {
        rebuildBloomFilter();
    }

    /**
     * 每七天凌晨 3 点重建布隆过滤器
     */
    @Scheduled(cron = "0 0 3 */7 * ?")
    public void scheduledRebuild() {
        log.info("开始定时重建布隆过滤器...");
        rebuildBloomFilter();
    }

    private void rebuildBloomFilter() {
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(RedisConstants.BLOOM_SHOP_KEY);
        // 删除旧的布隆过滤器
        bloomFilter.delete();
        // 重新初始化：预估 100 万个元素，误判率 3%
        bloomFilter.tryInit(1000000L, 0.03);

        // 从数据库加载所有店铺 ID
        List<Long> shopIds = shopMapper.selectList(null)
                .stream().map(Shop::getId).collect(Collectors.toList());
        for (Long shopId : shopIds) {
            bloomFilter.add(shopId);
        }
        log.info("布隆过滤器重建完成，加载店铺数量：{}", shopIds.size());
    }
}
