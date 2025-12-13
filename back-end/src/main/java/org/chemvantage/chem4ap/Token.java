package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.ofy;
import static com.googlecode.objectify.ObjectifyService.key;

import java.io.IOException;
import java.io.PrintWriter;
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
 * Token servlet serves as the OpenID Connect entry point for third-party initiated login flows.
 * 
 * This servlet implements the OAuth 2.0 Authorization Framework and OpenID Connect protocol
 * to enable learning management systems (LMS) to securely launch LTI tools. The servlet:
 * 
 * 1. Receives third-party initiated login requests from platforms (Canvas, Brightspace, Moodle, etc.)
 * 2. Identifies the corresponding Deployment entity based on platform_id and deployment_id
 * 3. Generates a signed JWT token containing deployment and user information
 * 4. Initiates the OIDC authorization flow with the platform
 * 
 * Supported LMS Platforms (with auto-registration):
 * - Canvas (canvas.instructure.com)
 * - Schoology (schoology.schoology.com)
 * - Blackboard (blackboard.com)
 * 
 * The token is valid for 5 minutes and includes the following claims:
 * - nonce: Random value for security
 * - deployment_id: Identifies the LMS deployment
 * - client_id: OAuth client identifier
 * - redirect_uri: Callback URI for OIDC response
 * 
 * URL Pattern: /auth/token
 * Methods: GET and POST (both delegate to same handler)
 */
@WebServlet("/auth/token")
public class Token extends HttpServlet {
	private static final long serialVersionUID = 1L;
	
	/** Token validity duration: 5 minutes in milliseconds */
	private static final long TOKEN_VALIDITY_MS = 300000L;  // 5 minutes
	
	// LTI/OAuth parameter names
	private static final String PARAM_ISS = "iss";              // Platform issuer/platform_id
	private static final String PARAM_LOGIN_HINT = "login_hint"; // User identifier for login
	private static final String PARAM_TARGET_LINK_URI = "target_link_uri"; // Tool entry point
	private static final String PARAM_LTI_DEPLOYMENT_ID = "lti_deployment_id";  // Moodle/Brightspace
	private static final String PARAM_DEPLOYMENT_ID = "deployment_id";  // Canvas
	private static final String PARAM_CLIENT_ID = "client_id";  // OAuth client identifier
	private static final String PARAM_LTI_MESSAGE_HINT = "lti_message_hint";  // Context hint
	
	// JWT claim names
	private static final String CLAIM_NONCE = "nonce";          // Random security value
	private static final String CLAIM_DEPLOYMENT_ID = "deployment_id";  // Deployment identifier
	private static final String CLAIM_CLIENT_ID = "client_id";   // OAuth client
	private static final String CLAIM_REDIRECT_URI = "redirect_uri";  // OIDC callback
	
	// OIDC/OAuth parameter values
	private static final String RESPONSE_TYPE = "id_token";      // OIDC response type
	private static final String RESPONSE_MODE = "form_post";     // Use HTML form for response
	private static final String SCOPE_OPENID = "openid";         // Request ID token
	private static final String PROMPT_NONE = "none";            // Don't prompt for login
	
	// Canvas LMS configuration
	private static final String CANVAS_PLATFORM_ID = "https://canvas.instructure.com";
	private static final String CANVAS_OIDC_AUTH_URL = "https://sso.canvaslms.com/api/lti/authorize_redirect";
	private static final String CANVAS_TOKEN_URL = "https://sso.canvaslms.com/login/oauth2/token";
	private static final String CANVAS_JWKS_URL = "https://sso.canvaslms.com/api/lti/security/jwks";
	
	// Schoology LMS configuration
	private static final String SCHOOLOGY_PLATFORM_ID = "https://schoology.schoology.com";
	private static final String SCHOOLOGY_OIDC_AUTH_URL = "https://lti-service.svc.schoology.com/lti-service/authorize-redirect";
	private static final String SCHOOLOGY_TOKEN_URL = "https://lti-service.svc.schoology.com/lti-service/access-token";
	private static final String SCHOOLOGY_JWKS_URL = "https://lti-service.svc.schoology.com/lti-service/.well-known/jwks";
	
	// Blackboard LMS configuration
	private static final String BLACKBOARD_PLATFORM_ID = "https://blackboard.com";
	private static final String BLACKBOARD_OIDC_AUTH_URL = "https://developer.blackboard.com/api/v1/gateway/oidcauth";
	private static final String BLACKBOARD_TOKEN_URL = "https://developer.blackboard.com/api/v1/gateway/oauth2/jwttoken";
	private static final String BLACKBOARD_JWKS_URL = "https://developer.blackboard.com/api/v1/management/applications/be1004de-6f8e-45b9-aae4-2c1370c24e1e/jwks.json";

	/**
	 * Handles GET requests for OIDC token generation (third-party initiated login).
	 * 
	 * Processes the following required parameters:
	 * - iss (platform_id): The issuer/platform URL
	 * - login_hint: User identifier at the platform
	 * - target_link_uri: URL where the tool should be launched
	 * 
	 * Generates an OIDC authorization request with a signed JWT state token and redirects
	 * the user agent to the platform's authorization endpoint for authentication.
	 * 
	 * @param request the HTTP request containing OIDC parameters
	 * @param response the HTTP response to send the redirect
	 * @throws ServletException if servlet processing fails
	 * @throws IOException if I/O error occurs
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {
		StringBuilder debug = new StringBuilder("");
		try {
			// store parameters required by third-party initiated login procedure:
			String platform_id = request.getParameter(PARAM_ISS);   // this should be the platform_id URL (aud)
			if (platform_id == null || platform_id.isEmpty()) throw new IllegalArgumentException("Missing required iss (platform_id) parameter.");
			
			String login_hint = request.getParameter(PARAM_LOGIN_HINT);
			if (login_hint == null || login_hint.isEmpty()) throw new IllegalArgumentException("Missing required login_hint parameter.");
			
			String target_link_uri = request.getParameter(PARAM_TARGET_LINK_URI);
			if (target_link_uri == null || target_link_uri.isEmpty()) throw new IllegalArgumentException("Missing required target_link_uri parameter.");
			
			Deployment d = getDeployment(request);
			
			if (d==null) throw new Exception("ChemVantage was unable to identify this deployment from your LMS. "
					+ "If you received a registration email within the past 7 days, please use the tokenized link in that message to "
					+ "submit (or resubmit) the deployment_id and other required parameters. Otherwise, you may "
					+ "repeat the registration process at https://www.chemvantage.org/lti/registration");
			
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
			
			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			StringBuilder buf = new StringBuilder();
			buf.append(Util.head("Auth Token"));
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
	 * Identifies the Deployment entity for the current request.
	 * 
	 * This method attempts to find a registered Deployment based on the platform_id (iss parameter)
	 * and deployment_id parameters. The deployment_id parameter name varies by LMS:
	 * - Moodle/Brightspace: lti_deployment_id
	 * - Canvas: deployment_id
	 * - Schoology: login_hint
	 * 
	 * If an exact match is found, that deployment is returned. If multiple deployments exist for
	 * the platform, a single deployment is returned (or exception if ambiguous). If no deployment
	 * is registered, automatic registration is attempted for known platforms (Canvas, Schoology, Blackboard).
	 * 
	 * @param request the HTTP request containing platform and deployment identifiers
	 * @return the matching Deployment entity, or null if not found and not auto-registered
	 * @throws Exception if platform_id is invalid or automatic registration fails
	 */
	private static Deployment getDeployment(HttpServletRequest request) throws Exception {
		// This method attempts to identify a unique registered Deployment entity based on the required
		// platform_id value and the optional lti_deployment_id and client_id values. The latter should 
		// be used in case the platform supports multiple deployments with different client_id values for the tool.
		// However, this is not technically required by the specifications. Hmm.
		Deployment d = null;
		String platform_id = request.getParameter(PARAM_ISS);   // this should be the platform_id URL (aud)
		if (platform_id == null || platform_id.isEmpty()) throw new IllegalArgumentException("Platform ID (iss parameter) is required.");
		if (platform_id.endsWith("/")) platform_id = platform_id.substring(0,platform_id.length()-1);  // strip any trailing / from platform_id

		URL platform = new URL(platform_id);
		if (!platform.getProtocol().equals("https")) throw new IllegalArgumentException("The platform_id must be a secure HTTPS URL. Received: " + platform.getProtocol());

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
				String lms = "canvas";
				d = new Deployment(platform_id,deployment_id,client_id,oidc_auth_url,oauth_access_token_url,well_known_jwks_url,contact_name,email,organization,org_url,lms);
				d.status = "auto";
				d.nLicensesRemaining = 0;
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
				String lms = "schoology";
				d = new Deployment(platform_id,deployment_id,client_id,oidc_auth_url,oauth_access_token_url,well_known_jwks_url,contact_name,email,organization,org_url,lms);
				d.status = "auto";
				d.nLicensesRemaining = 0;
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
				String lms = "blackboard";
				d = new Deployment(platform_id,deployment_id,client_id,oidc_auth_url,oauth_access_token_url,well_known_jwks_url,contact_name,email,organization,org_url,lms);
				d.status = "auto";
				d.nLicensesRemaining = 0;
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
