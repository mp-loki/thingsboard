/**
 * Copyright © 2016-2021 The Thingsboard Authors
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
package org.thingsboard.server.dao.resource;

import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.transport.resource.Resource;
import org.thingsboard.server.common.data.transport.resource.ResourceType;


public interface ResourceService {
    Resource saveResource(Resource resource);

    Resource getResource(TenantId tenantId, ResourceType resourceType, String resourceId);

    PageData<Resource> findResourcesByTenantId(TenantId tenantId, PageLink pageLink);

    void deleteResource(TenantId tenantId, ResourceType resourceType, String resourceId);

    void deleteResourcesByTenantId(TenantId tenantId);
}
