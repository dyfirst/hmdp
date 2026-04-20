package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    //初始化脚本
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //线程池
    private final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 运行开关
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        SECKILL_ORDER_EXECUTOR.shutdownNow();
        try {
            if (!SECKILL_ORDER_EXECUTOR.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("秒杀订单处理线程池未在预期时间内关闭");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (running) {
                try{
                    //1.获取消息队列中的订单信息 XREADGOURP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息是否获取成功
                    if(list == null||list.isEmpty()){
                        //2.1 如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //3.解析订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //4.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //5.ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                }catch (Exception e){
                    if (shouldExitOnShutdown(e)) {
                        break;
                    }
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (running) {
                try{
                    //1.获取pending-list中的订单信息 XREADGOURP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断消息是否获取成功
                    if(list == null||list.isEmpty()){
                        //2.1 如果获取失败，说明pending-list没有消息，结束循环
                        break;
                    }
                    //3.解析订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //4.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //5.ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                }catch (Exception e){
                    if (shouldExitOnShutdown(e)) {
                        break;
                    }
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    private boolean shouldExitOnShutdown(Exception e) {
        if (!running || Thread.currentThread().isInterrupted()) {
            return true;
        }
        Throwable cause = e;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && (message.contains("Connection is closed")
                    || message.contains("LettuceConnectionFactory was destroyed"))) {
                log.debug("秒杀订单处理线程检测到 Redis 连接关闭，准备退出");
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /*
    * 创建订单
    * */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        //2.创建新对象
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        //3.获取锁
        boolean isLock = lock.tryLock();
        //判断锁是否获取成功
        if(!isLock){
            //获取锁失败或者重试
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //1.执行Lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(),String.valueOf(orderId));

        //2.判断结果是否为0
        int r = result.intValue();
        if(r != 0){
            //2.1 不为0，代表没有购买资格
            return Result.fail(r == 1? "库存不足":"不能重复下单");
        }

        //3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //4.返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.一人一单
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        //5.1查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //5.2判断是否存在
        if(count > 0L){
            //用户已经购买过了
            log.error("用户已经购买过一次！");
            return;
        }

        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock",0).update();
        if(!success){
            //扣减失败
            log.error("不允许重复下单");
            return;
        }

        //7.创建订单
        save(voucherOrder);
    }
}
