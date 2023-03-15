package com.netflix.discovery;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A task for updating and replicating the local instanceinfo to the remote server. Properties of this task are:
 * - configured with a single update thread to guarantee sequential update to the remote server
 * - update tasks can be scheduled on-demand via onDemandUpdate()
 * - task processing is rate limited by burstSize
 * - a new update task is always scheduled automatically after an earlier update task. However if an on-demand task
 *   is started, the scheduled automatic update task is discarded (and a new one will be scheduled after the new
 *   on-demand update).
 *
 *   @author dliu
 */
class InstanceInfoReplicator implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InstanceInfoReplicator.class);

    private final DiscoveryClient discoveryClient;
    private final InstanceInfo instanceInfo;

    private final int replicationIntervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Future> scheduledPeriodicRef;

    private final AtomicBoolean started;
    private final RateLimiter rateLimiter;
    private final int burstSize;
    private final int allowedRatePerMinute;
    // （InstanceInfo复制者； 就是复制配置文件里的信息）
    InstanceInfoReplicator(DiscoveryClient discoveryClient, InstanceInfo instanceInfo, int replicationIntervalSeconds, int burstSize) {
        this.discoveryClient = discoveryClient;
        this.instanceInfo = instanceInfo;
        this.scheduler = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("DiscoveryClient-InstanceInfoReplicator-%d")
                        .setDaemon(true)
                        .build());

        this.scheduledPeriodicRef = new AtomicReference<Future>();

        this.started = new AtomicBoolean(false);
        this.rateLimiter = new RateLimiter(TimeUnit.MINUTES); //令牌桶限流
        this.replicationIntervalSeconds = replicationIntervalSeconds; //间隔
        this.burstSize = burstSize;

        this.allowedRatePerMinute = 60 * this.burstSize / this.replicationIntervalSeconds; //允许每分钟的速率(4)
        logger.info("InstanceInfoReplicator onDemand update allowed rate per min is {}", allowedRatePerMinute);
    }

    // 更新client信息给server
    public void start(int initialDelayMs) {
        if (started.compareAndSet(false, true)) {
            instanceInfo.setIsDirty();  // 设置为true脏的，表示还没更新到service； for initial register 
            Future next = scheduler.schedule(this, initialDelayMs, TimeUnit.SECONDS); //启动任务，一次性的；更新client信息给server；执行run()
            scheduledPeriodicRef.set(next); //定时更新的时候，把此任务放进去
        }
    }

    public void stop() {
        shutdownAndAwaitTermination(scheduler);
        started.set(false);
    }

    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("InstanceInfoReplicator stop interrupted");
        }
    }

    public boolean onDemandUpdate() { //配置文件发生变更时，按需更新client信息给server
        if (rateLimiter.acquire(burstSize, allowedRatePerMinute)) { //令牌桶算法，限流器（杀鸡用牛刀，配置文件不会更新那么快）
            if (!scheduler.isShutdown()) { //定时器没关
                scheduler.submit(new Runnable() { //执行此run()
                    @Override
                    public void run() {
                        logger.debug("Executing on-demand update of local InstanceInfo");
    
                        Future latestPeriodic = scheduledPeriodicRef.get(); //看看有没有执行中的，取出
                        if (latestPeriodic != null && !latestPeriodic.isDone()) { //如果发生了按需更新，会把最近的定时更新取消掉 ，不会出现多条线
                            logger.debug("Canceling the latest scheduled update, it will be rescheduled at the end of on demand update");
                            latestPeriodic.cancel(false); //取消执行
                        }
    
                        InstanceInfoReplicator.this.run(); //执行下面的那个run() 按需更新
                    }
                });
                return true;
            } else {
                logger.warn("Ignoring onDemand update due to stopped scheduler");
                return false;
            }
        } else {
            logger.warn("Ignoring onDemand update due to rate limiter");
            return false;
        }
    }

    // 更新client信息给server
    public void run() {
        try {
            discoveryClient.refreshInstanceInfo(); //更新instanceInfo信息

            Long dirtyTimestamp = instanceInfo.isDirtyWithTime(); //返回客户端修改的时间
            if (dirtyTimestamp != null) { //客户端发生了修改
                discoveryClient.register(); //提交【注册请求】给server
                instanceInfo.unsetIsDirty(dirtyTimestamp);//取消设置为脏
            }
        } catch (Throwable t) {
            logger.warn("There was a problem with the instance info replicator", t);
        } finally {
            Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS); //执行多次
            scheduledPeriodicRef.set(next); //定时任务放到此处
        }
    }

}
