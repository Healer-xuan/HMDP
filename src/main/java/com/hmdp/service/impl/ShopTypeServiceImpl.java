package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public Result queryTypeList() {
        //1.从redis查询首页缓存数据
        String shopType = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);

        //2.判断缓存是否存在
        if (StrUtil.isNotBlank(shopType)) {
            //3.存在，返回
            List<ShopType> shopType1= JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(shopType1);
        }

        //4.不存在，查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        //5.如果查询为空，返回错误
        if(shopTypeList == null || shopTypeList.size() == 0){
            return Result.fail("商品类型查询失败!");
        }

        //6.不为空，则把数据写入到redis，并返回结果
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(shopTypeList));
        return Result.ok(shopTypeList);
    }
}
