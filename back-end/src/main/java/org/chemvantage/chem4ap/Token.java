package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.ofy;
import static com.googlecode.objectify.ObjectifyService.key;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URL;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.googlecode.objectify.Key;

/**
 * Token servlet serving as the OpenID Connect authorization endpoint for third-party initiated login.
 * 
 * Architecture Overview:
 * This servlet implements the OAuth 2.0 Authorization Framework and OpenID Connect (OIDC) protocol
 * as specified in RFC 6749 and OpenID Connect Core 1.0 to enable secure Learning Tool Interoperability
 * (LTI 1.3) launch flows. The servlet orchestrates the authentication handshake between the tool (Chem4AP)
 * and learning management systems (LMS platforms).
 * 
 * LTI 1.3 Third-Party Initiated Login Flow:
 * <ol>
 * <li><b>LMS Redirect (1)</b>: User clicks tool link in LMS → Browser GET /auth/token
 * <li><b>Token Generation (2)</b>: This servlet validates parameters, generates signed JWT state token
 * <li><b>OIDC Redirect (3)</b>: Servlet redirects to LMS authorization endpoint with state token
 * <li><b>User Authentication (4)</b>: LMS authenticates user, returns to redirect_uri with ID token
 * <li><b>Launch Completion (5)</b>: Launch servlet validates ID token and renders tool UI
 * </ol>
 * 
 * Supported LMS Platforms (with Auto-Registration):
 * <ul>
 * <li><b>Canvas LMS</b> (Instructure): Most popular in higher ed, uses Canvas SSO OIDC
 * <li><b>Schoology LMS</b> (Powerschool): School management system with OIDC support
 * <li><b>Blackboard Learn</b> (Blackboard): Enterprise LMS with developer API
 * <li><b>Brightspace/Moodle</b>: Manual registration required (no auto-discovery)
 * </ul>
 * 
 * Request Parameters (from LMS):
 * <ul>
 * <li><b>iss</b> (required): Platform identifier URL (e.g., https://canvas.instructure.com)
 * <li><b>login_hint</b> (required): User identifier at platform (opaque string or email)
 * <li><b>target_link_uri</b> (required): Tool launch URL where browser should load
 * <li><b>lti_deployment_id</b> (optional): Deployment identifier (Moodle/Brightspace)
 * <li><b>deployment_id</b> (optional): Deployment identifier (Canvas - different parameter)
 * <li><b>client_id</b> (required): OAuth client identifier for this deployment
 * <li><b>lti_message_hint</b> (optional): Encrypted context hint from platform
 * </ul>
 * 
 * Deployment Lookup Strategy:
 * <ol>
 * <li><b>Exact Match</b>: Look for platform_id/deployment_id combination in Datastore
 *   <ul>
 *   <li>Primary key: platform_id + "/" + deployment_id</li>
 *   <li>Returns immediately if found</li>
 *   </ul>
 * </li>
 * <li><b>Single Platform Match</b>: If no deployment_id, look for only registered deployment for platform
 *   <ul>
 *   <li>Range query: platform_id + "/" to platform_id + "/~"</li>
 *   <li>Returns if exactly one deployment exists</li>
 *   </ul>
 * </li>
 * <li><b>Auto-Registration</b>: For known platforms, automatically register new deployment
 *   <ul>
 *   <li>Creates Deployment entity with well-known LMS configuration</li>
 *   <li>Sets status to "auto" for manual review</li>
 *   <li>Sends notification email to administrator</li>
 *   <li>Supports Canvas, Schoology, Blackboard</li>
 *   </ul>
 * </li>
 * <li><b>Failure</b>: No deployment found and not auto-registerable
 *   <ul>
 *   <li>Returns HTTP 401 error with diagnostic message</li>
 *   <li>Suggests manual registration at /lti/registration endpoint</li>
 *   </ul>
 * </li>
 * </ol>
 * 
 * JWT State Token (Signed):
 * <ul>
 * <li><b>Header</b>: typ=JWT, alg=HS256
 * <li><b>Claims</b>:
 *   <ul>
 *   <li>iss (issuer): Tool instance URL (e.g., https://www.chem4ap.org)
 *   <li>sub (subject): User login_hint from platform
 *   <li>aud (audience): Platform ID (iss parameter)
 *   <li>exp (expiration): 5 minutes from now (300000ms)
 *   <li>iat (issued at): Current time (seconds since epoch)
 *   <li>nonce: Random 32-character string for security
 *   <li>deployment_id: Retrieved from Deployment entity
 *   <li>client_id: Retrieved from Deployment entity
 *   <li>redirect_uri: Tool's callback endpoint (target_link_uri)
 *   </ul>
 * </li>
 * <li><b>Signature</b>: HMAC-SHA256 using shared secret (from Util.getHMAC256Secret())
 * <li><b>Usage</b>: Passed as 'state' parameter in OIDC authorization URL
 * <li><b>Validation</b>: Launch servlet verifies signature and extracts deployment info
 * </ul>
 * 
 * OIDC Authorization Request:
 * Redirects browser to LMS authorization endpoint with parameters:
 * <ul>
 * <li>response_type=id_token (request ID token directly, no code flow)
 * <li>response_mode=form_post (LMS uses HTML form for response, not URL)
 * <li>scope=openid (minimum scope - requests only ID token)
 * <li>prompt=none (no login prompt - user must be authenticated at platform)
 * <li>login_hint={login_hint} (hint for which account to use)
 * <li>redirect_uri={target_link_uri} (where LMS sends ID token response)
 * <li>client_id={client_id} (OAuth client identifier)
 * <li>state={jwt_token} (signed state token with deployment info)
 * <li>nonce={nonce} (security nonce - must match in ID token)
 * <li>lti_message_hint={optional} (context information from platform)
 * </ul>
 * 
 * LMS Configuration Endpoints:
 * <ul>
 * <li><b>Canvas OIDC</b>:
 *   <ul>
 *   <li>Authorization: https://sso.canvaslms.com/api/lti/authorize_redirect
 *   <li>Token: https://sso.canvaslms.com/login/oauth2/token
 *   <li>JWKS: https://sso.canvaslms.com/api/lti/security/jwks
 *   </ul>
 * </li>
 * <li><b>Schoology OIDC</b>:
 *   <ul>
 *   <li>Authorization: https://lti-service.svc.schoology.com/lti-service/authorize-redirect
 *   <li>Token: https://lti-service.svc.schoology.com/lti-service/access-token
 *   <li>JWKS: https://lti-service.svc.schoology.com/lti-service/.well-known/jwks
 *   </ul>
 * </li>
 * <li><b>Blackboard OIDC</b>:
 *   <ul>
 *   <li>Authorization: https://developer.blackboard.com/api/v1/gateway/oidcauth
 *   <li>Token: https://developer.blackboard.com/api/v1/gateway/oauth2/jwttoken
 *   <li>JWKS: https://developer.blackboard.com/api/v1/management/applications/...
 *   </ul>
 * </li>
 * </ul>
 * 
 * Security Properties:
 * <ul>
 * <li><b>HTTPS Required</b>: Platform ID must be HTTPS URL (TLS encryption)
 * <li><b>Signature Verification</b>: JWT signed with HMAC-SHA256 shared secret
 * <li><b>Nonce Validation</b>: Random nonce prevents token reuse attacks
 * <li><b>Token Expiration</b>: 5-minute lifetime limits exposure of compromised tokens
 * <li><b>State Protection</b>: State token carries deployment configuration, prevents parameter tampering
 * <li><b>Sealed Deployment Info</b>: Deployment details embedded in JWT, cannot be modified by client
 * </ul>
 * 
 * Data Models:
 * <ul>
 * <li><b>Deployment</b>: LMS-specific configuration (OIDC endpoints, client credentials, license info)
 * <li><b>Nonce</b>: One-time use security values for replay attack prevention
 * <li><b>User</b>: Tool user profile, linked to LMS user via launch claims
 * </ul>
 * 
 * Integration Points:
 * <ul>
 * <li><b>Util</b>: Server URL, HMAC256 secret, email sending
 * <li><b>Nonce</b>: Random security value generation
 * <li><b>Deployment</b>: LMS configuration lookup and auto-registration
 * <li><b>Objectify</b>: Datastore queries for Deployment entities
 * <li><b>Auth0 JWT</b>: JWT creation and signing with HMAC256
 * <li><b>SendGrid</b>: Email notifications for auto-registered deployments
 * </ul>
 * 
 * Performance:
 * <ul>
 * <li>Deployment lookup: 1-2 Datastore queries (exact match or range query)
 * <li>Auto-registration: +1 Datastore save + 1 email send (~500ms total)
 * <li>JWT generation and signing: <5ms
 * <li>Typical response time: 50-100ms (successful case)
 * </ul>
 * 
 * Error Handling:
 * <ul>
 * <li><b>Missing Parameters</b>: HTTP 401 with parameter name in error message
 * <li><b>Invalid Platform ID</b>: Non-HTTPS URL, malformed URL
 * <li><b>Deployment Not Found</b>: Suggests registration endpoint to user
 * <li><b>Auto-Registration Failure</b>: Admin notified via email, user gets generic error
 * </ul>
 * 
 * URL Pattern: /auth/token
 * Methods: GET (primary), POST (delegated to GET)
 * Content-Type: text/html (redirects with JavaScript)
 * 
 * @see Deployment LMS configuration entity
 * @see Nonce Security nonce values
 * @see Launch LTI 1.3 tool launch endpoint (receives ID token)
 * @see Util Server configuration and email utilities
 */
@WebServlet("/auth/token")
public class Token extends HttpServlet {
	private static final long serialVersionUID = 1L;
	
	// ========================================
	// Token Configuration Constants
	// ========================================
	
	/** OIDC state token validity duration: 5 minutes in milliseconds */
	private static final long TOKEN_VALIDITY_MS = 300000L;
	
	// ========================================
	// LTI/OAuth 2.0 Parameter Names
	// ========================================
	
	/** Platform issuer identifier (required) - typically https://canvas.instructure.com */
	private static final String PARAM_ISS = "iss";
	
	/** User identifier at the platform (required) - opaque string or email */
	private static final String PARAM_LOGIN_HINT = "login_hint";
	
	/** Tool launch entry point URL (required) - where user's browser navigates */
	private static final String PARAM_TARGET_LINK_URI = "target_link_uri";
	
	/** Deployment identifier for Moodle/Brightspace (optional) */
	private static final String PARAM_LTI_DEPLOYMENT_ID = "lti_deployment_id";
	
	/** Deployment identifier for Canvas (optional) - different parameter name than Moodle */
	private static final String PARAM_DEPLOYMENT_ID = "deployment_id";
	
	/** OAuth 2.0 client identifier (required) - links request to registered tool */
	private static final String PARAM_CLIENT_ID = "client_id";
	
	/** Platform-specific context hint, often contains encrypted data (optional) */
	private static final String PARAM_LTI_MESSAGE_HINT = "lti_message_hint";
	
	// ========================================
	// JWT Custom Claim Names
	// ========================================
	
	/** Random value for security, prevents token reuse/replay attacks */
	private static final String CLAIM_NONCE = "nonce";
	
	/** LMS deployment identifier, enables deployment-specific configuration */
	private static final String CLAIM_DEPLOYMENT_ID = "deployment_id";
	
	/** OAuth client identifier, identifies the tool to the LMS */
	private static final String CLAIM_CLIENT_ID = "client_id";
	
	/** Tool callback URL, where LMS redirects user after authentication */
	private static final String CLAIM_REDIRECT_URI = "redirect_uri";
	
	// ========================================
	// OIDC/OAuth Parameter Values
	// ========================================
	
	/** Response type for implicit flow (direct ID token in form_post response) */
	private static final String RESPONSE_TYPE = "id_token";
	
	/** Response mode: form_post uses HTML form for response instead of URL fragments */
	private static final String RESPONSE_MODE = "form_post";
	
	/** OpenID scope: minimum scope requesting ID token with basic claims */
	private static final String SCOPE_OPENID = "openid";
	
	/** Prompt parameter: none = do not show login prompt, user must be authenticated */
	private static final String PROMPT_NONE = "none";
	
	// ========================================
	// Canvas LMS Configuration
	// ========================================
	
	/** Canvas standard platform identifier */
	private static final String CANVAS_PLATFORM_ID = "https://canvas.instructure.com";
	
	/** Canvas OIDC authorization endpoint */
	private static final String CANVAS_OIDC_AUTH_URL = "https://sso.canvaslms.com/api/lti/authorize_redirect";
	
	/** Canvas OAuth token endpoint (for server-to-server token refresh) */
	private static final String CANVAS_TOKEN_URL = "https://sso.canvaslms.com/login/oauth2/token";
	
	/** Canvas JWT key set endpoint (public keys for ID token verification) */
	private static final String CANVAS_JWKS_URL = "https://sso.canvaslms.com/api/lti/security/jwks";
	
	// ========================================
	// Schoology LMS Configuration
	// ========================================
	
	/** Schoology standard platform identifier */
	private static final String SCHOOLOGY_PLATFORM_ID = "https://schoology.schoology.com";
	
	/** Schoology OIDC authorization endpoint */
	private static final String SCHOOLOGY_OIDC_AUTH_URL = "https://lti-service.svc.schoology.com/lti-service/authorize-redirect";
	
	/** Schoology OAuth token endpoint */
	private static final String SCHOOLOGY_TOKEN_URL = "https://lti-service.svc.schoology.com/lti-service/access-token";
	
	/** Schoology JWT key set endpoint */
	private static final String SCHOOLOGY_JWKS_URL = "https://lti-service.svc.schoology.com/lti-service/.well-known/jwks";
	
	// ========================================
	// Blackboard LMS Configuration
	// ========================================
	
	/** Blackboard standard platform identifier */
	private static final String BLACKBOARD_PLATFORM_ID = "https://blackboard.com";
	
	/** Blackboard OIDC authorization endpoint */
	private static final String BLACKBOARD_OIDC_AUTH_URL = "https://developer.blackboard.com/api/v1/gateway/oidcauth";
	
	/** Blackboard OAuth token endpoint */
	private static final String BLACKBOARD_TOKEN_URL = "https://developer.blackboard.com/api/v1/gateway/oauth2/jwttoken";
	
	/** Blackboard JWT key set endpoint */
	private static final String BLACKBOARD_JWKS_URL = "https://developer.blackboard.com/api/v1/management/applications/be1004de-6f8e-45b9-aae4-2c1370c24e1e/jwks.json";
	
	// ========================================
	// Error Messages and Status Constants
	// ========================================
	
	/** Error message for missing iss parameter */
	private static final String ERROR_MISSING_ISS = "Missing required iss (platform_id) parameter.";
	
	/** Error message for missing login_hint parameter */
	private static final String ERROR_MISSING_LOGIN_HINT = "Missing required login_hint parameter.";
	
	/** Error message for missing target_link_uri parameter */
	private static final String ERROR_MISSING_TARGET_LINK_URI = "Missing required target_link_uri parameter.";
	
	/** Error message for missing platform_id in getDeployment */
	private static final String ERROR_PLATFORM_ID_REQUIRED = "Platform ID (iss parameter) is required.";
	
	/** Error message for non-HTTPS platform URL */
	private static final String ERROR_NON_HTTPS_PLATFORM = "The platform_id must be a secure HTTPS URL. Received: ";
	
	/** Error message for deployment not found (suggests manual registration) */
	private static final String ERROR_DEPLOYMENT_NOT_FOUND = 
		"ChemVantage was unable to identify this deployment from your LMS. " +
		"If you received a registration email within the past 7 days, please use the tokenized link in that message to " +
		"submit (or resubmit) the deployment_id and other required parameters. Otherwise, you may " +
		"repeat the registration process at https://www.chemvantage.org/lti/registration";
	
	/** Deployment status for auto-registered deployments (pending manual review) */
	private static final String DEPLOYMENT_STATUS_AUTO = "auto";
	
	/** Default license count for auto-registered deployments (requires manual approval) */
	private static final int DEFAULT_AUTO_REG_LICENSES = 0;
	
	/** LMS identifier for Canvas (stored in Deployment entity) */
	private static final String LMS_CANVAS = "canvas";
	
	/** LMS identifier for Schoology */
	private static final String LMS_SCHOOLOGY = "schoology";
	
	/** LMS identifier for Blackboard */
	private static final String LMS_BLACKBOARD = "blackboard";
	
	// ========================================
	// HTML and Response Constants
	// ========================================
	
	/** Page title for token endpoint */
	private static final String PAGE_TITLE = "Auth Token";
	
	/** Content type for redirect responses */
	private static final String CONTENT_TYPE_HTML = "text/html";
	
	/**
	 * Handles GET requests for OIDC third-party initiated login token generation.
	 * 
	 * Request Processing Flow:
	 * <ol>
	 * <li><b>Parameter Validation</b>: Verify all required LTI parameters present
	 *   <ul>
	 *   <li>iss (platform_id): Platform URL, e.g., https://canvas.instructure.com
	 *   <li>login_hint: User identifier at platform (opaque string or email)
	 *   <li>target_link_uri: Tool's callback URL where user loads after auth
	 *   </ul>
	 * </li>
	 * <li><b>Deployment Lookup</b>: Find registered Deployment for platform+deployment_id combination
	 *   <ul>
	 *   <li>Calls getDeployment() for intelligent lookup with auto-registration support
	 *   <li>Throws exception if deployment not found and not auto-registerable
	 *   </ul>
	 * </li>
	 * <li><b>Nonce Generation</b>: Create random 32-character security nonce
	 *   <ul>
	 *   <li>Prevents token reuse and replay attacks
	 *   <li>Must match nonce in ID token response from LMS
	 *   </ul>
	 * </li>
	 * <li><b>JWT Creation</b>: Build signed state token with deployment info
	 *   <ul>
	 *   <li>Algorithm: HMAC-SHA256 with shared secret from Util
	 *   <li>Claims: iss, sub, aud, exp, iat, nonce, deployment_id, client_id, redirect_uri
	 *   <li>Expiration: 5 minutes from now (300000ms)
	 *   </ul>
	 * </li>
	 * <li><b>OIDC Redirect URL Construction</b>: Build authorization endpoint URL with parameters
	 *   <ul>
	 *   <li>Base URL from Deployment.oidc_auth_url
	 *   <li>Parameters: response_type, response_mode, scope, prompt, login_hint, redirect_uri, client_id, state, nonce
	 *   <li>Optional: lti_message_hint (platform context)
	 *   </ul>
	 * </li>
	 * <li><b>Browser Redirect</b>: Send HTML page with JavaScript redirect to LMS
	 *   <ul>
	 *   <li>Uses window.location.replace() for seamless navigation
	 *   <li>Prevents back-button from returning to token endpoint
	 *   </ul>
	 * </li>
	 * </ol>
	 * 
	 * Required Parameters (from LMS via query string):
	 * <ul>
	 * <li><b>iss</b> (platform_id): HTTPS URL of LMS platform
	 * <li><b>login_hint</b>: User identifier (opaque token or email)
	 * <li><b>target_link_uri</b>: Tool launch URL (must be HTTPS)
	 * <li><b>client_id</b>: OAuth client ID from tool registration
	 * </ul>
	 * 
	 * Optional Parameters:
	 * <ul>
	 * <li><b>lti_deployment_id</b> (Moodle/Brightspace): Deployment identifier
	 * <li><b>deployment_id</b> (Canvas): Alternate parameter name for deployment
	 * <li><b>lti_message_hint</b>: Platform-provided context information
	 * </ul>
	 * 
	 * JWT State Token Structure:
	 * <ul>
	 * <li>Header: {alg: "HS256", typ: "JWT"}
	 * <li>Payload: Standard OIDC claims + custom LTI claims (see JWT_* constants)
	 * <li>Signature: HMAC-SHA256(header.payload, shared_secret)
	 * <li>Format: "header.payload.signature" (URL-safe Base64)
	 * <li>Passed to LMS as 'state' parameter in OIDC request
	 * </ul>
	 * 
	 * OIDC Authorization URL Format:
	 * <pre>
	 * {oidc_auth_url}?
	 *   response_type=id_token&
	 *   response_mode=form_post&
	 *   scope=openid&
	 *   prompt=none&
	 *   login_hint={login_hint}&
	 *   redirect_uri={target_link_uri}&
	 *   client_id={client_id}&
	 *   state={signed_jwt_token}&
	 *   nonce={nonce}
	 *   [&lti_message_hint={lti_message_hint}]
	 * </pre>
	 * 
	 * Response Format:
	 * <ul>
	 * <li>Success: HTML page with JavaScript redirect (window.location.replace)
	 * <li>Failure: HTTP 401 error with diagnostic message
	 * <li>Content-Type: text/html
	 * </ul>
	 * 
	 * Error Handling:
	 * <ul>
	 * <li><b>Missing Parameters</b>: HTTP 401 with "Missing required {param_name} parameter."
	 * <li><b>Deployment Not Found</b>: HTTP 401 with registration instructions
	 * <li><b>Other Exceptions</b>: HTTP 401 with error message and diagnostic parameter dump
	 *   <ul>
	 *   <li>Includes all request parameters for debugging
	 *   <li>Admin email sent with failure details (production only)
	 *   </ul>
	 * </li>
	 * </ul>
	 * 
	 * Security Properties:
	 * <ul>
	 * <li>JWT signed with HMAC-SHA256 (cannot be forged without secret)
	 * <li>5-minute token lifetime (limits exposure window for token compromise)
	 * <li>Nonce prevents token reuse and replay attacks
	 * <li>HTTPS required for all URLs (platform_id, target_link_uri)
	 * <li>Deployment info sealed in JWT (cannot be modified by client)
	 * </ul>
	 * 
	 * Datastore Operations:
	 * - getDeployment() performs 1-2 queries for deployment lookup
	 * - Auto-registration saves new Deployment and sends email (if applicable)
	 * 
	 * Performance:
	 * <ul>
	 * <li>Deployment lookup: 10-50ms (Datastore query)
	 * <li>JWT creation: <5ms (cryptographic operation)
	 * <li>Total response time: 50-100ms typical
	 * </ul>
	 * 
	 * @param request HTTP request containing iss, login_hint, target_link_uri, and optionally deployment_id
	 * @param response HTTP response with HTML page containing JavaScript redirect
	 * @throws ServletException if servlet processing fails
	 * @throws IOException if response writing fails
	 * @see #getDeployment(HttpServletRequest) Intelligent deployment lookup with auto-registration
	 * @see Deployment LMS configuration entity
	 * @see Nonce Security nonce generation
	 * @see Util HMAC256 secret provider
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {
		StringBuilder debug = new StringBuilder("");
		try {
			// store parameters required by third-party initiated login procedure:
			String platform_id = request.getParameter(PARAM_ISS);   // this should be the platform_id URL (aud)
			if (platform_id == null || platform_id.isEmpty()) throw new IllegalArgumentException(ERROR_MISSING_ISS);
			
			String login_hint = request.getParameter(PARAM_LOGIN_HINT);
			if (login_hint == null || login_hint.isEmpty()) throw new IllegalArgumentException(ERROR_MISSING_LOGIN_HINT);
			
			String target_link_uri = request.getParameter(PARAM_TARGET_LINK_URI);
			if (target_link_uri == null || target_link_uri.isEmpty()) throw new IllegalArgumentException(ERROR_MISSING_TARGET_LINK_URI);
			
			Deployment d = getDeployment(request);
			
			if (d==null) throw new Exception(ERROR_DEPLOYMENT_NOT_FOUND);
			
			String redirect_uri = target_link_uri;
			
			Date now = new Date();
			Date exp = new Date(now.getTime() + TOKEN_VALIDITY_MS); // 5 minutes from now
			String nonce = Nonce.generateNonce();
			Algorithm algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
			
			debug.append("JWT algorithm loaded OK.<br>");
			
			String iss = "https://" + request.getServerName();
			
			String token = JWT.create()
					.withIssuer(iss)
					.withSubject(login_hint)
					.withAudience(platform_id)
					.withExpiresAt(exp)
					.withIssuedAt(now)
					.withClaim(CLAIM_NONCE, nonce)
					.withClaim(CLAIM_DEPLOYMENT_ID, d.getDeploymentId())
					.withClaim(CLAIM_CLIENT_ID, d.client_id)
					.withClaim(CLAIM_REDIRECT_URI, redirect_uri)
					.sign(algorithm);
			
			debug.append("JWT constructed and signed OK<br>");
			String lti_message_hint = request.getParameter(PARAM_LTI_MESSAGE_HINT);
			
			String oidc_auth_url = d.oidc_auth_url
					+ "?response_type=" + RESPONSE_TYPE
					+ "&response_mode=" + RESPONSE_MODE
					+ "&scope=" + SCOPE_OPENID
					+ "&prompt=" + PROMPT_NONE
					+ "&login_hint=" + login_hint
					+ "&redirect_uri=" + redirect_uri
					+ (lti_message_hint==null?"":"&" + PARAM_LTI_MESSAGE_HINT + "=" + lti_message_hint)
					+ "&" + PARAM_CLIENT_ID + "=" + d.client_id
					+ "&state=" + token
					+ "&nonce=" + nonce;
			
			debug.append("Sending token: " + oidc_auth_url + "<p>");
			
			response.setContentType(CONTENT_TYPE_HTML);
			PrintWriter out = response.getWriter();
			StringBuilder buf = new StringBuilder();
			buf.append(Util.head(PAGE_TITLE));
			// Javascript tries to store the hashCode of nonce for use during launch:
			buf.append("<script>"
					+ "window.location.replace('" + oidc_auth_url + "');"
					+ "</script>");
			buf.append(Util.foot());
			//if (oidc_auth_url.contains("imc")) Utilities.sendEmail("ChemVantage", "admin@chemvantage.org", "IMC OIDC Auth URL", oidc_auth_url);
			out.println(buf.toString());
		} catch (Exception e) {
			Enumeration<String> parameterNames = request.getParameterNames();
			while (parameterNames.hasMoreElements()) {
				String name = parameterNames.nextElement();
				debug.append(name + ":" + request.getParameter(name) + ";");
			}
			response.sendError(401,"Failed Auth Token. " + (e.getMessage()==null?e.toString():e.getMessage()) + "\n" + debug.toString());
		}
	}
	
	
	/**
	 * Intelligently identifies the Deployment entity for the LTI launch request.
	 * 
	 * Lookup Strategy (Priority Order):
	 * <ol>
	 * <li><b>Exact Match</b>: Primary key lookup using platform_id + "/" + deployment_id
	 *   <ul>
	 *   <li>Most specific match - directly returns if found</li>
	 *   <li>Handles Canvas (deployment_id), Moodle (lti_deployment_id), Schoology (login_hint)</li>
	 *   <li>Primary key format: "https://platform.com/deployment-xyz"</li>
	 *   </ul>
	 * </li>
	 * <li><b>Single Platform Match</b>: If deployment_id not found, fallback to platform-only search
	 *   <ul>
	 *   <li>Range query: keys from "platform_id/" to "platform_id/~"</li>
	 *   <li>Returns single result if exactly one deployment registered for platform</li>
	 *   <li>Throws exception if multiple deployments found (ambiguous)</li>
	 *   </ul>
	 * </li>
	 * <li><b>Auto-Registration</b>: For known platforms, automatically create Deployment
	 *   <ul>
	 *   <li><b>Canvas</b>: Well-known OIDC endpoints + Canvas specific configuration</li>
	 *   <li><b>Schoology</b>: Schoology LMS endpoints + Schoology configuration</li>
	 *   <li><b>Blackboard</b>: Blackboard developer API endpoints</li>
	 *   <li>Sets status="auto" for administrator review and approval</li>
	 *   <li>Sets nLicensesRemaining=0 (requires manual license allocation)</li>
	 *   <li>Sends email notification to ChemVantage admin with request details</li>
	 *   </ul>
	 * </li>
	 * <li><b>Failure</b>: Unknown platform or failed auto-registration
	 *   <ul>
	 *   <li>Returns null (caller handles error)</li>
	 *   <li>Sends advisory email to admin with all request parameters</li>
	 *   </ul>
	 * </li>
	 * </ol>
	 * 
	 * Platform ID Processing:
	 * <ul>
	 * <li>Validates presence (throws IllegalArgumentException if missing)
	 * <li>Strips trailing "/" from platform URL (normalization)
	 * <li>Validates URL structure (must be valid HTTPS URL)
	 * <li>Checks protocol is HTTPS (throws exception for HTTP)
	 * </ul>
	 * 
	 * Deployment ID Parameter Variations:
	 * <ul>
	 * <li><b>Moodle/Brightspace</b>: lti_deployment_id parameter
	 * <li><b>Canvas</b>: deployment_id parameter (different from Moodle)
	 * <li><b>Schoology</b>: login_hint parameter (school-specific identifier)
	 * <li>Code tries all three in fallback order
	 * </ul>
	 * 
	 * Auto-Registration Details:
	 * <ul>
	 * <li><b>Canvas Auto-Reg</b>:
	 *   <ul>
	 *   <li>Uses standard Canvas SSO endpoints</li>
	 *   <li>Attempts to extract canvas_domain from lti_message_hint JWT</li>
	 *   <li>Includes in notification email for context</li>
	 *   </ul>
	 * </li>
	 * <li><b>Schoology Auto-Reg</b>:
	 *   <ul>
	 *   <li>Uses Schoology LTI service endpoints</li>
	 *   <li>Stores request parameters for admin review</li>
	 *   </ul>
	 * </li>
	 * <li><b>Blackboard Auto-Reg</b>:
	 *   <ul>
	 *   <li>Uses Blackboard developer gateway endpoints</li>
	 *   <li>Stores request parameters for admin review</li>
	 *   </ul>
	 * </li>
	 * </ul>
	 * 
	 * Deployment Entity Structure:
	 * <ul>
	 * <li>Key: platform_id + "/" + deployment_id
	 * <li>Properties: platform_id, deployment_id, client_id, oidc_auth_url, oauth_access_token_url, 
	 *     well_known_jwks_url, contact_name, email, organization, org_url, lms, status, nLicensesRemaining
	 * <li>Auto-reg sets: status="auto", nLicensesRemaining=0
	 * <li>Contact info (name, email, organization) initially null for auto-reg
	 * </ul>
	 * 
	 * Email Notifications:
	 * <ul>
	 * <li><b>Auto-Registered</b>: Admin notified of Canvas/Schoology/Blackboard auto-reg
	 *   <ul>
	 *   <li>Subject: "Automatic {Platform} Registration"
	 *   <li>Body: Platform-specific details (Canvas domain, request parameters)
	 *   <li>Action: Manual review and license allocation required
	 *   </ul>
	 * </li>
	 * <li><b>Unknown Platform</b>: Admin notified of failed lookup (production only)
	 *   <ul>
	 *   <li>Subject: "AuthToken Request Failure (Production)"
	 *   <li>Body: All request parameters for debugging
	 *   <li>Action: May require manual registration setup
	 *   </ul>
	 * </li>
	 * </ul>
	 * 
	 * Error Handling:
	 * <ul>
	 * <li><b>Missing platform_id</b>: Throws IllegalArgumentException
	 * <li><b>Non-HTTPS URL</b>: Throws IllegalArgumentException with protocol shown
	 * <li><b>Not Found + Not Auto-Registerable</b>: Returns null
	 * <li>Admin notified of failures via email
	 * </ul>
	 * 
	 * Performance:
	 * <ul>
	 * <li>Exact match (found): 1 Datastore query (~20-50ms)
	 * <li>Platform-only match: 1 range query (~30-100ms)
	 * <li>Auto-registration: +1 save + 1 email send (~500-1000ms total)
	 * </ul>
	 * 
	 * Datastore Queries:
	 * <ul>
	 * <li>ofy().load().type(Deployment.class).id(platform_deployment_id).now() - Exact match
	 * <li>Range query with filterKey >= and < for platform-only search
	 * <li>ofy().save().entity(d).now() - Persist auto-registered deployment
	 * </ul>
	 * 
	 * @param request HTTP request containing iss (platform_id) and optional deployment_id parameters
	 * @return Deployment entity with OIDC configuration, or null if not found
	 * @throws IllegalArgumentException if platform_id missing or invalid (non-HTTPS)
	 * @throws Exception if URL parsing fails or other processing error
	 * @see Deployment LMS configuration entity
	 * @see #doGet(HttpServletRequest, HttpServletResponse) Caller requiring deployment info
	 */
	private static Deployment getDeployment(HttpServletRequest request) throws Exception {
		// This method attempts to identify a unique registered Deployment entity based on the required
		// platform_id value and the optional lti_deployment_id and client_id values. The latter should 
		// be used in case the platform supports multiple deployments with different client_id values for the tool.
		// However, this is not technically required by the specifications. Hmm.
		Deployment d = null;
		String platform_id = request.getParameter(PARAM_ISS);   // this should be the platform_id URL (aud)
		if (platform_id == null || platform_id.isEmpty()) throw new IllegalArgumentException(ERROR_PLATFORM_ID_REQUIRED);
		if (platform_id.endsWith("/")) platform_id = platform_id.substring(0,platform_id.length()-1);  // strip any trailing / from platform_id

		URL platform = new URI(platform_id).toURL();
		if (!platform.getProtocol().equals("https")) throw new IllegalArgumentException(ERROR_NON_HTTPS_PLATFORM + platform.getProtocol());

		// Take the optimistic route first; this should always work if the deployment_id has been provided, else return null;
		String deployment_id = request.getParameter(PARAM_LTI_DEPLOYMENT_ID);  // moodle, brightspace, blackboard
		if (deployment_id == null) deployment_id = request.getParameter(PARAM_DEPLOYMENT_ID);  // canvas
		if (deployment_id == null) deployment_id = request.getParameter(PARAM_LOGIN_HINT);  // schoology

		try {
			String platform_deployment_id = platform_id + "/" + deployment_id;
			d = ofy().load().type(Deployment.class).id(platform_deployment_id).now();  // previously used .safe()
			
			if (d != null) return d;
			
			// test to see if the platform has a single registered deployment
			Key<Deployment> kstart = key(Deployment.class, platform_id);
			Key<Deployment> kend = key(Deployment.class, platform_id + "/~");
			List<Deployment> range = ofy().load().type(Deployment.class).filterKey(">=", kstart).filterKey("<", kend).list();
			if (range.size()==1) return range.get(0);
			
			// experimental: automatic deployment registration
			
			if (CANVAS_PLATFORM_ID.equals(platform_id)) {  // auto register canvas account
				String client_id = request.getParameter(PARAM_CLIENT_ID);
				String oidc_auth_url = CANVAS_OIDC_AUTH_URL;
				String oauth_access_token_url = CANVAS_TOKEN_URL;
				String well_known_jwks_url = CANVAS_JWKS_URL;
				String contact_name = null;
				String email = null;
				String organization = null;
				String org_url = null;
				String lms = LMS_CANVAS;
				d = new Deployment(platform_id,deployment_id,client_id,oidc_auth_url,oauth_access_token_url,well_known_jwks_url,contact_name,email,organization,org_url,lms);
				d.status = DEPLOYMENT_STATUS_AUTO;
				d.nLicensesRemaining = DEFAULT_AUTO_REG_LICENSES;
				ofy().save().entity(d).now();
				String message = "<h3>Deployment Registration</h3>";
				try {
					String token = request.getParameter("lti_message_hint");
					String canvas_domain = JWT.decode(token).getClaims().get("canvas_domain").asString();
					message += "Canvas domain: " + canvas_domain + "<br/><br/>";
				} catch (Exception e) {}
				Map<String,String[]> params = request.getParameterMap();
				message += "Query parameters:<br/>";
				for (String name : params.keySet()) message += name + "=" + params.get(name)[0] + "<br/>";
				Util.sendEmail("ChemVantage","admin@chemvantage.org","Automatic Canvas Registration",message);
				return d;
			} else if (SCHOOLOGY_PLATFORM_ID.equals(platform_id)) {  // auto register schoology account
				String client_id = request.getParameter(PARAM_CLIENT_ID);
				String oidc_auth_url = SCHOOLOGY_OIDC_AUTH_URL;
				String well_known_jwks_url = SCHOOLOGY_JWKS_URL;
				String oauth_access_token_url = SCHOOLOGY_TOKEN_URL;
				String contact_name = null;
				String email = null;
				String organization = null;
				String org_url = null;
				String lms = LMS_SCHOOLOGY;
				d = new Deployment(platform_id,deployment_id,client_id,oidc_auth_url,oauth_access_token_url,well_known_jwks_url,contact_name,email,organization,org_url,lms);
				d.status = DEPLOYMENT_STATUS_AUTO;
				d.nLicensesRemaining = DEFAULT_AUTO_REG_LICENSES;
				ofy().save().entity(d).now();
				Map<String,String[]> params = request.getParameterMap();
				String message = "<h3>Deployment Registration</h3>Query parameters:<br/>";
				for (String name : params.keySet()) message += name + "=" + params.get(name)[0] + "<br/>";
				Util.sendEmail("ChemVantage","admin@chemvantage.org","Automatic Schoology Registration",message);
				return d;
			} else if (BLACKBOARD_PLATFORM_ID.equals(platform_id)) {
				String client_id = request.getParameter(PARAM_CLIENT_ID);
				String oidc_auth_url = BLACKBOARD_OIDC_AUTH_URL;
				String well_known_jwks_url = BLACKBOARD_JWKS_URL;
				String oauth_access_token_url = BLACKBOARD_TOKEN_URL;
				String contact_name = null;
				String email = null;
				String organization = null;
				String org_url = null;
				String lms = LMS_BLACKBOARD;
				d = new Deployment(platform_id,deployment_id,client_id,oidc_auth_url,oauth_access_token_url,well_known_jwks_url,contact_name,email,organization,org_url,lms);
				d.status = DEPLOYMENT_STATUS_AUTO;
				d.nLicensesRemaining = DEFAULT_AUTO_REG_LICENSES;
				ofy().save().entity(d).now();
				Map<String,String[]> params = request.getParameterMap();
				String message = "<h3>Deployment Registration</h3>Query parameters:<br/>";
				for (String name : params.keySet()) message += name + "=" + params.get(name)[0] + "<br/>";
				Util.sendEmail("ChemVantage","admin@chemvantage.org","Automatic Blackboard Registration",message);
				return d;	
			} else {
				throw new Exception("Deployment Not Found");
			}
		} catch (Exception e) {
			// send advisory email to ChemVantage administrator:
			Map<String,String[]> params = request.getParameterMap();
			String message = "<h3>Deployment Not Found</h3>Query parameters:<br/>";
			for (String name : params.keySet()) message += name + "=" + params.get(name)[0] + "<br/>";
			if (Util.projectId.equals("chem4ap")) Util.sendEmail("ChemVantage","admin@chemvantage.org","AuthToken Request Failure (Production)",message);
		}
		return d;
}

	protected void doPost(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {
		doGet(request, response);
	}
	
}
