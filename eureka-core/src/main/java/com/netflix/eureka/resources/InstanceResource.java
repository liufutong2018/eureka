/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.resources;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.cluster.PeerEurekaNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A <em>jersey</em> resource that handles operations for a particular instance.
 * 一个jersey资源，处理特定instance的操作。类似于一个SpringMVC里的 Controller处理器
 * @author Karthik Ranganathan, Greg Kim
 *
 */
@Produces({"application/xml", "application/json"})
public class InstanceResource {
    private static final Logger logger = LoggerFactory
            .getLogger(InstanceResource.class);

    private final PeerAwareInstanceRegistry registry;
    private final EurekaServerConfig serverConfig;
    private final String id;
    private final ApplicationResource app;


    InstanceResource(ApplicationResource app, String id, EurekaServerConfig serverConfig, PeerAwareInstanceRegistry registry) {
        this.app = app;
        this.id = id;
        this.serverConfig = serverConfig;
        this.registry = registry;
    }

    /**
     * Get requests returns the information about the instance's
     * {@link InstanceInfo}.
     *
     * @return response containing information about the the instance's
     *         {@link InstanceInfo}.
     */
    @GET
    public Response getInstanceInfo() {
        InstanceInfo appInfo = registry
                .getInstanceByAppAndId(app.getName(), id);
        if (appInfo != null) {
            logger.debug("Found: {} - {}", app.getName(), id);
            return Response.ok(appInfo).build();
        } else {
            logger.debug("Not Found: {} - {}", app.getName(), id);
            return Response.status(Status.NOT_FOUND).build();
        }
    }

    /**
     * A put request for renewing lease from a client instance. 续约
     *
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @param overriddenStatus
     *            overridden status if any.
     * @param status
     *            the {@link InstanceStatus} of the instance.
     * @param lastDirtyTimestamp
     *            last timestamp when this instance information was updated.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @PUT
    public Response renewLease(
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication,
            @QueryParam("overriddenstatus") String overriddenStatus,
            @QueryParam("status") String status,
            @QueryParam("lastDirtyTimestamp") String lastDirtyTimestamp) {
        boolean isFromReplicaNode = "true".equals(isReplication); //是不是复制的
        boolean isSuccess = registry.renew(app.getName(), id, isFromReplicaNode); //续约

        // Not found in the registry, immediately ask for a register； 续约失败；客户端第一次先续约404再注册
        if (!isSuccess) {
            logger.warn("Not Found (Renew): {} - {}", app.getName(), id);
            return Response.status(Status.NOT_FOUND).build(); //续约失败返回404
        }
        // Check if we need to sync based on dirty time stamp, the client
        // instance might have changed some value； 检查我们是否需要基于脏时间戳同步，客户端instance可能改变了一些值
        Response response; //shouldSyncWhenTimestampDiffers 配置的，server间数据不一致的时候做同步，默认为ture
        if (lastDirtyTimestamp != null && serverConfig.shouldSyncWhenTimestampDiffers()) { //server间数据的同步
            response = this.validateDirtyTimestamp(Long.valueOf(lastDirtyTimestamp), isFromReplicaNode); //验证时间一样不一样
            // Store the overridden status since the validation found out the node that replicates wins
            if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode() //NOT_FOUND情况下才做同步，正常情况是这个
                    && (overriddenStatus != null)
                    && !(InstanceStatus.UNKNOWN.name().equals(overriddenStatus))
                    && isFromReplicaNode) {
                registry.storeOverriddenStatusIfRequired(app.getAppName(), id, InstanceStatus.valueOf(overriddenStatus)); //续约时做同步
            }
        } else {
            response = Response.ok().build();
        }
        logger.debug("Found (Renew): {} - {}; reply status={}", app.getName(), id, response.getStatus());
        return response;
    }

    /**
     * Handles {@link InstanceStatus} updates. 处理状态修改的请求；在UP和OUT_OF_SERVICE之间
     * 
     * <p>
     * The status updates are normally done for administrative purposes to
     * change the instance status between {@link InstanceStatus#UP} and
     * {@link InstanceStatus#OUT_OF_SERVICE} to select or remove instances for
     * receiving traffic.
     * </p>
     *
     * @param newStatus
     *            the new status of the instance. 实例的新状态。
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes. 是不是节点间的复制。
     * @param lastDirtyTimestamp
     *            last timestamp when this instance information was updated. 客户端更新此instance信息时的最后一个时间戳。
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @PUT
    @Path("status")
    public Response statusUpdate(
            @QueryParam("value") String newStatus,
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication,
            @QueryParam("lastDirtyTimestamp") String lastDirtyTimestamp) {
        try {
            if (registry.getInstanceByAppAndId(app.getName(), id) == null) { //根据微服务名称和instanceId查找出InstanceInfo
                logger.warn("Instance not found: {}/{}", app.getName(), id);
                return Response.status(Status.NOT_FOUND).build(); //404不存在
            }
            boolean isSuccess = registry.statusUpdate(app.getName(), id, //处理状态修改的请求
                    InstanceStatus.valueOf(newStatus), lastDirtyTimestamp,
                    "true".equals(isReplication));

            if (isSuccess) { //修改成功
                logger.info("Status updated: {} - {} - {}", app.getName(), id, newStatus);
                return Response.ok().build(); //成功200
            } else {
                logger.warn("Unable to update status: {} - {} - {}", app.getName(), id, newStatus);
                return Response.serverError().build(); //失败500
            }
        } catch (Throwable e) {
            logger.error("Error updating instance {} for status {}", id, newStatus);
            return Response.serverError().build(); //失败500
        }
    }

    /**
     * Removes status override for an instance, set with；客户端提交了CANCEL_OVERRIDE状态请求，服务端在此处理
     * {@link #statusUpdate(String, String, String)}.
     *
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @param lastDirtyTimestamp
     *            last timestamp when this instance information was updated.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @DELETE
    @Path("status") //处理客户端删除overridden状态请求 
    public Response deleteStatusUpdate(
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication,
            @QueryParam("value") String newStatusValue, //新的状态 null
            @QueryParam("lastDirtyTimestamp") String lastDirtyTimestamp) {
        try {
            if (registry.getInstanceByAppAndId(app.getName(), id) == null) { //根据微服务名称和instanceId查找出InstanceInfo
                logger.warn("Instance not found: {}/{}", app.getName(), id);
                return Response.status(Status.NOT_FOUND).build();
            }

            InstanceStatus newStatus = newStatusValue == null ? InstanceStatus.UNKNOWN : InstanceStatus.valueOf(newStatusValue);
            boolean isSuccess = registry.deleteStatusOverride(app.getName(), id, //进行删除 PeerAwareInstanceRegistryImpl.deleteStatusOverride()
                    newStatus, lastDirtyTimestamp, "true".equals(isReplication));

            if (isSuccess) {
                logger.info("Status override removed: {} - {}", app.getName(), id);
                return Response.ok().build();
            } else {
                logger.warn("Unable to remove status override: {} - {}", app.getName(), id);
                return Response.serverError().build();
            }
        } catch (Throwable e) {
            logger.error("Error removing instance's {} status override", id);
            return Response.serverError().build();
        }
    }

    /**
     * Updates user-specific metadata information. If the key is already available, its value will be overwritten.
     * If not, it will be added.
     * @param uriInfo - URI information generated by jersey.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @PUT
    @Path("metadata")
    public Response updateMetadata(@Context UriInfo uriInfo) {
        try {
            InstanceInfo instanceInfo = registry.getInstanceByAppAndId(app.getName(), id);
            // ReplicationInstance information is not found, generate an error
            if (instanceInfo == null) {
                logger.warn("Cannot find instance while updating metadata for instance {}/{}", app.getName(), id);
                return Response.status(Status.NOT_FOUND).build();
            }
            MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
            Set<Entry<String, List<String>>> entrySet = queryParams.entrySet();
            Map<String, String> metadataMap = instanceInfo.getMetadata();
            // Metadata map is empty - create a new map
            if (Collections.emptyMap().getClass().equals(metadataMap.getClass())) {
                metadataMap = new ConcurrentHashMap<>();
                InstanceInfo.Builder builder = new InstanceInfo.Builder(instanceInfo);
                builder.setMetadata(metadataMap);
                instanceInfo = builder.build();
            }
            // Add all the user supplied entries to the map
            for (Entry<String, List<String>> entry : entrySet) {
                metadataMap.put(entry.getKey(), entry.getValue().get(0));
            }
            registry.register(instanceInfo, false);
            return Response.ok().build();
        } catch (Throwable e) {
            logger.error("Error updating metadata for instance {}", id, e);
            return Response.serverError().build();
        }

    }

    /**
     * Handles cancellation of leases for this particular instance； 处理服务下架请求
     *
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @DELETE
    public Response cancelLease(
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication) {
        try {
            boolean isSuccess = registry.cancel(app.getName(), id, "true".equals(isReplication)); //处理下架请求

            if (isSuccess) {
                logger.debug("Found (Cancel): {} - {}", app.getName(), id);
                return Response.ok().build();
            } else {
                logger.info("Not Found (Cancel): {} - {}", app.getName(), id);
                return Response.status(Status.NOT_FOUND).build();
            }
        } catch (Throwable e) {
            logger.error("Error (cancel): {} - {}", app.getName(), id, e);
            return Response.serverError().build();
        }

    }

    // 验证两个server的lastDirtyTimestamp一致不一致； A复制到B，A的lastDirtyTimestamp，B的appInfo.getLastDirtyTimestamp()
    private Response validateDirtyTimestamp(Long lastDirtyTimestamp,
                                            boolean isReplication) {
        InstanceInfo appInfo = registry.getInstanceByAppAndId(app.getName(), id, false); //从注册表里返回实例
        if (appInfo != null) {
            if ((lastDirtyTimestamp != null) && (!lastDirtyTimestamp.equals(appInfo.getLastDirtyTimestamp()))) { //两个server间时间不一样
                Object[] args = {id, appInfo.getLastDirtyTimestamp(), lastDirtyTimestamp, isReplication};

                if (lastDirtyTimestamp > appInfo.getLastDirtyTimestamp()) { //外来的新
                    logger.debug(
                            "Time to sync, since the last dirty timestamp differs -"
                                    + " ReplicationInstance id : {},Registry : {} Incoming: {} Replication: {}",
                            args);
                    return Response.status(Status.NOT_FOUND).build(); //NOT_FOUND，后续会复制
                } else if (appInfo.getLastDirtyTimestamp() > lastDirtyTimestamp) { //我的比外来的还新
                    // In the case of replication, send the current instance info in the registry for the
                    // replicating node to sync itself with this one.
                    if (isReplication) { //是复制
                        logger.debug(
                                "Time to sync, since the last dirty timestamp differs -"
                                        + " ReplicationInstance id : {},Registry : {} Incoming: {} Replication: {}",
                                args);
                        return Response.status(Status.CONFLICT).entity(appInfo).build(); //数据冲突（正常一般不出现）
                    } else {
                        return Response.ok().build(); //不是复制
                    }
                }
            }

        }
        return Response.ok().build();
    }
}
