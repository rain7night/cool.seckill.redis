package org.seckill.service.impl;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.seckill.dao.SeckillDao;
import org.seckill.dao.SuccessKilledDao;
import org.seckill.dao.cache.RedisDao;
import org.seckill.dto.Exposer;
import org.seckill.dto.SeckillExecution;
import org.seckill.entity.Seckill;
import org.seckill.entity.SuccessKilled;
import org.seckill.enums.SeckillStatEnum;
import org.seckill.exception.RepeatKillException;
import org.seckill.exception.SeckillCloseException;
import org.seckill.exception.SeckillException;
import org.seckill.service.SeckillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务实现类
 */

@Service
public class SeckillServiceImpl implements SeckillService {

    private Log LOG = LogFactory.getLog(this.getClass());

    @Autowired
    private SeckillDao seckillDao;

    @Autowired
    private RedisDao redisDao;

    @Autowired
    private SuccessKilledDao successKilledDao;

    //md5盐值字符串,用于混淆md5
    private final String slat = "asdfasd2341242@#$@#$%$%%#@$%#@%^%^";

    public List<Seckill> getSeckillList() {
        return seckillDao.queryAll(0, 1000);
    }

    public Seckill getById(long seckillId) {
        return seckillDao.queryById(seckillId);
    }

    public Exposer exportSeckillUrl(long seckillId) {

        //优化点:缓存优化 超时的基础上维护一致性
        //1.访问redis
        Seckill seckill = redisDao.getSeckill(seckillId);

        if (seckill == null) {
            //2.访问数据库
            seckill = getById(seckillId);
            if (seckill == null) {
                return new Exposer(false, seckillId);
            } else {
                //3.放入redis
                redisDao.putSeckill(seckill);
            }
        }

        Date startTime = seckill.getStartTime();
        Date endTime = seckill.getEndTime();

        Date nowTime = new Date();

        if (nowTime.getTime() > endTime.getTime() || nowTime.getTime() < startTime.getTime()) {
            return new Exposer(false, seckillId, nowTime.getTime(), startTime.getTime(), endTime.getTime());
        }

        //转化特定字符串的过程,不可逆
        String md5 = getMD5(seckillId);
        return new Exposer(true, md5, seckillId);
    }


    /**
     * 使用注解控制事务的优点:
     * 1.开发团队达成一致约定,明确标注事务方法的编程风格.
     * 2.保证事务方法的执行时间尽可能短,不要穿插其他网络操作RPC/HTTP请求或者剥离到事务方法外部.
     * 3.不是所有的方法都需要事务.如一些查询的service.只有一条修改操作的service.
     */
    @Transactional
    public SeckillExecution executeSeckill(long seckillId, long userPhone, String md5) throws SeckillException, RepeatKillException, SeckillCloseException {
        if (StringUtils.isEmpty(md5) || !md5.equals(getMD5(seckillId))) {
            throw new SeckillException(SeckillStatEnum.DATA_REWRITE.getStateInfo());
        }

        return executeSeckill(seckillId, userPhone);
    }

    public SeckillExecution executeSeckillProcedure(long seckillId, long userPhone, String md5) {
        if (StringUtils.isEmpty(md5) || !md5.equals(getMD5(seckillId))) {
            throw new SeckillException(SeckillStatEnum.DATA_REWRITE.getStateInfo());
        }

        Date killTime = new Date();
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("seckillId", seckillId);
        map.put("phone", userPhone);
        map.put("killTime", killTime);
        map.put("result", null);
        //执行存储过程 result被赋值
        try {
            seckillDao.killByProcedure(map);
            //获取result
            int result = MapUtils.getInteger(map, "result", -2);
            if (result == 1) {
                SuccessKilled sk = successKilledDao.queryByIdWithSeckill(seckillId, userPhone);
                return new SeckillExecution(seckillId, SeckillStatEnum.SUCCESS, sk);
            } else {
                return new SeckillExecution(seckillId, SeckillStatEnum.stateOf(result));
            }

        } catch (Exception e) {
            LOG.error(e.getMessage());
            return new SeckillExecution(seckillId, SeckillStatEnum.INNER_ERROR);
        }

    }

    private String getMD5(long seckillId) {
        String base = seckillId + "/" + slat;
        String md5 = DigestUtils.md5DigestAsHex(base.getBytes());
        LOG.info("_________________________________md5: " + md5);
        return md5;
    }

    /**
     * 执行秒杀-无md5校验
     *
     * @param seckillId
     * @param userPhone
     * @return
     * @throws SeckillException
     * @throws RepeatKillException
     * @throws SeckillCloseException
     */
    @Transactional
    public SeckillExecution executeSeckill(long seckillId, long userPhone) throws SeckillException, RepeatKillException, SeckillCloseException {

        //执行秒杀逻辑:1.减库存.2.记录购买行为
        Date nowTime = new Date();

        try {

            //记录购买行为
            int inserCount = successKilledDao.insertSuccessKilled(seckillId, userPhone);

            if (inserCount <= 0) {
                //重复秒杀
                throw new RepeatKillException(SeckillStatEnum.REPEAT_KILL.getStateInfo());
            } else {
                //减库存-热点商品竞争
                int updateCount = seckillDao.reduceNumber(seckillId, nowTime);

                if (updateCount <= 0) {
                    //rollback
                    throw new SeckillCloseException(SeckillStatEnum.END.getStateInfo());
                } else {
                    //秒杀成功  commit
                    SuccessKilled successKilled = successKilledDao.queryByIdWithSeckill(seckillId, userPhone);
                    return new SeckillExecution(seckillId, SeckillStatEnum.SUCCESS, successKilled);
                }

            }


        } catch (SeckillCloseException e1) {
            throw e1;
        } catch (RepeatKillException e2) {
            throw e2;
        } catch (Exception e) {
            LOG.error(e.getMessage());
            //所有的编译期异常转化为运行期异常,spring的声明式事务做rollback
            throw new SeckillException("seckill inner error: " + e.getMessage());
        }

    }
}
