package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import io.lettuce.core.api.sync.RedisAclCommands;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
public class HmdpApplicationTest {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private VoucherOrderServiceImpl voucherOrderService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    // 创建一个固定大小的线程池，线程池中最多同时存在 500 个工作线程
    private final ExecutorService executorService = Executors.newFixedThreadPool(500);
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testIdWorker() throws InterruptedException {

        // CountDownLatch：并发控制工具，这里初始计数为 300（对应 300 个并发任务）
        CountDownLatch latch = new CountDownLatch(300);

        // 定义一个并发任务 Runnable
        Runnable task = () -> {
            // 每个线程循环生成 100 个 ID
            for(int i=0;i<100;i++){
                // 调用 RedisIdWorker 生成分布式唯一 ID，"order" 是业务前缀
                long id = redisIdWorker.nextId("order");
                // 打印生成的 ID，方便观察是否有重复
                System.out.println("id = "+id);
            }
            //把 CountDownLatch 的计数器减 1
            latch.countDown();
        };

        // 记录开始时间，用于统计生成 ID 的耗时
        long begin = System.currentTimeMillis();

        // 向线程池提交 300 个并发任务
        for(int i=0;i<300;i++){
            executorService.execute(task);
        }
        // 主线程阻塞，等待所有任务执行完成
        latch.await();
        // 记录结束时间
        long end = System.currentTimeMillis();

        // 输出从提交任务到当前时刻的耗时
        System.out.println("time:"+(end-begin));
    }


    @Test
    void testSaveShop() throws InterruptedException {
        Shop shop = shopService.getById(1L);

        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY+1L,shop,10L, TimeUnit.SECONDS);
    }

    @Test
    void loadShopData(){
        //1.查询店铺信息
        List<Shop> list = shopService.list();
        //2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
        Map<Long,List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3.分批完成写入Redis
        for (Map.Entry<Long,List<Shop>> entry : map.entrySet()) {
            //3.1 获取类型id
            Long typeId = entry.getKey();
            String key = RedisConstants.SHOP_GEO_KEY+typeId;
            //3.2 获取同类型的店铺的集合
            List<Shop> shops = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
            //3.3 写入Redis GEOADD key 经度 维度 member
            for (Shop shop : shops) {
                //stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }

    @Test
    void testHyperLogLog(){
        String[] values = new String[1000];
        int j = 0;
        for(int i=0;i<1000000;i++){
            j = i%1000;
            values[j] = "user_"+i;
            if(j == 999){
                //发送Redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2",values);
            }
        }
        //统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }

    @Test
    @Transactional
    void testCreateVoucherOrderShouldCreateOnlyOneOrderAndDeductStockOnce() {
        long voucherId = 99991L;
        long userId = 88881L;

        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucherId);
        seckillVoucher.setStock(1);
        seckillVoucher.setBeginTime(LocalDateTime.now().minusHours(1));
        seckillVoucher.setEndTime(LocalDateTime.now().plusHours(1));
        seckillVoucherService.save(seckillVoucher);

        VoucherOrder firstOrder = new VoucherOrder();
        firstOrder.setId(1L);
        firstOrder.setUserId(userId);
        firstOrder.setVoucherId(voucherId);

        voucherOrderService.createVoucherOrder(firstOrder);

        VoucherOrder secondOrder = new VoucherOrder();
        secondOrder.setId(2L);
        secondOrder.setUserId(userId);
        secondOrder.setVoucherId(voucherId);

        voucherOrderService.createVoucherOrder(secondOrder);

        long orderCount = voucherOrderService.query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        SeckillVoucher savedVoucher = seckillVoucherService.getById(voucherId);

        Assertions.assertEquals(1L, orderCount);
        Assertions.assertNotNull(savedVoucher);
        Assertions.assertEquals(0, savedVoucher.getStock());
    }
}
