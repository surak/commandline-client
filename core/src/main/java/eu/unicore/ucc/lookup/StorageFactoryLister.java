package eu.unicore.ucc.lookup;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Logger;

import de.fzj.unicore.uas.util.Pair;
import eu.unicore.client.Endpoint;
import eu.unicore.client.core.EnumerationClient;
import eu.unicore.client.core.SiteClient;
import eu.unicore.client.core.StorageFactoryClient;
import eu.unicore.client.lookup.AddressFilter;
import eu.unicore.client.lookup.Lister;
import eu.unicore.client.lookup.Producer;
import eu.unicore.client.registry.IRegistryClient;
import eu.unicore.client.registry.RegistryClient;
import eu.unicore.services.rest.client.IAuthCallback;
import eu.unicore.ucc.authn.UCCConfigurationProvider;
import eu.unicore.util.Log;
import eu.unicore.util.httpclient.IClientConfiguration;

public class StorageFactoryLister extends Lister<StorageFactoryClient>{

	final static Logger log = Log.getLogger(Log.CLIENT, StorageFactoryLister.class);
	
	private final IRegistryClient registry;

	private final UCCConfigurationProvider configurationProvider;

	public StorageFactoryLister(IRegistryClient registry, UCCConfigurationProvider configurationProvider, String[] tags){
		this(null, registry, configurationProvider, new AcceptAllFilter());
	}
	
	/**
	 * @param executor
	 * @param registry
	 * @param configurationProvider
	 */
	public StorageFactoryLister(ExecutorService executor, IRegistryClient registry, 
			UCCConfigurationProvider configurationProvider){
		this(executor,registry,configurationProvider,new AcceptAllFilter());
	}
	
	/**
	 * 
	 * @param executor
	 * @param registry
	 * @param configurationProvider
	 * @param addressFilter - filter for accepting/rejecting service URLs 
	 */
	public StorageFactoryLister(ExecutorService executor, IRegistryClient registry, 
			UCCConfigurationProvider configurationProvider, AddressFilter addressFilter){
		super();
		this.registry = registry;
		this.configurationProvider = configurationProvider;
		setAddressFilter(addressFilter);
		setExecutor(executor);
	}
	
	@Override
	public Iterator<StorageFactoryClient> iterator() {
		try{
			setupProducers();
		}
		catch(Exception ex){
			throw new RuntimeException(ex);
		}
		return super.iterator();
	}

	protected void setupProducers()throws Exception {
		List<Endpoint>sites = registry.listEntries(new RegistryClient.ServiceTypeFilter("CoreServices"));
		for(final Endpoint site: sites){
			addProducer(new StorageFactoryProducer(site,
					configurationProvider.getClientConfiguration(site.getUrl()),
					configurationProvider.getRESTAuthN(), addressFilter));
		}
	}

	public static class StorageFactoryProducer implements Producer<StorageFactoryClient>{

		private final Endpoint ep;
		private final IClientConfiguration securityProperties;
		private final IAuthCallback auth;
		private final List<Pair<Endpoint,String>>errors = new ArrayList<>();
		private final AddressFilter addressFilter;

		private BlockingQueue<StorageFactoryClient> target;
		private AtomicInteger runCounter;

		public StorageFactoryProducer(Endpoint ep, IClientConfiguration securityProperties, IAuthCallback auth, AddressFilter addressFilter) {
			this.ep = ep;
			this.securityProperties = securityProperties;
			this.auth = auth;
			this.addressFilter = addressFilter;
		}

		@Override
		public void run() {
			try{
				log.debug("Processing site at {}", ep.getUrl());
				handleEndpoint();
			}
			catch(Exception ex){
				errors.add(new Pair<>(ep,Log.createFaultMessage("", ex)));
			}
			finally{
				runCounter.decrementAndGet();
			}
		}
		
		private void handleEndpoint() throws Exception {
			SiteClient c = new SiteClient(ep, securityProperties, auth);
			Endpoint factoriesEp = ep.cloneTo(c.getLinkUrl("storagefactories"));
			EnumerationClient factoriesList = new EnumerationClient(factoriesEp, securityProperties, auth);
			for(String factoryURL: factoriesList) {
				if(addressFilter.accept(factoryURL)) {
					StorageFactoryClient f = new StorageFactoryClient(c.getEndpoint().cloneTo(factoryURL),
							securityProperties, auth);
					if(addressFilter.accept(f)) {
						target.offer(f);
					}
				}
			}
		}

		@Override
		public void init(BlockingQueue<StorageFactoryClient> target, AtomicInteger runCount) {
			this.target = target;
			this.runCounter = runCount;
		}
	}
}
