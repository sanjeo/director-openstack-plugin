/*
 * Copyright (c) 2015 Intel Corporation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.director.openstack.nova;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.jclouds.ContextBuilder;
import org.jclouds.apis.ApiMetadata;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.openstack.nova.v2_0.NovaApi;
import org.jclouds.openstack.nova.v2_0.NovaApiMetadata;
import org.jclouds.openstack.nova.v2_0.domain.Address;
import org.jclouds.openstack.nova.v2_0.domain.FloatingIP;
import org.jclouds.openstack.nova.v2_0.domain.Server;
import org.jclouds.openstack.nova.v2_0.domain.Server.Status;
import org.jclouds.openstack.nova.v2_0.domain.ServerCreated;
import org.jclouds.openstack.nova.v2_0.extensions.FloatingIPApi;
import org.jclouds.openstack.nova.v2_0.features.ServerApi;
import org.jclouds.openstack.nova.v2_0.options.CreateServerOptions;
import org.jclouds.openstack.v2_0.domain.PaginatedCollection;
import org.jclouds.openstack.v2_0.options.PaginationOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.cloudera.director.openstack.nova.NovaProviderConfigurationProperty.REGION;
import static com.cloudera.director.openstack.nova.NovaInstanceTemplateConfigurationProperty.IMAGE;
import static com.cloudera.director.openstack.nova.NovaInstanceTemplateConfigurationProperty.NETWORK_ID;
import static com.cloudera.director.openstack.nova.NovaInstanceTemplateConfigurationProperty.TYPE;
import static com.cloudera.director.openstack.nova.NovaInstanceTemplateConfigurationProperty.SECURITY_GROUP_NAMES;
import static com.cloudera.director.openstack.nova.NovaInstanceTemplateConfigurationProperty.AVAILABILITY_ZONE;
import static com.cloudera.director.openstack.nova.NovaInstanceTemplateConfigurationProperty.KEY_NAME;
import static com.cloudera.director.openstack.nova.NovaInstanceTemplateConfigurationProperty.FLOATING_IP_POOL;

import com.cloudera.director.openstack.OpenStackCredentials;
import com.cloudera.director.spi.v1.compute.util.AbstractComputeProvider;
import com.cloudera.director.spi.v1.model.ConfigurationProperty;
import com.cloudera.director.spi.v1.model.Configured;
import com.cloudera.director.spi.v1.model.InstanceState;
import com.cloudera.director.spi.v1.model.LocalizationContext;
import com.cloudera.director.spi.v1.model.Resource.Type;
import com.cloudera.director.spi.v1.model.exception.PluginExceptionConditionAccumulator;
import com.cloudera.director.spi.v1.model.exception.PluginExceptionDetails;
import com.cloudera.director.spi.v1.model.exception.UnrecoverableProviderException;
import com.cloudera.director.spi.v1.model.util.SimpleResourceTemplate;
import com.cloudera.director.spi.v1.provider.ResourceProviderMetadata;
import com.cloudera.director.spi.v1.provider.util.SimpleResourceProviderMetadata;
import com.cloudera.director.spi.v1.util.ConfigurationPropertiesUtil;
import com.google.common.base.Optional;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.BiMap;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.inject.Module;
import com.typesafe.config.Config;

public class NovaProvider extends AbstractComputeProvider<NovaInstance, NovaInstanceTemplate> {

	private static final Logger LOG = LoggerFactory.getLogger(NovaProvider.class);
	
	private static final ApiMetadata NOVA_API_METADATA = new NovaApiMetadata();
	
	/**
	 * The provider configuration properties.
	 */	
	protected static final List<ConfigurationProperty> CONFIGURATION_PROPERTIES =
		ConfigurationPropertiesUtil.asConfigurationPropertyList(
			NovaProviderConfigurationProperty.values());
	
	/**
	 * The resource provider ID.
	 */
	public static final String ID = NovaProvider.class.getCanonicalName();
	
	/**
	 * The resource provider metadata.
	 */
	public static final ResourceProviderMetadata METADATA = SimpleResourceProviderMetadata.builder()
		.id(ID)
		.name("Nova")
		.description("OpenStack Nova compute provider")
		.providerClass(NovaProvider.class)
		.providerConfigurationProperties(CONFIGURATION_PROPERTIES)
		.resourceTemplateConfigurationProperties(NovaInstanceTemplate.getConfigurationProperties())
		.resourceDisplayProperties(NovaInstance.getDisplayProperties())
		.build();
	
	/*
	 * The credentials of the OpenStack environment
	 */
	private OpenStackCredentials credentials;
	
	/*
	 * The configuration of the OpenStack environment
	 */
	@SuppressWarnings("unused")
	private Config openstackConfig;
	
	/*
	 * The nova api for OpenStack Nova service
	 */
	private final NovaApi novaApi;
	
	/*
	 * Region of the provider
	 */
	private String region;
	
	
	public NovaProvider(Configured configuration, OpenStackCredentials credentials,
			Config openstackConfig, LocalizationContext localizationContext) {
		super(configuration, METADATA, localizationContext);
		this.credentials = credentials;
		this.openstackConfig = openstackConfig;
		this.novaApi = buildNovaAPI();
		this.region = configuration.getConfigurationValue(REGION, localizationContext);
	}
	
	public NovaApi getNovaApi() {
		return novaApi;
	}
	
	public String getRegion() {
		return region;
	}
	
	
	private NovaApi buildNovaAPI() {	
		Iterable<Module> modules = ImmutableSet.<Module>of(new SLF4JLoggingModule());
		String endpoint = credentials.getEndpoint();
		String identity = credentials.getIdentity();
		String credential = credentials.getCredential();
		
		
		return ContextBuilder.newBuilder(NOVA_API_METADATA)
				.endpoint(endpoint)
				.credentials(identity, credential)
				.modules(modules)
				.buildApi(NovaApi.class);
	}
	
	public NovaInstanceTemplate createResourceTemplate(String name,
			Configured configuration, Map<String, String> tags) {
		return new NovaInstanceTemplate(name, configuration, tags, this.getLocalizationContext());
	}
	
	
	private void createAndAssignFloatingIP(FloatingIPApi floatingIpApi,
			String floatingipPool, String instanceId) {
		if (floatingipPool != null) {
	            FloatingIP floatingip = floatingIpApi.allocateFromPool(floatingipPool);
        	    floatingIpApi.addToServer(floatingip.getIp(), instanceId);
		}
	}

	public void allocate(NovaInstanceTemplate template, Collection<String> instanceIds,
			int minCount) throws InterruptedException {
		LocalizationContext providerLocalizationContext = getLocalizationContext();
		LocalizationContext templateLocalizationContext =
				SimpleResourceTemplate.getTemplateLocalizationContext(providerLocalizationContext);
		
		// Provisioning the cluster
		ServerApi  serverApi = novaApi.getServerApi(region);
		Optional<FloatingIPApi> floatingIpApi = novaApi.getFloatingIPApi(region);
		final Set<String> instancesWithNoPrivateIp = Sets.newHashSet();
		
		String image = template.getConfigurationValue(IMAGE, templateLocalizationContext);
		String flavor = template.getConfigurationValue(TYPE, templateLocalizationContext);
		String network = template.getConfigurationValue(NETWORK_ID, templateLocalizationContext);
		String azone = template.getConfigurationValue(AVAILABILITY_ZONE, templateLocalizationContext);
		String securityGroups = template.getConfigurationValue(SECURITY_GROUP_NAMES, templateLocalizationContext);
		String keyName = template.getConfigurationValue(KEY_NAME, templateLocalizationContext);
		String floatingipPool = template.getConfigurationValue(FLOATING_IP_POOL, templateLocalizationContext);
		List<String> securityGroupNames = NovaInstanceTemplate.CSV_SPLITTER.splitToList(securityGroups);
		
		for (String currentId : instanceIds) {
			String decoratedInstanceName = decorateInstanceName(template, currentId, templateLocalizationContext);

			// Tag all the new instances so that we can easily find them later on
			Map<String, String> tags = new HashMap<String, String>();
			tags.put("DIRECTOR_ID", currentId);
			tags.put("INSTANCE_NAME", decoratedInstanceName);
			
			CreateServerOptions createServerOps = new CreateServerOptions()
								.keyPairName(keyName)
								.networks(network)
								.availabilityZone(azone)
								.securityGroupNames(securityGroupNames)
								.metadata(tags);
			
			ServerCreated currentServer = serverApi.create(decoratedInstanceName, image, flavor, createServerOps);
			
			String novaInstanceId = currentServer.getId();			
			while (novaInstanceId.isEmpty()) {
				TimeUnit.SECONDS.sleep(5);
				novaInstanceId = currentServer.getId();
			}
			
			if (serverApi.get(novaInstanceId).getAddresses() == null) {
				instancesWithNoPrivateIp.add(novaInstanceId);
			} else {
				createAndAssignFloatingIP(floatingIpApi.get(), floatingipPool, novaInstanceId);
				LOG.info("<< Instance {} got IP {}", novaInstanceId, serverApi.get(novaInstanceId).getAccessIPv4());
			}
		}
		
		// Wait until all of them to have a private IP
		int totalTimePollingSeconds = 0;
		int pollingTimeoutSeconds = 180;
		boolean timeoutExceeded = false;
		while (!instancesWithNoPrivateIp.isEmpty() && !timeoutExceeded) {
			LOG.info(">> Waiting for {} instance(s) to be active",
					instancesWithNoPrivateIp.size());
		    
			for (String novaInstanceId : instancesWithNoPrivateIp) {
				if (serverApi.get(novaInstanceId).getAddresses() != null) {
					instancesWithNoPrivateIp.remove(novaInstanceId);
					createAndAssignFloatingIP(floatingIpApi.get(), floatingipPool, novaInstanceId);
				}
			}
			
			if (!instancesWithNoPrivateIp.isEmpty()) {
				LOG.info("Waiting 5 seconds until next check, {} instance(s) still don't have an IP",
						instancesWithNoPrivateIp.size());
				
				if (totalTimePollingSeconds > pollingTimeoutSeconds) {
					timeoutExceeded = true;
		        }        
				TimeUnit.SECONDS.sleep(5);
				totalTimePollingSeconds += 5;
			}
		}
		
		int successfulOperationCount = instanceIds.size() - instancesWithNoPrivateIp.size();
		if (successfulOperationCount < minCount) {
			PluginExceptionConditionAccumulator accumulator = new PluginExceptionConditionAccumulator();
			BiMap<String, String> virtualInstanceIdsByNovaInstanceId = 
					getNovaInstanceIdsByVirtualInstanceId(instanceIds);
			
			for (String currentId : instanceIds) {
				try{
					String novaInstanceId = virtualInstanceIdsByNovaInstanceId.get(currentId);
					novaApi.getServerApi(region).delete(novaInstanceId);
				} catch (Exception e) {
					accumulator.addError(null, e.getMessage());
				}
			}
			PluginExceptionDetails pluginExceptionDetails = new PluginExceptionDetails(accumulator.getConditionsByKey());
			throw new UnrecoverableProviderException("Problem allocating instances.", pluginExceptionDetails);
		}
	}
	
	private String findFloatingIPByAddress(FloatingIPApi floatingIpApi, String floatingIp) {
		FluentIterable<FloatingIP> floatingipList = floatingIpApi.list();
		for ( FloatingIP ip : floatingipList) {
			if (ip.getIp().compareTo(floatingIp) == 0) {
				return ip.getId();
			}
		}
		return null;
	}

	public void delete(NovaInstanceTemplate template, Collection<String> virtualInstanceIds)
			throws InterruptedException {
		if (virtualInstanceIds.isEmpty()) {
			return;
		}
		
		BiMap<String, String> virtualInstanceIdsByNovaInstanceId = 
				getNovaInstanceIdsByVirtualInstanceId(virtualInstanceIds);
		
		ServerApi serverApi = novaApi.getServerApi(region);
		Optional<FloatingIPApi> floatingIpApi = novaApi.getFloatingIPApi(region);
		
		for (String currentId : virtualInstanceIds) {
			String novaInstanceId = virtualInstanceIdsByNovaInstanceId.get(currentId);
			
			//find the floating IP address if it exists
			String floatingIp = null;
			Iterator<Address> iterator = serverApi.get(novaInstanceId).getAddresses().values().iterator();
			if (iterator.hasNext()) {
				//discard the first one (the fixed IP)
				iterator.next();
				if (iterator.hasNext()) {
					floatingIp = iterator.next().getAddr();
				}
			}
			
			//disassociate and delete the floating IP
			if (floatingIp != null) {
				String floatingipID = findFloatingIPByAddress(floatingIpApi.get(), floatingIp);
				floatingIpApi.get().removeFromServer(floatingIp, novaInstanceId);
				floatingIpApi.get().delete(floatingipID);
			}
			
			//delete the server
			boolean deleted = serverApi.delete(novaInstanceId);
			if (!deleted) {
				LOG.info("Unable to terminate instance {}", novaInstanceId);
			}
		}
	}

	public Collection<NovaInstance> find(NovaInstanceTemplate template,
			Collection<String> virtualInstanceIds) throws InterruptedException {
		
		final Collection<NovaInstance> novaInstances =
				Lists.newArrayListWithExpectedSize(virtualInstanceIds.size());
		BiMap<String, String> virtualInstanceIdsByNovaInstanceId = 
				getNovaInstanceIdsByVirtualInstanceId(virtualInstanceIds);
		
		ServerApi serverApi = novaApi.getServerApi(region);
		
		for (String currentId : virtualInstanceIds) {
			String novaInstanceId = virtualInstanceIdsByNovaInstanceId.get(currentId);
			novaInstances.add(new NovaInstance(template, currentId, serverApi.get(novaInstanceId)));
		}
		
		return novaInstances;
	}

	public Map<String, InstanceState> getInstanceState(NovaInstanceTemplate template, 
			Collection<String> virtualInstanceIds) {
		
		Map<String, InstanceState> instanceStateByInstanceId = new HashMap<String, InstanceState >();
		
		BiMap<String, String> virtualInstanceIdsByNovaInstanceId = 
				getNovaInstanceIdsByVirtualInstanceId(virtualInstanceIds);
		  
		for (String currentId : virtualInstanceIds) {
			String novaInstanceId = virtualInstanceIdsByNovaInstanceId.get(currentId);
			if (novaInstanceId == null) {
				InstanceState instanceStateDel = NovaInstanceState.fromInstanceStateName(Status.DELETED);
				instanceStateByInstanceId.put(currentId, instanceStateDel);
				continue;	
			}
			Status instance_state =  novaApi.getServerApi(region).get(novaInstanceId).getStatus();
			InstanceState instanceState = NovaInstanceState.fromInstanceStateName(instance_state);
			instanceStateByInstanceId.put(currentId, instanceState);
		}
		
		return instanceStateByInstanceId;
	}	

	public Type getResourceType() {
		return NovaInstance.TYPE;
	}
	
	private static String decorateInstanceName(NovaInstanceTemplate template, String currentId,
		      LocalizationContext templateLocalizationContext){
		return template.getInstanceNamePrefix() + "-" + currentId;
	}
	
	/**
	 * Returns a map from virtual instance ID to corresponding instance ID for the specified
	 * virtual instance IDs.
	 *
	 * @param virtualInstanceIds the virtual instance IDs
	 * @return the map from virtual instance ID to corresponding Nova instance ID
	 */
	private BiMap<String, String> getNovaInstanceIdsByVirtualInstanceId(
	      Collection<String> virtualInstanceIds) {
		final BiMap<String, String> novaInstanceIdsByVirtualInstanceId = HashBiMap.create();
		for (String instanceName : virtualInstanceIds) {
			ListMultimap<String, String> multimap = ArrayListMultimap.create();
			multimap.put("name", instanceName) ;
			ServerApi serverApi = novaApi.getServerApi(region);
			PaginatedCollection<Server> servers = serverApi.listInDetail(PaginationOptions.Builder.queryParameters(multimap));
			if (servers.isEmpty()) {
				continue;
			}
			novaInstanceIdsByVirtualInstanceId.put(instanceName, servers.get(0).getId());	
		}
		
		return novaInstanceIdsByVirtualInstanceId;
	}
}
