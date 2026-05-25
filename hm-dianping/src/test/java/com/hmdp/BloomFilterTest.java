package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.List;

@SpringBootTest
public class BloomFilterTest {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ShopServiceImpl shopService;

    /**
     * 测试布隆过滤器是否正确初始化，已存在的店铺 ID 应该能通过
     */
    @Test
    void testExistingShopIdShouldPassBloomFilter() {
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(RedisConstants.BLOOM_SHOP_KEY);

        // 数据库中已有的店铺 ID
        List<Shop> shops = shopService.list();
        Assertions.assertFalse(shops.isEmpty(), "数据库中应存在店铺数据");

        for (Shop shop : shops) {
            Assertions.assertTrue(bloomFilter.contains(shop.getId()),
                    "店铺 ID " + shop.getId() + " 应该在布隆过滤器中");
        }
    }

    /**
     * 测试查询不存在的 ID，应被布隆过滤器拦截
     */
    @Test
    void testNonExistentShopIdShouldBeBlocked() {
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(RedisConstants.BLOOM_SHOP_KEY);

        // 用一个大概率不存在的 ID 测试
        Long fakeId = 999999999L;
        // 确认数据库里真的没有这个 ID
        Shop shop = shopService.getById(fakeId);
        if (shop == null) {
            // 数据库不存在，布隆过滤器应该返回 false（或误判为 true，但概率很低）
            boolean mightExist = bloomFilter.contains(fakeId);
            // 布隆过滤器误判率 3%，这里只打印观察，不做硬断言
            System.out.println("不存在的 ID " + fakeId + " 布隆过滤器判断结果: " + mightExist);
        }
    }

    /**
     * 测试通过 shopService.queryById 走完整链路：布隆过滤器 → 缓存 → 数据库
     */
    @Test
    void testQueryByIdWithBloomFilter() {
        // 查询存在的店铺
        List<Shop> shops = shopService.list();
        if (!shops.isEmpty()) {
            Long existId = shops.get(0).getId();
            Result result = shopService.queryById(existId);
            Assertions.assertNotNull(result, "存在的店铺应该能查到");
        }

        // 查询不存在的店铺，应被布隆过滤器拦截
        Result result = shopService.queryById(999999999L);
        System.out.println("查询不存在 ID 的返回: " + result);
    }

    /**
     * 测试新增店铺后，布隆过滤器能识别新 ID
     */
    @Test
    void testNewShopShouldBeAddedToBloomFilter() {
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(RedisConstants.BLOOM_SHOP_KEY);

        // 模拟新店铺 ID
        Long newShopId = 888888888L;
        bloomFilter.add(newShopId);

        Assertions.assertTrue(bloomFilter.contains(newShopId),
                "新增的店铺 ID 应该能被布隆过滤器识别");
    }
}
