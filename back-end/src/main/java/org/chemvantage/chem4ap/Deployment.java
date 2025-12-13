package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.net.URI;
import java.net.URL;
import java.util.Date;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

/**
 * LTI 1.3 Platform Deployment configuration for CHEM4AP.
 * Stores authentication URLs, credentials, and LMS integration details.
 * Required for LTI authentication and Assignment and Grade Service (AGS) integration.
 */
@Entity
public class Deployment implements java.lang.Cloneable {
	@Id 	String platform_deployment_id;
	@Index	String client_id;
			String platformId;
			String oauth_access_token_url;
			String oidc_auth_url;
			String well_known_jwks_url;
			String email;
			String contact_name;
			String organization;
			String org_url;
			String lms_type;
			String rsa_key_id;
			String scope;
			String claims;
	@Index	String status;
	@Index	Date   created;
	@Index	Date   lastLogin;
			Date   expires;
	public	int    price = 0; // default monthly subscription price in $USD for individual student users
			int    nLicensesRemaining = 5; // default number of free student licenses
			
	/**
	 * LTI AGS (Assignment and Grade Service) OAuth 2.0 scope string.
	 * Defines permissions for accessing lineitem, result, and score APIs.
	 * @see https://www.imsglobal.org/spec/lti-ags/v2p0
	 */
	static final String LTI_AGS_SCOPE = 
		"https://purl.imsglobal.org/spec/lti-ags/scope/lineitem "
		+ "https://purl.imsglobal.org/spec/lti-ags/scope/lineitem.readonly "
		+ "https://purl.imsglobal.org/spec/lti-ags/scope/result.readonly "
		+ "https://purl.imsglobal.org/spec/lti-ags/scope/score "
		+ "https://purl.imsglobal.org/spec/lti-nrps/scope/contextmembership.readonly";
			
	/**
	 * Default constructor for Objectify persistence.
	 */
	Deployment() {}
	
	/**
	 * Creates a new LTI 1.3 deployment configuration.
	 * Validates all URLs for HTTPS protocol and constructs deployment identifier.
	 * 
	 * @param platform_id The LTI platform base URL (must use HTTPS, trailing slash removed)
	 * @param deployment_id The LTI deployment identifier (can be null/empty)
	 * @param client_id The OAuth 2.0 client ID for this deployment
	 * @param oidc_auth_url The OIDC authorization endpoint URL (must be HTTPS)
	 * @param oauth_access_token_url The OAuth 2.0 token endpoint URL (must be HTTPS)
	 * @param well_known_jwks_url The JWKS (JSON Web Key Set) endpoint URL (must be HTTPS)
	 * @param contact_name Primary contact name for the deployment
	 * @param email Contact email address
	 * @param organization Organization/institution name
	 * @param org_url Organization website URL
	 * @param lms LMS type identifier (e.g., "Canvas", "Blackboard")
	 * @throws Exception if any URL uses non-HTTPS protocol or URL parsing fails
	 * @throws IllegalArgumentException if required parameters are null
	 */
	Deployment(String platform_id, String deployment_id, String client_id, String oidc_auth_url, 
			String oauth_access_token_url, String well_known_jwks_url, String contact_name, String email, 
			String organization, String org_url, String lms) 
			throws Exception {
		// Validate required parameters
		if (platform_id == null || platform_id.isEmpty()) {
			throw new IllegalArgumentException("platform_id cannot be null or empty");
		}
		if (client_id == null || client_id.isEmpty()) {
			throw new IllegalArgumentException("client_id cannot be null or empty");
		}
		if (oidc_auth_url == null) {
			throw new IllegalArgumentException("oidc_auth_url cannot be null");
		}
		if (oauth_access_token_url == null) {
			throw new IllegalArgumentException("oauth_access_token_url cannot be null");
		}
		if (well_known_jwks_url == null) {
			throw new IllegalArgumentException("well_known_jwks_url cannot be null");
		}
		
		// Ensure that the platform_id is secure and does not end in a slash
		if (platform_id.endsWith("/")) {
			platform_id = platform_id.substring(0, platform_id.length() - 1);
		}
		if (deployment_id == null) {
			deployment_id = "";
		}
		
		// Validate all URLs use HTTPS protocol
		URL platform = new URL(platform_id);
		if (!platform.getProtocol().equals("https")) {
			throw new Exception("Platform URL must be secure (https): " + platform_id);
		}
		this.platformId = platform_id;
		this.platform_deployment_id = platform_id + "/" + deployment_id;
		
		URL auth = new URL(oidc_auth_url);
		if (!auth.getProtocol().equals("https")) {
			throw new Exception("OIDC auth URL must be secure (https): " + oidc_auth_url);
		}
		this.oidc_auth_url = auth.toString();
		
		URL token = new URL(oauth_access_token_url);
		if (!token.getProtocol().equals("https")) {
			throw new Exception("OAuth token URL must be secure (https): " + oauth_access_token_url);
		}
		this.oauth_access_token_url = token.toString();		
		
		URL jwks = new URL(well_known_jwks_url);
		if (!jwks.getProtocol().equals("https")) {
			throw new Exception("JWKS URL must be secure (https): " + well_known_jwks_url);
		}
		this.well_known_jwks_url = jwks.toString();
		
		this.client_id = client_id;
		this.contact_name = contact_name;
		this.email = email;
		this.organization = organization;
		this.org_url = org_url;
		this.lms_type = lms;
		this.rsa_key_id = KeyStore.getAKeyId(lms);
		this.created = new Date();
		this.scope = LTI_AGS_SCOPE;
	}
			
	/**
	 * Retrieves a deployment configuration by platform_deployment_id.
	 * @param platform_deployment_id The unique deployment identifier
	 * @return The Deployment, or null if not found
	 */
	static Deployment getInstance(String platform_deployment_id) {
		if (platform_deployment_id == null || platform_deployment_id.isEmpty()) {
			return null;
		}
		try {
			return ofy().load().type(Deployment.class).id(platform_deployment_id).safe();
		} catch (Exception e) {
			// Log error in production; currently returns null for backward compatibility
			return null;
		}
	}
	
	/**
	 * Extracts the deployment ID from the platform_deployment_id.
	 * @return The deployment ID portion (everything after the platform URL)
	 * @throws IllegalStateException if platformId cannot be determined
	 */
	String getDeploymentId() {
		String platformId = getPlatformId();
		if (platformId == null) {
			throw new IllegalStateException("Unable to determine platform ID from " + this.platform_deployment_id);
		}
		return this.platform_deployment_id.substring(platformId.length() + 1);
	}
	
	/**
	 * Gets or derives the platform ID from platform_deployment_id.
	 * Lazily parses and caches the result if not already set.
	 * @return The platform ID, or null if parsing fails
	 */
	String getPlatformId() {
		if (this.platformId != null) {
			return this.platformId;
		}
		try {
			String path = new URI(platform_deployment_id).getPath();
			int j = platform_deployment_id.length() - path.length();
			this.platformId = platform_deployment_id.substring(0, j);
			ofy().save().entity(this);
			return this.platformId;
		} catch (Exception e) {
			// Log error in production; return null to maintain backward compatibility
			return null;
		}
	}
	
	/**
	 * Gets the platform deployment identifier.
	 * @return The full platform/deployment URL identifier
	 */
	public String getPlatformDeploymentId() {
		return this.platform_deployment_id;
	}
	
	/**
	 * Gets the number of free student licenses remaining.
	 * @return Number of licenses
	 */
	public int getNLicensesRemaining() {
		return this.nLicensesRemaining;
	}
	
	/**
	 * Updates the number of free student licenses remaining.
	 * @param n The new license count
	 */
	public void putNLicensesRemaining(int n) {
		this.nLicensesRemaining = n;
		ofy().save().entity(this);
	}
	
	/**
	 * Gets the organization/institution name.
	 * @return Organization name
	 */
	public String getOrganization() {
		return this.organization;
	}
	
	/**
	 * Gets the OAuth client ID.
	 * @return Client ID
	 */
	public String getClientId() {
		return this.client_id;
	}
	
	/**
	 * Gets the OIDC authorization endpoint URL.
	 * @return OIDC auth URL
	 */
	public String getOidcAuthUrl() {
		return this.oidc_auth_url;
	}
	
	/**
	 * Gets the OAuth 2.0 access token endpoint URL.
	 * @return Token endpoint URL
	 */
	public String getOAuthAccessTokenUrl() {
		return this.oauth_access_token_url;
	}
	
	/**
	 * Gets the JWKS (JSON Web Key Set) endpoint URL.
	 * @return JWKS URL
	 */
	public String getWellKnownJwksUrl() {
		return this.well_known_jwks_url;
	}
	
	/**
	 * Gets the contact name for this deployment.
	 * @return Contact name
	 */
	public String getContactName() {
		return this.contact_name;
	}
	
	/**
	 * Gets the contact email address.
	 * @return Email address
	 */
	public String getEmail() {
		return this.email;
	}
	
	/**
	 * Gets the LMS type identifier.
	 * @return LMS type (e.g., "Canvas", "Blackboard")
	 */
	public String getLmsType() {
		return this.lms_type;
	}
	
	/**
	 * Gets the RSA key ID used for JWT signing.
	 * @return RSA key ID
	 */
	public String getRsaKeyId() {
		return this.rsa_key_id;
	}
	
	/**
	 * Gets the deployment creation timestamp.
	 * @return Creation date
	 */
	public Date getCreated() {
		return this.created;
	}
	
	/**
	 * Gets the last login timestamp.
	 * @return Last login date
	 */
	public Date getLastLogin() {
		return this.lastLogin;
	}
	
	/**
	 * Checks if this deployment is equivalent to another.
	 * @param d The deployment to compare with
	 * @return true if all significant fields match
	 */
	boolean equivalentTo(Deployment d) {
		if (d == null) {
			return false;
		}
		return fieldsEqual(this.platform_deployment_id, d.platform_deployment_id) &&
				fieldsEqual(this.client_id, d.client_id) &&
				fieldsEqual(this.oauth_access_token_url, d.oauth_access_token_url) &&
				fieldsEqual(this.oidc_auth_url, d.oidc_auth_url) &&
				fieldsEqual(this.well_known_jwks_url, d.well_known_jwks_url) &&
				fieldsEqual(this.email, d.email) &&
				fieldsEqual(this.contact_name, d.contact_name) &&
				fieldsEqual(this.organization, d.organization) &&
				fieldsEqual(this.org_url, d.org_url) &&
				fieldsEqual(this.lms_type, d.lms_type) &&
				fieldsEqual(this.rsa_key_id, d.rsa_key_id) &&
				fieldsEqual(this.scope, d.scope) &&
				this.price == d.price &&
				this.nLicensesRemaining == d.nLicensesRemaining &&
				fieldsEqual(this.created, d.created);
	}
	
	/**
	 * Helper method to compare two fields for equality, handling nulls.
	 * @param field1 First field to compare
	 * @param field2 Second field to compare
	 * @return true if both fields are null or both equal each other
	 */
	private static boolean fieldsEqual(Object field1, Object field2) {
		if (field1 == null && field2 == null) {
			return true;
		}
		if (field1 == null || field2 == null) {
			return false;
		}
		return field1.equals(field2);
	}
	
	/**
	 * Creates a shallow clone of this deployment.
	 * @return A cloned Deployment instance
	 * @throws CloneNotSupportedException if cloning is not supported
	 */
	@Override
	protected Deployment clone() throws CloneNotSupportedException {
		return (Deployment) super.clone();
	}
}




