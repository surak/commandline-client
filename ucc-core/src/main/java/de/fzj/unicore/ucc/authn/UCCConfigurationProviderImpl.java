/*
th * Copyright (c) 2013 ICM Uniwersytet Warszawski All rights reserved.
 * See LICENCE.txt file for licensing information.
 */
package de.fzj.unicore.ucc.authn;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;

import de.fzj.unicore.ucc.Command;
import de.fzj.unicore.ucc.Constants;
import de.fzj.unicore.ucc.UCC;
import eu.emi.security.authn.x509.ValidationError;
import eu.emi.security.authn.x509.ValidationErrorListener;
import eu.emi.security.authn.x509.impl.X500NameUtils;
import eu.unicore.security.wsutil.client.authn.AuthenticationProvider;
import eu.unicore.security.wsutil.client.authn.CachingIdentityResolver;
import eu.unicore.security.wsutil.client.authn.ClientConfigurationProvider;
import eu.unicore.security.wsutil.client.authn.ClientConfigurationProviderImpl;
import eu.unicore.security.wsutil.client.authn.DelegationSpecification;
import eu.unicore.security.wsutil.client.authn.JsonSecuritySessionPersistence;
import eu.unicore.services.rest.client.IAuthCallback;
import eu.unicore.util.httpclient.IClientConfiguration;

/**
 * UCC specific extension of {@link ClientConfigurationProvider}. Configures the object in UCC way (from preferences)
 * and sets up user preferences from UCC options.
 * <p>
 * Support for plain SAML attribute assertions push is managed here with help of {@link SamlPushSupport}.
 * <p>
 * By default this implementation uses a {@link CachingIdentityResolver} however it is advised to configure
 * usage {@link RegistryIdentityResolver}.
 *    
 * @author K. Benedyczak
 */
public class UCCConfigurationProviderImpl extends ClientConfigurationProviderImpl 
		implements UCCConfigurationProvider
{
	public static final String PREFERENCE_ARG_VO = "vo";
	public static final String PREFERENCE_ARG_ROLE = "role";
	public static final String PREFERENCE_ARG_UID = "uid";
	public static final String PREFERENCE_ARG_PGID = "pgid";
	public static final String PREFERENCE_ARG_SUP_GIDS = "supgids";
	public static final String PREFERENCE_ARG_ADD_OS_GIDS = "useOSgids";
	public static final String PREFERENCE_ARG = "[see description]";
	public static final String PREFERENCE_ARG_HELP = PREFERENCE_ARG_VO + ":<val>|" + 
			PREFERENCE_ARG_ROLE + ":<val>|" + 
			PREFERENCE_ARG_UID + ":<val>|" +
			PREFERENCE_ARG_PGID + ":<val>|" +
			PREFERENCE_ARG_SUP_GIDS + ":<val1,val2,...>|" +
			PREFERENCE_ARG_ADD_OS_GIDS + ":<true|false>";
	
	private Properties userProperties;
	private Command command;
	
	public UCCConfigurationProviderImpl(String authnMethod, Properties userProperties, 
			Command command, boolean acceptAllCAs) throws Exception
	{
		super();
		this.userProperties = userProperties;
		this.command = command;
		AuthenticationProvider ap = UCC.getAuthNMethod(authnMethod);
		if (ap instanceof PropertiesAwareAuthn){
			
			if(userProperties.getProperty("truststore.type")==null) {
				if(acceptAllCAs) {
					userProperties.setProperty("truststore.type", "directory");
					userProperties.setProperty("truststore.directoryLocations.1", "___dummy___");
				}
				else {
					// fallback to a default truststore
					String path = new File(System.getProperty("user.home"), 
							".ucc"+File.separator+"trusted-certs"+File.separator+"*.pem").getPath();
					command.verbose("Default truststore "+path);
					userProperties.setProperty("truststore.type", "directory");
					userProperties.setProperty("truststore.directoryLocations.1", path);
				}
			}
			
			((PropertiesAwareAuthn)ap).setProperties(userProperties);
			acceptAll = acceptAllCAs;
			ValidationErrorListener vel = new ValidationErrorListener() {
				@Override
				public boolean onValidationError(final ValidationError error) {
					return queryValidity(error);
				}
			};
			((PropertiesAwareAuthn) ap).setValidationErrorListener(vel);
		}
		setAuthnProvider(ap);
		setBasicConfiguration(ap.getBaseClientConfiguration());
		setAnonymousConfiguration(ap.getAnonymousClientConfiguration());
	
		setSessionsPersistence(new JsonSecuritySessionPersistence(
				getBasicClientConfiguration().useSecuritySessions(), getSessionStorageFile()));
		getSessionsPersistence().readSessionIDs(getBasicClientConfiguration().getSessionIDProvider());
		setSecurityPreferences(setupExtraAttributes());
		setIdentityResolver(new CachingIdentityResolver());
	}
	
	@Override
	public IClientConfiguration getClientConfiguration(String serviceUrl) throws Exception {
		return getClientConfiguration(serviceUrl,null,DelegationSpecification.DO_NOT);
	}
	
	@Override
	public IClientConfiguration getClientConfiguration(String serviceUrl, String serviceIdentity, 
			DelegationSpecification delegate) throws Exception
	{
		if(DelegationSpecification.STANDARD.equals(delegate) && serviceIdentity==null){
			try{
				serviceIdentity = getIdentityResolver().resolveIdentity(serviceUrl);
			}catch(Exception ex){/*ignored*/}
		}
		return super.getClientConfiguration(serviceUrl, serviceIdentity, delegate);
	}
	
	protected String getSessionStorageFile() {
		if (userProperties != null) {
			 return userProperties.getProperty("ucc-session-ids");
		}
		return null;
	}

	@Override
	public Properties getUserProperties()
	{
		return userProperties;
	}
	
	@Override
	public IAuthCallback getRESTAuthN() {
		if(getAuthnProvider() instanceof IAuthCallback) {
			return (IAuthCallback)getAuthnProvider();
		}
		else return null;
	}
	
	/**
	 * Prepares a map with security preferences, to be quickly applied when actual configuration is 
	 * assembled on demand. 
	 * The base implementation adds the {@link Constants#OPT_SECURITY_PREFERENCES} and the 
	 * requested user ID, if given by the {@link Constants#OPT_USERID} option.
	 */
	private Map<String, String[]> setupExtraAttributes(){
		Map<String, String[]> securityPreferences = new HashMap<String, String[]>();
		String[] vals = command.getCommandLine().getOptionValues(Constants.OPT_SECURITY_PREFERENCES);
		if (vals == null) {
			String fromFile = userProperties.getProperty(Constants.OPT_SECURITY_PREFERENCES_LONG);
			if (fromFile != null)
				vals = fromFile.split("( )+");
		}
		if (vals != null) {
			for (String val: vals)
				parseSecurityPreference(val, securityPreferences);
		}
		return securityPreferences;
	}
	
	private void parseSecurityPreference(String pref, Map<String, String[]> securityPreferences) {
		if (pref.startsWith(PREFERENCE_ARG_UID + ":")) {
			String val = pref.substring(PREFERENCE_ARG_UID.length() + 1);
			securityPreferences.put("uid", new String[]{val});
		} else if (pref.startsWith(PREFERENCE_ARG_PGID + ":")) {
			String val = pref.substring(PREFERENCE_ARG_PGID.length() + 1);
			securityPreferences.put("group", new String[]{val});
		} else if (pref.startsWith(PREFERENCE_ARG_SUP_GIDS + ":")) {
			String val = pref.substring(PREFERENCE_ARG_SUP_GIDS.length() + 1);
			String vals[] = val.split(",");
			securityPreferences.put("supplementaryGroups", vals);
		} else if (pref.startsWith(PREFERENCE_ARG_ADD_OS_GIDS + ":")) {
			String val = pref.substring(PREFERENCE_ARG_ADD_OS_GIDS.length() + 1);
			if (!val.equals("true")&&!val.equals("false")) {
				command.error("Value of the " + PREFERENCE_ARG_ADD_OS_GIDS + 
						" preference must be 'true' or 'false', but not '" + 
						val + "'", null);
				command.endProcessing(Constants.ERROR_CLIENT);
			}
			securityPreferences.put("addDefaultGroups", new String[]{val});
		} else if (pref.startsWith(PREFERENCE_ARG_VO + ":")) {
			String val = pref.substring(PREFERENCE_ARG_VO.length() + 1);
			securityPreferences.put("selectedVirtualOrganisation", new String[]{val});
		} else if (pref.startsWith(PREFERENCE_ARG_ROLE + ":")) {
			String val = pref.substring(PREFERENCE_ARG_ROLE.length() + 1);
			securityPreferences.put("role", new String[]{val});
		} else {
			command.error("Wrong value '" + pref + "' of the option -" + Constants.OPT_SECURITY_PREFERENCES + 
					", must have the following format: " + PREFERENCE_ARG_HELP, null);
			command.endProcessing(Constants.ERROR_CLIENT);
		}
	}
	
	private static boolean acceptAll = false;
	
	private static ArrayList<String>acceptedCAs = new ArrayList<>();
	
	private boolean queryValidity(ValidationError err) {
		if(acceptAll)return true;
		
		String issuer = err.getChain()[0].getIssuerX500Principal().getName();
		String cmp = X500NameUtils.getComparableForm(issuer);
		if(acceptedCAs.contains(cmp))return true;
		
		System.err.println("VALIDATION ERROR : "+err.getMessage());
		try{
			LineReader cr = LineReaderBuilder.builder().build();
			String line = cr.readLine("Accept issuer <"+issuer+"> [Y/n/a]");
			boolean accept = line.length()==0  || line.startsWith("y") || line.startsWith("Y");
			acceptAll =  line.startsWith("a") || line.startsWith("A");
			if(accept||acceptAll){
				acceptedCAs.add(cmp);
			}
			return accept;
		}catch(Exception e){
			throw new RuntimeException(e);
		}
	}

}
