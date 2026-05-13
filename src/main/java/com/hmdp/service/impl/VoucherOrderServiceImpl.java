package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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

    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠卷信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId); //这里的getById来源

        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始！");
        }
        //3.判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束！");
        }

        //4.判断库存是否充足
        if(voucher.getStock() < 1){
            //4.2不充足，返回异常结果
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            //这里事务会失效，需要解决
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();//IVoucherOrderService的代理对象
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //4.充足
        //5.根据优惠卷id和用户id查询订单是否存在(一人一单)
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0){
            //5.1存在，返回异常结果
            return Result.fail("您已经购买过一次！");
        }

        //5.2不存在，扣减库存，更新数据库
        //加上乐观锁，解决超卖问题
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0).update();
        if(!success){
            //扣减失败
            return Result.fail("库存不足！");
        }

        //5.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //5.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //5.2用户id
        voucherOrder.setUserId(userId);
        //5.3代金卷id
        voucherOrder.setVoucherId(voucherId);
        //6.写入数据库
        save(voucherOrder);

        //7.返回订单id
        return Result.ok(orderId);
    }
}
