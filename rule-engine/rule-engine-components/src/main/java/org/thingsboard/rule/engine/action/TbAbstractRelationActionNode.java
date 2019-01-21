/**
 * Copyright © 2016-2018 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.rule.engine.action;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNode;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.rule.engine.util.EntityContainer;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.DashboardInfo;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.EntityView;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EntityIdFactory;
import org.thingsboard.server.common.data.page.TextPageData;
import org.thingsboard.server.common.data.page.TextPageLink;
import org.thingsboard.server.common.data.relation.EntitySearchDirection;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.dao.asset.AssetService;
import org.thingsboard.server.dao.customer.CustomerService;
import org.thingsboard.server.dao.dashboard.DashboardService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.entityview.EntityViewService;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.thingsboard.rule.engine.api.TbRelationTypes.FAILURE;
import static org.thingsboard.rule.engine.api.TbRelationTypes.SUCCESS;
import static org.thingsboard.rule.engine.api.util.DonAsynchron.withCallback;

@Slf4j
public abstract class TbAbstractRelationActionNode<C extends TbAbstractRelationActionNodeConfiguration> implements TbNode {

    protected C config;
    protected EntityId fromId;
    protected EntityId toId;

    private LoadingCache<Entitykey, EntityContainer> entityIdCache;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = loadEntityNodeActionConfig(configuration);
        CacheBuilder cacheBuilder = CacheBuilder.newBuilder();
        if (this.config.getEntityCacheExpiration() > 0) {
            cacheBuilder.expireAfterWrite(this.config.getEntityCacheExpiration(), TimeUnit.SECONDS);
        }
        entityIdCache = cacheBuilder
                .build(new EntityCacheLoader(ctx, createEntityIfNotExists()));
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        withCallback(processEntityRelationAction(ctx, msg),
                filterResult -> ctx.tellNext(msg, filterResult ? SUCCESS : FAILURE), t -> ctx.tellFailure(msg, t), ctx.getDbCallbackExecutor());
    }

    @Override
    public void destroy() {
    }

    private ListenableFuture<Boolean> processEntityRelationAction(TbContext ctx, TbMsg msg) {
        return Futures.transformAsync(getEntity(ctx, msg), entityContainer -> doProcessEntityRelationAction(ctx, msg, entityContainer));
    }

    protected abstract boolean createEntityIfNotExists();

    protected abstract ListenableFuture<Boolean> doProcessEntityRelationAction(TbContext ctx, TbMsg msg, EntityContainer entityContainer);

    protected abstract C loadEntityNodeActionConfig(TbNodeConfiguration configuration) throws TbNodeException;

    protected ListenableFuture<EntityContainer> getEntity(TbContext ctx, TbMsg msg) {
        String entityName = TbNodeUtils.processPattern(this.config.getEntityNamePattern(), msg.getMetaData());
        String type = null;
        if (this.config.getEntityTypePattern() != null) {
            type = TbNodeUtils.processPattern(this.config.getEntityTypePattern(), msg.getMetaData());
        }
        EntityType entityType = EntityType.valueOf(this.config.getEntityType());
        Entitykey key = new Entitykey(entityName, type, entityType);
        return ctx.getDbCallbackExecutor().executeAsync(() -> {
            EntityContainer entityContainer = entityIdCache.get(key);
            if (entityContainer.getEntityId() == null) {
                throw new RuntimeException("No entity found with type '" + key.getEntityType() + " ' and name '" + key.getEntityName() + "'.");
            }
            return entityContainer;
        });
    }

    protected void processSearchDirection(TbMsg msg, EntityContainer entityContainer) {
        if (EntitySearchDirection.FROM.name().equals(config.getDirection())) {
            fromId = EntityIdFactory.getByTypeAndId(entityContainer.getEntityType().name(), entityContainer.getEntityId().toString());
            toId = msg.getOriginator();
        } else {
            toId = EntityIdFactory.getByTypeAndId(entityContainer.getEntityType().name(), entityContainer.getEntityId().toString());
            fromId = msg.getOriginator();
        }
    }

    @Data
    @AllArgsConstructor
    private static class Entitykey {
        private String entityName;
        private String type;
        private EntityType entityType;
    }

    private static class EntityCacheLoader extends CacheLoader<Entitykey, EntityContainer> {

        private final TbContext ctx;
        private final boolean createIfNotExists;

        private EntityCacheLoader(TbContext ctx, boolean createIfNotExists) {
            this.ctx = ctx;
            this.createIfNotExists = createIfNotExists;
        }

        @Override
        public EntityContainer load(Entitykey key) {
            return loadEntity(key);
        }

        private EntityContainer loadEntity(Entitykey entitykey) {
            EntityType type = entitykey.getEntityType();
            EntityContainer targetEntity = new EntityContainer();
            targetEntity.setEntityType(type);
            switch (type) {
                case DEVICE:
                    DeviceService deviceService = ctx.getDeviceService();
                    Device device = deviceService.findDeviceByTenantIdAndName(ctx.getTenantId(), entitykey.getEntityName());
                    if (device != null) {
                        targetEntity.setEntityId(device.getId());
                    } else if (createIfNotExists) {
                        Device newDevice = new Device();
                        newDevice.setName(entitykey.getEntityName());
                        newDevice.setType(entitykey.getType());
                        newDevice.setTenantId(ctx.getTenantId());
                        Device savedDevice = deviceService.saveDevice(newDevice);
                        targetEntity.setEntityId(savedDevice.getId());
                    }
                    break;
                case ASSET:
                    AssetService assetService = ctx.getAssetService();
                    Asset asset = assetService.findAssetByTenantIdAndName(ctx.getTenantId(), entitykey.getEntityName());
                    if (asset != null) {
                        targetEntity.setEntityId(asset.getId());
                    } else if (createIfNotExists) {
                        Asset newAsset = new Asset();
                        newAsset.setName(entitykey.getEntityName());
                        newAsset.setType(entitykey.getType());
                        newAsset.setTenantId(ctx.getTenantId());
                        Asset savedAsset = assetService.saveAsset(newAsset);
                        targetEntity.setEntityId(savedAsset.getId());
                    }
                    break;
                case CUSTOMER:
                    CustomerService customerService = ctx.getCustomerService();
                    Optional<Customer> customerOptional = customerService.findCustomerByTenantIdAndTitle(ctx.getTenantId(), entitykey.getEntityName());
                    if (customerOptional.isPresent()) {
                        targetEntity.setEntityId(customerOptional.get().getId());
                    } else if (createIfNotExists) {
                        Customer newCustomer = new Customer();
                        newCustomer.setTitle(entitykey.getEntityName());
                        newCustomer.setTenantId(ctx.getTenantId());
                        Customer savedCustomer = customerService.saveCustomer(newCustomer);
                        targetEntity.setEntityId(savedCustomer.getId());
                    }
                    break;
                case TENANT:
                    targetEntity.setEntityId(ctx.getTenantId());
                    break;
                case ENTITY_VIEW:
                    EntityViewService entityViewService = ctx.getEntityViewService();
                    EntityView entityView = entityViewService.findEntityViewByTenantIdAndName(ctx.getTenantId(), entitykey.getEntityName());
                    if (entityView != null) {
                        targetEntity.setEntityId(entityView.getId());
                    }
                    break;
                case DASHBOARD:
                    DashboardService dashboardService = ctx.getDashboardService();
                    TextPageData<DashboardInfo> dashboardInfoTextPageData = dashboardService.findDashboardsByTenantId(ctx.getTenantId(), new TextPageLink(200, entitykey.getEntityName()));
                    for (DashboardInfo dashboardInfo : dashboardInfoTextPageData.getData()) {
                        if (dashboardInfo.getTitle().equals(entitykey.getEntityName())) {
                            targetEntity.setEntityId(dashboardInfo.getId());
                        }
                    }
                    break;
                default:
                    return targetEntity;
            }
            return targetEntity;
        }


    }


}
