/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.osgi;

import java.util.Collection;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataBuilder;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.TypeContributor;
import org.hibernate.boot.registry.BootstrapServiceRegistry;
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.registry.selector.StrategyRegistrationProvider;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.internal.CoreMessageLogger;

import org.jboss.logging.Logger;

import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.wiring.BundleWiring;

/**
 * Hibernate 4.2 and 4.3 still heavily rely on TCCL for ClassLoading.  Although
 * our ClassLoaderService removed some of the reliance, access to the proper ClassLoader
 * via TCCL is still required in a few cases where we call out to external libs.  An OSGi
 * bundle manually creating a SessionFactory would require numerous ClassLoader
 * tricks (or may be impossible altogether).
 * <p/>
 * In order to fully control the TCCL issues and shield users from the
 * knowledge, we're requiring that bundles use this OSGi ServiceFactory.  It
 * configures and provides a SessionFactory as an OSGi service.
 * <p/>
 * Note that an OSGi ServiceFactory differs from a Service.  The ServiceFactory
 * allows individual instances of Services to be created and provided to
 * multiple client Bundles.
 *
 * @author Brett Meyer
 * @author Tim Ward
 */
public class OsgiSessionFactoryService implements ServiceFactory {
	private static final CoreMessageLogger LOG = Logger.getMessageLogger(CoreMessageLogger.class,
			OsgiSessionFactoryService.class.getName());
	
	private OsgiClassLoader osgiClassLoader;
	private OsgiJtaPlatform osgiJtaPlatform;
	private OsgiServiceUtil osgiServiceUtil;

	/**
	 * Constructs a OsgiSessionFactoryService
	 *
	 * @param osgiClassLoader The OSGi-specific ClassLoader created in HibernateBundleActivator
	 * @param osgiJtaPlatform The OSGi-specific JtaPlatform created in HibernateBundleActivator
	 * @param osgiServiceUtil Util object built in HibernateBundleActivator
	 */
	public OsgiSessionFactoryService(
			OsgiClassLoader osgiClassLoader,
			OsgiJtaPlatform osgiJtaPlatform,
			OsgiServiceUtil osgiServiceUtil) {
		this.osgiClassLoader = osgiClassLoader;
		this.osgiJtaPlatform = osgiJtaPlatform;
		this.osgiServiceUtil = osgiServiceUtil;
	}

	@Override
	public Object getService(Bundle requestingBundle, ServiceRegistration registration) {
		osgiClassLoader.addBundle( requestingBundle );

		final BootstrapServiceRegistryBuilder bsrBuilder = new BootstrapServiceRegistryBuilder();
		bsrBuilder.applyClassLoaderService( new OSGiClassLoaderServiceImpl( osgiClassLoader, osgiServiceUtil ) );

		final Integrator[] integrators = osgiServiceUtil.getServiceImpls( Integrator.class );
		for ( Integrator integrator : integrators ) {
			bsrBuilder.applyIntegrator( integrator );
		}

		final StrategyRegistrationProvider[] strategyRegistrationProviders
				= osgiServiceUtil.getServiceImpls( StrategyRegistrationProvider.class );
		for ( StrategyRegistrationProvider strategyRegistrationProvider : strategyRegistrationProviders ) {
			bsrBuilder.withStrategySelectors( strategyRegistrationProvider );
		}

		final BootstrapServiceRegistry bsr = bsrBuilder.build();
		final StandardServiceRegistryBuilder ssrBuilder = new StandardServiceRegistryBuilder( bsr );

		// Allow bundles to put the config file somewhere other than the root level.
		final BundleWiring bundleWiring = (BundleWiring) requestingBundle.adapt( BundleWiring.class );
		final Collection<String> cfgResources = bundleWiring.listResources( "/", "hibernate.cfg.xml",
																			BundleWiring.LISTRESOURCES_RECURSE );
		if (cfgResources.size() == 0) {
			ssrBuilder.configure();
		}
		else {
			if (cfgResources.size() > 1) {
				LOG.warn( "Multiple hibernate.cfg.xml files found in the persistence bundle.  Using the first one discovered." );
			}
			String cfgResource = "/" + cfgResources.iterator().next();
			ssrBuilder.configure( cfgResource );
		}

		ssrBuilder.applySetting( AvailableSettings.JTA_PLATFORM, osgiJtaPlatform );

		final StandardServiceRegistry ssr = ssrBuilder.build();

		final MetadataBuilder metadataBuilder = new MetadataSources( ssr ).getMetadataBuilder();
		final TypeContributor[] typeContributors = osgiServiceUtil.getServiceImpls( TypeContributor.class );
		for ( TypeContributor typeContributor : typeContributors ) {
			metadataBuilder.applyTypes( typeContributor );
		}

		return metadataBuilder.build().buildSessionFactory();
	}

	@Override
	public void ungetService(Bundle requestingBundle, ServiceRegistration registration, Object service) {
		((SessionFactory) service).close();
	}

}
