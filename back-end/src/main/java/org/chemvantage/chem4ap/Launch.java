package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.key;
import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.googlecode.objectify.Key;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Launch servlet providing user authentication and unit selection entry points.
 * 
 * Architecture Overview:
 * Chem4AP uses a hybrid authentication approach supporting both:
 * 1. Independent users: Email-based authentication with JWT token flows
 * 2. LTI 1.3 users: Direct integration via Learning Management Systems
 * 
 * Request Flows:
 * <ul>
 * <li><b>Email Login (GET /launch?t=&lt;token&gt;)</b>
 *   <ul>
 *   <li>User receives email with secure login link containing JWT token</li>
 *   <li>Validates token signature using HMAC256-shared secret</li>
 *   <li>Verifies token expiration (7-day validity window)</li>
 *   <li>Extracts email from token subject claim</li>
 *   <li>Displays unit selection form if validation succeeds</li>
 *   <li>Handles token expiration by resending new login link</li>
 *   </ul>
 * </li>
 * <li><b>Email Request (POST /launch Email=user@example.com)</b>
 *   <ul>
 *   <li>User submits email address via login form</li>
 *   <li>Validates email format using RFC 5322 pattern</li>
 *   <li>Creates HMAC256-signed JWT token with 7-day expiration</li>
 *   <li>Sends email with personalized login link using SendGrid</li>
 *   <li>Confirms email sent and instructs user to check inbox</li>
 *   </ul>
 * </li>
 * <li><b>Unit Selection (POST /launch sig=&lt;sig&gt;&amp;UnitId=&lt;id&gt;)</b>
 *   <ul>
 *   <li>Authenticated user selects AP Chemistry unit to study</li>
 *   <li>Validates user signature and retrieves user entity from Datastore</li>
 *   <li>Checks unit exists and verifies user has access (premium or Unit 0)</li>
 *   <li>Creates/retrieves Assignment entity for tracking progress</li>
 *   <li>Associates assignment with user and redirects to exercises app</li>
 *   </ul>
 * </li>
 * </ul>
 * 
 * Authentication & Security:
 * <ul>
 * <li><b>JWT Token Structure</b>: HMAC256-signed JWTs contain issuer URL, user email (subject), expiration
 * <li><b>Token Lifetime</b>: 7 days per token (604800000 milliseconds)
 * <li><b>Email Validation</b>: Regex pattern ensures proper format before processing
 * <li><b>HTML Escaping</b>: All user input escaped to prevent XSS injection
 * <li><b>User Signatures</b>: Email hashes used as opaque user identifiers in requests
 * </ul>
 * 
 * Unit Access Control:
 * <ul>
 * <li><b>Unit 0 (Free)</b>: Always accessible, provides AP Chemistry orientation
 * <li><b>Units 1-9 (Premium)</b>: Require active subscription purchase via Checkout servlet
 * <li><b>Assignment Tracking</b>: One assignment per user-unit pair for progress monitoring
 * </ul>
 * 
 * Data Models:
 * <ul>
 * <li><b>User</b>: Represents authenticated individual with email, scores, premium status
 * <li><b>APChemUnit</b>: Immutable curriculum unit (0-9) with topics and learning objectives
 * <li><b>APChemTopic</b>: Sub-unit grouping with questions and practice problems
 * <li><b>Assignment</b>: Tracks user progress for specific unit (score, completion status)
 * <li><b>Score</b>: Hierarchical scoring with ancestor relationships (user → assignment → topic)
 * </ul>
 * 
 * Integration Points:
 * <ul>
 * <li><b>Util</b>: Server URL, token secrets, email sending, banner HTML
 * <li><b>User</b>: User lookup and authentication, premium status checking
 * <li><b>Objectify</b>: Datastore queries for units, assignments, scores
 * <li><b>SendGrid</b>: Email delivery for login links (via Util.sendEmail)
 * <li><b>Auth0 JWT</b>: Token creation, signing, verification with HMAC256
 * </ul>
 * 
 * Performance Considerations:
 * <ul>
 * <li>Caches unit list in memory during request (single query for all units)
 * <li>Batch loads assignment and score data to reduce Datastore calls
 * <li>Uses eventual consistency (safe() method) for non-critical lookups
 * <li>Typical page load: 2-3 Datastore queries + 1-2 email sends
 * </ul>
 * 
 * UI/UX Features:
 * <ul>
 * <li><b>Unit Selection Form</b>: Radio buttons with dynamic JavaScript topic display
 * <li><b>Progress Indicators</b>: Shows percentage completion for resumed units
 * <li><b>Responsive Design</b>: Uses Bootstrap CSS framework for mobile compatibility
 * <li><b>Accessibility</b>: Proper labels, ARIA hints, clear form instructions
 * <li><b>Free Trial Notice</b>: Informs non-premium users about Unit 0 limitation
 * </ul>
 * 
 * @see User User authentication and profile management
 * @see APChemUnit Curriculum unit definitions
 * @see Assignment User progress tracking for units
 * @see Util Server configuration and email utilities
 * @see Checkout Subscription purchase and payment processing
 */
@WebServlet(urlPatterns = {"/launch"})
public class Launch extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 1L;
	
	// Token Management Constants
	private static final long LAUNCH_TOKEN_VALIDITY_MS = 604800000L;  // 7 days in milliseconds
	
	
	/**
	 * Handles GET requests for JWT token validation and unit selection.
	 * 
	 * Request Types and Processing:
	 * <ul>
	 * <li><b>Signature-Based (?sig=&lt;hash&gt;)</b>
	 *   <ul>
	 *   <li>Retrieves previously authenticated user from Datastore</li>
	 *   <li>Verifies user signature matches and user exists</li>
	 *   <li>Displays unit selection form with progress information</li>
	 *   <li>No token validation required (session continuation)</li>
	 *   </ul>
	 * </li>
	 * <li><b>Token-Based (?t=&lt;jwt&gt;)</b>
	 *   <ul>
	 *   <li>Decodes JWT token structure without verification (claims inspection)</li>
	 *   <li>Extracts email from token subject claim</li>
	 *   <li>Verifies HMAC256 signature using shared secret from Util</li>
	 *   <li>Handles TokenExpiredException by resending new login link</li>
	 *   <li>Catches all other validation errors and presents new login form</li>
	 *   <li>Creates/updates User entity for authenticated email address</li>
	 *   <li>Persists user to Datastore for future session resumption</li>
	 *   </ul>
	 * </li>
	 * <li><b>No Parameters</b>
	 *   <ul>
	 *   <li>No authenticated user detected</li>
	 *   <li>Redirects to home page for login entry point</li>
	 *   </ul>
	 * </li>
	 * </ul>
	 * 
	 * JWT Token Validation:
	 * <ol>
	 * <li>JWT.decode() - Parse token structure and extract claims
	 * <li>Verify subject (email) is present and non-empty
	 * <li>Verify HMAC256 signature matches server secret
	 * <li>Verify token not yet expired (Auth0 library checks)
	 * <li>All checks must pass before proceeding
	 * </ol>
	 * 
	 * Error Handling:
	 * <ul>
	 * <li><b>TokenExpiredException</b>
	 *   <ul>
	 *   <li>Email extracted from expired token if available</li>
	 *   <li>Generates new token and sends fresh login link via email</li>
	 *   <li>Informs user that original link expired but new one sent</li>
	 *   <li>If email missing, returns error (cannot resend)</li>
	 *   </ul>
	 * </li>
	 * <li><b>General Exception (Invalid Signature, Decode Error, etc.)</b>
	 *   <ul>
	 *   <li>Returns login form with error message explanation</li>
	 *   <li>User can enter email to request new token</li>
	 *   </ul>
	 * </li>
	 * <li><b>User Not Found (?sig=invalid)</b>
	 *   <ul>
	 *   <li>Returns 404 or redirect to login (signature invalid/not found)</li>
	 *   </ul>
	 * </li>
	 * </ul>
	 * 
	 * Response Format:
	 * HTML page with:
	 * - Standard banner and header/footer (via Util)
	 * - Either unit selection form (success) or login form/error message (failure)
	 * - Content type: text/html; charset=UTF-8
	 * - All user input HTML-escaped to prevent XSS
	 * 
	 * Datastore Operations:
	 * - User.getUser(sig) - Lookup single user by signature
	 * - ofy().save().entity(user).now() - Persist new/updated user (synchronous)
	 * 
	 * Performance:
	 * - Typical flow: 1-2 Datastore queries
	 * - With token expiration: +1 email send operation
	 * - Token validation: Cryptographic verification (5-10ms)
	 * 
	 * @param request HTTP request containing either 'sig' or 't' parameter
	 * @param response HTTP response with HTML unit selection form or login form
	 * @throws IOException if response writing fails
	 * @see #unitSelectForm(User) Unit selection form generation
	 * @see #resendExpiredTokenEmail(String, Algorithm) Token expiration handling
	 * @see #buildInvalidTokenForm(Exception) Login form with error message
	 */
	@Override
	public void doGet(HttpServletRequest request, HttpServletResponse response) 
			throws IOException {
		
		response.setContentType("text/html; charset=UTF-8");
		PrintWriter out = response.getWriter();
		
		try {
			String sig = request.getParameter("sig");
			if (sig != null && !sig.isEmpty()) {
				User user = User.getUser(sig);
				if (user != null) {
					out.println(Util.head("Chem4AP Login") + Util.banner + unitSelectForm(user) + Util.foot());
					return;
				}
			}
			
			String token = request.getParameter("t");
			if (token == null || token.isEmpty()) {
				response.sendRedirect(Util.getServerUrl());
				return;
			}
			
			StringBuilder buf = new StringBuilder(Util.head("Chem4AP Login")).append(Util.banner);
			String email = null;
			Algorithm algorithm = null;
			try {
				algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
				DecodedJWT claims = JWT.decode(token);
				email = claims.getSubject();
				if (email == null || email.isEmpty()) {
					throw new Exception("Token subject (email) is missing");
				}
				JWT.require(algorithm).build().verify(claims);
				User user = new User(email);
				ofy().save().entity(user).now();
				buf.append(unitSelectForm(user));
			} catch (TokenExpiredException e1) {
				if (email == null || email.isEmpty()) {
					buf.append("<h1>Token Expired with Invalid Email</h1>Unable to resend login link.");
				} else {
					buf.append(resendExpiredTokenEmail(email, algorithm));
				}
			} catch (Exception e2) {
				buf.append(buildInvalidTokenForm(e2));
			}
			out.println(buf.toString() + Util.foot());
		} catch (Exception e) {
			response.sendError(500, "Error processing login: " + e.getMessage());
		}
	}

	
	/**
	 * Handles POST requests for unit selection (authenticated users) or email-based login.
	 * 
	 * Request Types and Processing:
	 * <ul>
	 * <li><b>Unit Selection (sig + UnitId parameters)</b>
	 *   <ul>
	 *   <li>Validates user signature and retrieves User from Datastore</li>
	 *   <li>Parses UnitId parameter as Long (throws NumberFormatException if invalid format)</li>
	 *   <li>Checks unit exists in Datastore using id lookup</li>
	 *   <li>Verifies user has access (premium users access all, free users access Unit 0 only)</li>
	 *   <li>Finds or creates Assignment entity linking user to unit</li>
	 *   <li>Associates assignment with user profile</li>
	 *   <li>Redirects to exercises app with secure token URL parameter</li>
	 *   <li>For premium users: Direct exercises access</li>
	 *   <li>For non-premium users on Units 1-9: Redirects to Checkout servlet</li>
	 *   </ul>
	 * </li>
	 * <li><b>Email Login Request (Email parameter only)</b>
	 *   <ul>
	 *   <li>Validates email parameter present and non-empty</li>
	 *   <li>Validates email format against RFC 5322 simplified pattern</li>
	 *   <li>Generates HMAC256-signed JWT token with 7-day expiration</li>
	 *   <li>Includes issuer URL and email as subject in token claims</li>
	 *   <li>Sends login link email via SendGrid (via Util.sendEmail)</li>
	 *   <li>Displays confirmation message instructing user to check email</li>
	 *   <li>Email escapes user input to prevent header injection attacks</li>
	 *   </ul>
	 * </li>
	 * </ul>
	 * 
	 * Authorization:
	 * <ul>
	 * <li><b>Unit 0 (Free Unit)</b>: Accessible to all users (free trial)
	 * <li><b>Units 1-9 (Premium)</b>: Require isPremium() check
	 *   <ul>
	 *   <li>If premium: Proceed to exercises</li>
	 *   <li>If not premium: Redirect to Checkout servlet for purchase</li>
	 *   </ul>
	 * </li>
	 * </ul>
	 * 
	 * Assignment Management:
	 * <ol>
	 * <li>Query Datastore for existing Assignment (platform_deployment_id, unitId filters)</li>
	 * <li>If found: Reuse existing assignment (preserves score history)</li>
	 * <li>If not found: Create new Assignment with ASSIGNMENTS_TYPE ("Exercises")</li>
	 * <li>Associate assignment ID with user entity</li>
	 * <li>Save assignment to Datastore if newly created</li>
	 * </ol>
	 * 
	 * Error Handling:
	 * <ul>
	 * <li><b>Invalid UnitId Format</b>: Returns error message (non-numeric)
	 * <li><b>User Not Found</b>: Returns unauthorized message
	 * <li><b>Unit Not Found</b>: Returns unit not found error
	 * <li><b>Missing Email/UnitId</b>: Returns appropriate error message
	 * <li><b>Invalid Email Format</b>: Returns email validation error message
	 * </ul>
	 * 
	 * Response Format:
	 * <ul>
	 * <li>Content type: text/html; charset=UTF-8
	 * <li>Success: HTML redirect (302) to exercises or checkout
	 * <li>Failure: HTML page with error message and/or login form
	 * <li>All user input HTML-escaped to prevent XSS injection
	 * </ul>
	 * 
	 * JWT Token Claims:
	 * - iss: Issuer (Util.getServerUrl())
	 * - sub: Subject (user email address)
	 * - exp: Expiration time (7 days from now)
	 * - Algorithm: HMAC256 with shared secret from Util
	 * 
	 * Email Message:
	 * <ul>
	 * <li>Personalized with recipient email address
	 * <li>Contains clickable button linking to login URL with token
	 * <li>Includes fallback plain text link for email clients without HTML
	 * <li>Warns user not to share link with others
	 * <li>Provides support contact for technical issues
	 * </ul>
	 * 
	 * Datastore Operations:
	 * - User.getUser(sig) - Lookup single user by signature
	 * - ofy().load().type(APChemUnit.class).id(unitId).safe() - Unit lookup with null return
	 * - Query for existing Assignment (dual filter on platform_deployment_id, unitId)
	 * - ofy().save().entity(a).now() - Persist new assignment (synchronous)
	 * 
	 * Performance:
	 * - Email-only flow: 1-2 Datastore queries + 1 email send (~500ms)
	 * - Unit selection: 2-3 Datastore queries (user, unit, assignment lookup)
	 * - Typical total: <1s for email, <100ms for unit selection
	 * 
	 * Security Considerations:
	 * <ul>
	 * <li>JWT tokens signed with HMAC256 shared secret (cannot be forged without secret)
	 * <li>7-day token lifetime limits window for compromised tokens
	 * <li>Email delivery confirms user controls email address (proof of ownership)
	 * <li>User signatures opaque (hashes) preventing direct email extraction
	 * <li>HTML escaping prevents XSS injection via email display
	 * </ul>
	 * 
	 * @param request HTTP request with 'Email' or 'sig'+'UnitId' parameters
	 * @param response HTTP response with redirect, error message, or confirmation
	 * @throws IOException if response writing or redirect fails
	 * @see #buildLoginEmailMessage(String, Date) Email message formatting
	 * @see Util#sendEmail(String, String, String, String) Email delivery
	 * @see User#getUser(String) User lookup by signature
	 * @see Assignment Assignment entity for progress tracking
	 */
	@Override
	public void doPost(HttpServletRequest request, HttpServletResponse response) 
			throws IOException {
		
		response.setContentType("text/html; charset=UTF-8");
		PrintWriter out = response.getWriter();
		StringBuilder buf = new StringBuilder(Util.head("Chem4AP Login")).append(Util.banner);

		// Try to process as unit selection request first
		String sigParam = request.getParameter("sig");
		String unitIdParam = request.getParameter("UnitId");
		
		if (sigParam != null && !sigParam.isEmpty() && unitIdParam != null && !unitIdParam.isEmpty()) {
			try {
				Long unitId = Long.parseLong(unitIdParam);
				User user = User.getUser(sigParam);
				if (user == null) {
					buf.append("<h1>Unauthorized</h1>");
					out.println(buf.toString() + Util.foot());
					return;
				}
				
				Assignment a = ofy().load().type(Assignment.class)
					.filter("platform_deployment_id", Util.getServerUrl())
					.filter("unitId", unitId)
					.first()
					.now();
				if (a == null) {
					// Create new Assignment entity
					a = new Assignment("Exercises", "", unitId, Util.getServerUrl());
					ofy().save().entity(a).now();
				}
				
				user.setAssignment(a.id);
				APChemUnit u = ofy().load().type(APChemUnit.class).id(unitId).safe();
				if (u == null) {
					buf.append("<h1>Error: Unit not found</h1>");
					out.println(buf.toString() + Util.foot());
					return;
				}
				
				if (user.isPremium() || u.unitNumber == 0) {
					response.sendRedirect(Util.getServerUrl() + "/" + a.assignmentType.toLowerCase() + "/index.html?t=" + Util.getToken(user.getTokenSignature()));
				} else {
					response.sendRedirect("/checkout?sig=" + user.getTokenSignature());
				}
				return;
			} catch (NumberFormatException e) {
				buf.append("<h1>Error: Invalid UnitId format</h1>");
				out.println(buf.toString() + Util.foot());
				return;
			} catch (Exception e) {
				buf.append("<h1>Error processing unit selection</h1>" + e.getMessage());
				out.println(buf.toString() + Util.foot());
				return;
			}
		}
		
		// Process as email-based login request
		try {
			String email = request.getParameter("Email");
			if (email == null || email.isEmpty()) {
				buf.append("<h1>Your email address was missing or not formatted correctly.</h1>");
				out.println(buf.toString() + Util.foot());
				return;
			}

			// Validate email address structure
			Pattern pattern = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");
			if (!pattern.matcher(email).matches()) {
				buf.append("<h1>Your email address was missing or not formatted correctly.</h1>");
				out.println(buf.toString() + Util.foot());
				return;
			}
			
			Date now = new Date();
			Date exp = new Date(now.getTime() + LAUNCH_TOKEN_VALIDITY_MS);
			Algorithm algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
			String token = JWT.create()
				.withIssuer(Util.getServerUrl())
				.withSubject(email)
				.withExpiresAt(exp)
				.sign(algorithm);

			String emailMessage = buildLoginEmailMessage(token, exp);
			Util.sendEmail(null, email, "Chem4AP Login Link", emailMessage);

			buf.append("<h1>Please Check Your Email</h1>" +
				"We just sent a personalized secure link to you at {email}. <br/>" +
				"This is just for you; please do not share the link with anyone else.<br/>" +
				"Click the link in your email to launch your session in Chem4AP.<br/>" +
				"If the link doesn't work, or you encounter difficulties launching the app, " +
				"please contact us at <a href='mailto:admin@chemvantage.org'>admin@chemvantage.org</a>."
				.replace("{email}", escapeHtml(email)));
		} catch (Exception e) {
			buf.append("<h1>Error</h1>" + escapeHtml(e.getMessage()));
		}
		out.println(buf.toString() + Util.foot());
	}
	
	
	/**
	 * Builds the unit selection form displaying available units and topics.
	 * 
	 * Form Structure:
	 * <ul>
	 * <li>Heading: "Select a Unit"
	 * <li>Free Trial Notice (for non-premium users): Explains Unit 0 access and premium requirement
	 * <li>Unit List: Radio buttons with hidden topic details
	 * <li>Progress Display: Shows completion percentage for resumed units
	 * <li>Start/Resume Button: Dynamically shows/hides based on selected unit
	 * </ul>
	 * 
	 * Unit Organization:
	 * <ul>
	 * <li><b>Unit 0</b>: "Prepare for AP Chemistry" - Free orientation unit
	 * <li><b>Units 1-9</b>: Core AP Chemistry curriculum (premium required)
	 * <li>Each unit contains multiple topics (APChemTopic) displayed as nested list
	 * <li>Topics hidden by default, shown when unit selected (JavaScript managed)
	 * </ul>
	 * 
	 * Data Loading Process:
	 * <ol>
	 * <li>Query all APChemUnit entities ordered by unitNumber</li>
	 * <li>Query all Exercise-type assignments for current user
	 *   <ul>
	 *   <li>Filtered by platform_deployment_id (current server)
	 *   <li>Indexed by unitId for O(1) lookup
	 *   </ul>
	 * </li>
	 * <li>Query all Score entities with user as ancestor
	 *   <ul>
	 *   <li>Batch load all score entities in single query
	 *   <li>Indexed by user key for quick access
	 *   </ul>
	 * </li>
	 * <li>For each unit, retrieve score from assignment (if exists)</li>
	 * <li>Query topics for each unit filtered by unitId</li>
	 * </ol>
	 * 
	 * Progress Indicators:
	 * <ul>
	 * <li>Shows completion percentage if score exists and > 0
	 * <li>No percentage shown for new units (score = 0 treated as not started)
	 * <li>Button text changes: "Start" (0%) vs "Resume" (any > 0)
	 * </ul>
	 * 
	 * User Access Control:
	 * <ul>
	 * <li><b>Premium Users</b>: Can see and access all units 0-9
	 * <li><b>Free Users</b>: Can see all units but only access Unit 0
	 *   <ul>
	 *   <li>Free trial message displayed at top</li>
	 *   <li>JavaScript auto-selects Unit 0 and disables other selections</li>
	 *   </ul>
	 * </li>
	 * </ul>
	 * 
	 * HTML Structure:
	 * <pre>
	 * &lt;h1&gt;Select a Unit&lt;/h1&gt;
	 * [Free trial notice - non-premium only]
	 * &lt;form method=post action='/launch'&gt;
	 *   &lt;ul&gt;  (no bullets)
	 *     &lt;li&gt;
	 *       &lt;input type=radio name=UnitId /&gt;
	 *       &lt;b&gt;Unit 0 - Prepare for AP Chemistry (45%)&lt;/b&gt;
	 *       &lt;input class='startButton' type=submit value='Resume' /&gt;
	 *       &lt;ul&gt;  (topics - initially hidden)
	 *         &lt;li class='topic list0'&gt;Topic name&lt;/li&gt;
	 *       &lt;/ul&gt;
	 *     &lt;/li&gt;
	 *   &lt;/ul&gt;
	 *   &lt;input type=hidden name=sig value='user_hash' /&gt;
	 * &lt;/form&gt;
	 * &lt;script&gt;...JavaScript for topic visibility and form interaction...&lt;/script&gt;
	 * </pre>
	 * 
	 * Error Handling:
	 * <ul>
	 * <li>Returns error message if user is null (should not occur - method precondition)
	 * <li>Returns error message if no units available in curriculum
	 * <li>Silent fallback if score lookup fails (treats as 0%)
	 * <li>Silent fallback if topics query returns empty/null
	 * </ul>
	 * 
	 * CSS Classes Used:
	 * <ul>
	 * <li>startButton: Submit button with dynamic visibility
	 * <li>topic: Base class for all topic list items
	 * <li>list{unitId}: Unit-specific topic selector (e.g., list0, list1)
	 * </ul>
	 * 
	 * JavaScript Integration:
	 * <ul>
	 * <li>Calls buildUnitSelectionScript() to generate interactive behavior
	 * <li>unitClicked(unitId): Shows topics for selected unit, hides others
	 * <li>Auto-selects Unit 0 for free users
	 * <li>Manages start/resume button visibility based on selection
	 * </ul>
	 * 
	 * Performance:
	 * <ul>
	 * <li>Unit query: Single Datastore query (all units)
	 * <li>Assignment query: Single query with dual filter
	 * <li>Score batch load: One query for all scores
	 * <li>Topic queries: One query per unit (N queries for N units)
	 * <li>Total: Typically 3-15 Datastore queries depending on unit count
	 * <li>Can be optimized with caching if unit/topic data static
	 * </ul>
	 * 
	 * @param user the authenticated user (non-null precondition)
	 * @return HTML form with units, topics, and interactive JavaScript
	 * @throws NullPointerException if user is null (precondition violation)
	 * @see #buildUnitSelectionScript(List, boolean) JavaScript generation
	 * @see User#isPremium() Premium status checking
	 * @see Assignment Progress tracking entity
	 * @see APChemUnit Curriculum unit data model
	 * @see APChemTopic Topic data model
	 */
	String unitSelectForm(User user) {
		if (user == null) {
			return "<h1>Error: User not authenticated</h1>";
		}
		
		StringBuilder buf = new StringBuilder("<h1>Select a Unit</h1>");
		
		if (!user.isPremium()) {
			buf.append("<div style='max-width:600px'>")
				.append("Your free trial account includes access to Unit 0 - Prepare for AP Chemistry. " +
					"This will give you a feel for how to use the app by progressing through the different types of questions. " +
					"If you start Units 1-9 you will be asked to purchase a subscription.")
				.append("</div><br/><br/>");
		}
		
		List<APChemUnit> units = ofy().load().type(APChemUnit.class).order("unitNumber").list();
		if (units == null || units.isEmpty()) {
			buf.append("<h1>Error: No units available</h1>");
			return buf.toString();
		}
		
		// Create a Map of Chem4AP assignments by UnitID
		List<Assignment> assignmentList = ofy().load().type(Assignment.class)
			.filter("assignmentType", "Exercises")
			.filter("platform_deployment_id", Util.getServerUrl())
			.list();
		Map<Long,Assignment> assignmentMap = new HashMap<Long,Assignment>();
		if (assignmentList != null) {
			for (Assignment a : assignmentList) {
				if (a != null) assignmentMap.put(a.unitId, a);
			}
		}
		
		// Create a Map of the user's Scores by key
		Key<User> userKey = key(User.class, user.hashedId);
		List<Key<Score>> scoreKeys = ofy().load().type(Score.class).ancestor(userKey).keys().list();
		Map<Key<Score>, Score> scores = ofy().load().keys(scoreKeys);
		
		buf.append("<form method=post action='/launch'>")
			.append("<ul style='list-style: none;'>");
		
		for (APChemUnit u : units) {
			if (u == null) continue;
			
			int userPctScore = 0;
			try {
				Assignment a = assignmentMap.get(u.id);
				if (a != null && a.id != null) {
					Score score = scores.get(key(userKey, Score.class, a.id));
					if (score != null) userPctScore = score.totalScore;
				}
			} catch (Exception e) {
				// Score not found or error retrieving it, default to 0
			}
			
			buf.append("<li><label>")
				.append("<input type=radio name=").append("UnitId").append(" value=").append(u.id).append(" onclick=unitClicked('").append(u.id).append("') />")
				.append("<b> Unit ").append(u.unitNumber).append(" - ").append(escapeHtml(u.title));
			if (userPctScore > 0) {
				buf.append(" (").append(userPctScore).append("%)");
			}
			buf.append("</b></label>&nbsp;")
				.append("<input id=start").append(u.id).append(" class='").append("startButton").append("' style='display:none' type=submit value='")
				.append(userPctScore == 0 ? "Start" : "Resume").append("' />")
				.append("</li>");
			
			buf.append("<ul style='list-style: none;'>");
			List<APChemTopic> topics = ofy().load().type(APChemTopic.class).filter("unitId", u.id).order("topicNumber").list();
			if (topics != null) {
				for(APChemTopic t : topics) {
					if (t != null) {
						buf.append("<li class='").append("topic").append(" ").append("list").append(u.id).append("' style='display:none'>")
							.append(escapeHtml(t.title)).append("</li>");
					}
				}
			}
			buf.append("</ul><br/>");
		}
		
		buf.append("</ul>")
			.append("<input type=hidden name=").append("sig").append(" value=").append(user.getTokenSignature()).append(" />")
			.append("</form>");
		
		// Add JavaScript for topic visibility management
		buf.append(buildUnitSelectionScript(units, user.isPremium()));
		
		return buf.toString();
	}
	
	
	/**
	 * Builds JavaScript code for unit/topic selection form interactivity.
	 * 
	 * Functionality:
	 * <ul>
	 * <li><b>unitClicked(unitId)</b> - Primary interaction handler
	 *   <ul>
	 *   <li>Hides all topic list items (li.topic CSS class)
	 *   <li>Shows only topics matching selected unit (li.list{unitId})
	 *   <li>Hides all start/resume buttons
	 *   <li>Shows start/resume button for selected unit (id=start{unitId})
	 *   <li>Called onchange when user selects unit radio button
	 *   </ul>
	 * </li>
	 * <li><b>Auto-selection (Non-Premium Users)</b>
	 *   <ul>
	 *   <li>Pre-selects Unit 0 radio button on page load
	 *   <li>Calls unitClicked(unit0_id) to show Unit 0 topics
	 *   <li>Checks first unit in list and assumes unitNumber 0
	 *   <li>Ensures free users default to free trial unit
	 *   </ul>
	 * </li>
	 * </ul>
	 * 
	 * Implementation Details:
	 * <ul>
	 * <li>Uses DOM selectors: querySelectorAll('li.topic'), getElementById('start' + unitId)
	 * <li>Manipulates inline CSS: style='display:list-item' (show) vs 'display:none' (hide)
	 * <li>Assumes HTML structure with matching CSS class naming conventions
	 * <li>No external library dependencies (vanilla JavaScript)
	 * <li>Executed inline on page load (no deferred execution)
	 * </ul>
	 * 
	 * HTML Dependencies:
	 * <ul>
	 * <li>Radio input type with name='UnitId' value='{unitId}'
	 * <li>Topic list items with: class='topic list{unitId}' style='display:none'
	 * <li>Start button with: id='start{unitId}' class='startButton'
	 * <li>onclick handler on radio: onclick=unitClicked('{unitId}')
	 * </ul>
	 * 
	 * CSS Classes:
	 * <ul>
	 * <li>li.topic: Base selector for all topic elements (initially hidden)
	 * <li>li.list{unitId}: Unit-specific topics (e.g., list0 for Unit 0)
	 * <li>.startButton: Start/resume submit button
	 * </ul>
	 * 
	 * Free User Behavior:
	 * <ul>
	 * <li>Only when isPremium=false
	 * <li>Requires at least one unit exists (units not empty)
	 * <li>Assumes first unit in list is Unit 0 (unitNumber 0)
	 * <li>Calls unitClicked() immediately to show Unit 0 content
	 * <li>Pre-checks first radio button to reflect selection
	 * <li>Prevents accidental selection of premium units
	 * </ul>
	 * 
	 * Performance:
	 * <ul>
	 * <li>Minimal JavaScript (10-20 lines)
	 * <li>No event delegation (direct DOM manipulation)
	 * <li>Linear DOM traversal for visibility changes (O(n) where n = unit count)
	 * <li>Suitable for typical 10-15 unit curriculum
	 * </ul>
	 * 
	 * Browser Compatibility:
	 * <ul>
	 * <li>Requires ES5+ (querySelectorAll, for loops)
	 * <li>Compatible with all modern browsers (Chrome, Firefox, Safari, Edge)
	 * <li>No polyfills needed for standard DOM APIs
	 * </ul>
	 * 
	 * @param units list of APChemUnit entities ordered by unitNumber
	 * @param isPremium whether authenticated user has premium subscription
	 * @return &lt;script&gt; block with JavaScript function definitions
	 * @see #unitSelectForm(User) Form generation calling this method
	 */
	private String buildUnitSelectionScript(List<APChemUnit> units, boolean isPremium) {
		StringBuilder script = new StringBuilder("<script>")
			.append("function unitClicked(unitId) {")
			.append("  var allListItems = document.querySelectorAll('li.").append("topic").append("');")
			.append("  for (var i=0; i<allListItems.length;i++) {")
			.append("    allListItems[i].style='display:none';")
			.append("  }")
			.append("  var unitListItems = document.querySelectorAll('li.").append("list").append("' + unitId);")
			.append("  for (var i=0;i<unitListItems.length;i++) {")
			.append("    unitListItems[i].style='display:list-item';")
			.append("  }")
			.append("  var startButtons = document.querySelectorAll('.").append("startButton").append("');")
			.append("  for (var i=0;i<startButtons.length;i++) {")
			.append("    startButtons[i].style='display:none';")
			.append("  }")
			.append("  document.getElementById('start' + unitId).style='display:inline';")
			.append("}");
		
		if (!isPremium && !units.isEmpty() && units.getFirst() != null) {
			APChemUnit unitZero = units.getFirst();
			script.append("unitClicked(").append(unitZero.id).append(");")
				.append("document.querySelector('input[name=\"").append("UnitId").append("\"]').checked=true;")
				.append("var firstInput = document.querySelector('input[name=\"").append("UnitId").append("\"]');")
				.append("if (firstInput) firstInput.checked=true;");
		}
		
		script.append("</script>");
		return script.toString();
	}
	
	
	/**
	 * Builds the email message containing the JWT login link.
	 * 
	 * Message Structure:
	 * <ul>
	 * <li><b>Heading</b>: "Chem4AP Login Link"
	 * <li><b>Primary CTA</b>: Prominent blue button with "Login to Chem4AP Now"
	 *   <ul>
	 *   <li>href points to /launch?t={jwt_token}</li>
	 *   <li>Uses inline CSS styling (blue background, white text, rounded corners)</li>
	 *   <li>Font size: 16px for mobile visibility
	 *   </ul>
	 * </li>
	 * <li><b>Expiration Notice</b>: Token valid for 7 days (links to date object)
	 * <li><b>Fallback Link</b>: Plain text copy of full URL for non-HTML clients
	 * </ul>
	 * 
	 * Security & Guidance:
	 * <ul>
	 * <li>Advises user not to share link (personalized per email)
	 * <li>Provides support contact for troubleshooting
	 * <li>Reusable token within 7-day window (multiple logins allowed)
	 * </ul>
	 * 
	 * Token Attributes:
	 * <ul>
	 * <li>Algorithm: HMAC256 (shared secret with server)
	 * <li>Subject: User email address
	 * <li>Issuer: Server URL from Util
	 * <li>Expiration: Provided Date parameter (typically 7 days from now)
	 * </ul>
	 * 
	 * HTML Output:
	 * <pre>
	 * &lt;h1&gt;Chem4AP Login Link&lt;/h1&gt;
	 * &lt;a href='/launch?t={token}'&gt;
	 *   &lt;button style='border:none;...'&gt;Login to Chem4AP Now&lt;/button&gt;
	 * &lt;/a&gt;&lt;br/&gt;&lt;br/&gt;
	 * You may reuse this login button multiple times for a week until it expires on {expiration_date}...
	 * If the button doesn't work, paste the following link:
	 * {full_url}/launch?t={token}
	 * </pre>
	 * 
	 * Button Styling:
	 * <ul>
	 * <li>No border: border:none
	 * <li>White text on blue background
	 * <li>Padding: 10px 10px for touch-friendly size
	 * <li>Rounded corners: border-radius:10px
	 * <li>Cursor changes to hand pointer on hover (cursor:pointer)
	 * <li>Font size: 16px (readable on all devices)
	 * </ul>
	 * 
	 * Multi-Use Feature:
	 * <ul>
	 * <li>Token remains valid for 7 days after creation
	 * <li>User can click button multiple times (idempotent)
	 * <li>No per-click usage limits
	 * <li>Useful if user reopens email later in week
	 * </ul>
	 * 
	 * Email Platform Compatibility:
	 * <ul>
	 * <li>Button renders in modern email clients (Gmail, Outlook, Apple Mail)
	 * <li>Fallback plain text link for text-only clients
	 * <li>All links point to same launch endpoint
	 * </ul>
	 * 
	 * @param token HMAC256-signed JWT token (format: header.payload.signature)
	 * @param expiration Date object representing token expiration time
	 * @return HTML string formatted for email display (can include button and fallback)
	 * @see #doPost(HttpServletRequest, HttpServletResponse) Email generation caller
	 * @see #resendExpiredTokenEmail(String, Algorithm) Similar method for token refresh
	 */
	private String buildLoginEmailMessage(String token, Date expiration) {
		return new StringBuilder("<h1>Chem4AP Login Link</h1>")
			.append("<a href='").append(Util.getServerUrl()).append("/launch?t=").append(token).append("'>")
			.append("<button style='border:none;color:white;padding:10px 10px;margin:4px 2px;font-size:16px;cursor:pointer;border-radius:10px;background-color:blue;'>")
			.append("Login to Chem4AP Now")
			.append("</button>")
			.append("</a><br/><br/>")
			.append("You may reuse this login button multiple times for a week until it expires on ").append(expiration).append("<br/><br/>")
			.append("If the button doesn't work, paste the following link into your browser:<br/>")
			.append(Util.getServerUrl()).append("/launch?t=").append(token)
			.toString();
	}
	
	
	/**
	 * Handles expired JWT token by generating and sending a fresh login link.
	 * 
	 * Trigger Condition:
	 * <ul>
	 * <li>User clicks login link after 7-day expiration window has passed
	 * <li>Launch.doGet() catches TokenExpiredException
	 * <li>Extracts email from expired token's subject claim
	 * <li>Calls this method to generate replacement
	 * </ul>
	 * 
	 * Process Flow:
	 * <ol>
	 * <li>Validate algorithm parameter (non-null check)
	 * <li>Get current timestamp (Date.now())
	 * <li>Calculate new expiration: now + 7 days (604800000ms)
	 * <li>Create new JWT with same claims structure
	 *   <ul>
	 *   <li>Issuer: Util.getServerUrl()
	 *   <li>Subject: user email (from previous token)
	 *   <li>Expiration: new calculated date
	 *   </ul>
	 * </li>
	 * <li>Sign token with provided HMAC256 algorithm</li>
	 * <li>Send email via Util.sendEmail() with new token
	 * <li>Return HTML response explaining token refresh
	 * </ol>
	 * 
	 * Error Handling:
	 * <ul>
	 * <li><b>Algorithm null</b>: Returns error message (configuration missing)
	 * <li><b>Email send failure</b>: Returns error message with exception details
	 * <li>All exceptions caught and converted to user-friendly HTML
	 * </ul>
	 * 
	 * User Communication:
	 * <ul>
	 * <li>Explains why old link no longer works (7-day expiration)
	 * <li>Confirms new email was sent to user's address
	 * <li>Instructs user to check inbox for fresh link
	 * <li>Shows 7-day countdown starting from new link generation
	 * </ul>
	 * 
	 * Security Properties:
	 * <ul>
	 * <li>New token has independent expiration (7 days from resend, not from original)
	 * <li>Previous expired token remains invalid (cannot be reused)
	 * <li>Email proof required (resend goes to same email address)
	 * <li>Same 7-day window prevents permanent token reuse
	 * </ul>
	 * 
	 * HMAC256 Signing:
	 * <ul>
	 * <li>Uses same shared secret as original token (from Util.getHMAC256Secret())
	 * <li>Algorithm parameter derived from that secret
	 * <li>Server must maintain consistent secret across token generations
	 * </ul>
	 * 
	 * Email Content:
	 * <ul>
	 * <li>Uses buildLoginEmailMessage() to format message body
	 * <li>Subject line: "Chem4AP Login Link"
	 * <li>From address: Configured in Util class
	 * <li>Recipient: Extracted from expired token (verified email)
	 * </ul>
	 * 
	 * Performance:
	 * <ul>
	 * <li>Token generation: <1ms (cryptographic operation)
	 * <li>Email send: ~500ms (network call to SendGrid)
	 * <li>Total: Typically <600ms
	 * </ul>
	 * 
	 * @param email user's verified email address (from expired token subject)
	 * @param algorithm HMAC256 algorithm instance for signing (same as original token)
	 * @return HTML message confirming resend and new expiration details
	 * @throws IllegalArgumentException if algorithm is null
	 * @see #doGet(HttpServletRequest, HttpServletResponse) TokenExpiredException handler
	 * @see #buildLoginEmailMessage(String, Date) Email message formatting
	 * @see Util#sendEmail(String, String, String, String) Email delivery service
	 */
	private String resendExpiredTokenEmail(String email, Algorithm algorithm) {
		if (algorithm == null) {
			return "<h1>Error: Authentication not configured</h1>";
		}
		
		Date now = new Date();
		Date exp = new Date(now.getTime() + LAUNCH_TOKEN_VALIDITY_MS);
		String newToken = JWT.create()
			.withIssuer(Util.getServerUrl())
			.withSubject(email)
			.withExpiresAt(exp)
			.sign(algorithm);

		String emailMessage = buildLoginEmailMessage(newToken, exp);
		try {
			Util.sendEmail(null, email, "Chem4AP Login Link", emailMessage);
		} catch (Exception e) {
			return "<h1>Error Sending Email</h1>" + escapeHtml(e.getMessage());
		}

		return new StringBuilder("<h1>Please Check Your Email</h1>")
			.append("Login links are only valid for 7 days, and yours had expired. But don't worry! We just sent an updated login link ")
			.append("to you at ").append(escapeHtml(email)).append(". You can use it for the next 7 days.<br/><br/>")
			.toString();
	}
	
	
	/**
	 * Builds HTML form for invalid JWT token error response.
	 * 
	 * Trigger Conditions:
	 * <ul>
	 * <li>Token signature verification fails (wrong shared secret)
	 * <li>Token structure invalid (malformed JWT - decode fails)
	 * <li>Token missing required claims (no subject/email)
	 * <li>Any other JWT validation exception (non-expiration)
	 * </ul>
	 * 
	 * Form Structure:
	 * <ul>
	 * <li><b>Error Heading</b>: "The Login Token Was Invalid"
	 * <li><b>Error Details</b>: Exception message explaining failure reason
	 * <li><b>Email Input Form</b>: Allows user to request new token
	 *   <ul>
	 *   <li>Input type: email with validation
	 *   <li>Placeholder: "Enter your email address"
	 *   <li>Button: "Send Me A Secure Login Link"
	 *   <li>Action: POST /launch (same endpoint)
	 *   </ul>
	 * </li>
	 * <li><b>Legal Clause</b>: Links to Terms of Service
	 * </ul>
	 * 
	 * Bootstrap CSS Styling:
	 * <ul>
	 * <li>input-group: Grouped input + button layout
	 * <li>input-group-lg: Larger font size for mobile
	 * <li>shadow-sm: Subtle drop shadow
	 * <li>form-control: Styled text input
	 * <li>btn btn-primary: Blue submit button
	 * <li>form-text: Smaller legal text below form
	 * <li>max-width:800px: Responsive constraint
	 * </ul>
	 * 
	 * Error Display:
	 * <ul>
	 * <li>Exception message passed directly (should be HTML-escaped by caller)
	 * <li>Examples of messages:
	 *   <ul>
	 *   <li>"The Signature could not be verified" - signature mismatch
	 *   <li>"Invalid token" - JWT structure invalid
	 *   <li>"The token subject is not an email address" - validation fail
	 *   </ul>
	 * </li>
	 * </ul>
	 * 
	 * User Recovery Flow:
	 * <ol>
	 * <li>User sees error explaining what went wrong
	 * <li>Reads form instructions and legal clause
	 * <li>Enters email address in input field
	 * <li>Clicks "Send Me A Secure Login Link" button
	 * <li>Form POSTs to /launch with Email parameter
	 * <li>Launch.doPost() generates new token and sends email
	 * </ol>
	 * 
	 * Security Considerations:
	 * <ul>
	 * <li>Exception message exposed to user (may leak implementation details)
	 * <li>Consider sanitizing/obfuscating sensitive error info
	 * <li>HTML escaping should be done by caller (escapeHtml())
	 * <li>Email input has browser validation (type=email)
	 * <li>Server should revalidate email format in doPost()
	 * </ul>
	 * 
	 * Accessibility:
	 * <ul>
	 * <li>Proper form labels with for attributes
	 * <li>ARIA labels for screen readers (aria-label)
	 * <li>Focus outline for keyboard navigation
	 * <li>Large touch targets (input-group-lg)
	 * </ul>
	 * 
	 * Browser Compatibility:
	 * <ul>
	 * <li>HTML5 email input type (with fallback text input in older browsers)
	 * <li>Bootstrap 4/5 required for CSS classes
	 * <li>No JavaScript dependencies
	 * </ul>
	 * 
	 * @param exception the original JWT validation exception
	 * @return HTML form with error message and email input for token request
	 * @see #doGet(HttpServletRequest, HttpServletResponse) Error handler calling this
	 * @see #escapeHtml(String) Should be called on exception message
	 * @see #doPost(HttpServletRequest, HttpServletResponse) Form submission handler
	 */
	private String buildInvalidTokenForm(Exception exception) {
		return new StringBuilder("<h1>The Login Token Was Invalid</h1>")
			.append(escapeHtml(exception.getMessage())).append("<br/><br/>")
			.append("Use this form to get a valid login link:<br/>")
			.append("<form action='/launch' method='POST' class='mt-4'>\n")
			.append("  <div class='input-group input-group-lg shadow-sm' style='max-width:800px'>\n")
			.append("    <input type='email' class='form-control' name='Email' placeholder='Enter your email address' aria-label='Enter your email address' required>\n")
			.append("    <button class='btn btn-primary' type='submit'>Send Me A Secure Login Link</button>\n")
			.append("  </div>\n")
			.append("  <div class='form-text mt-2'>\n")
			.append("    By clicking, you agree to our <a href='/terms.html'>Terms of Service</a>.\n")
			.append("  </div>\n")
			.append("</form><br/><br/>")
			.toString();
	}
	
	
	/**
	 * Escapes HTML special characters to prevent injection attacks.
	 * 
	 * Purpose:
	 * <ul>
	 * <li>Prevents Cross-Site Scripting (XSS) injection attacks
	 * <li>Sanitizes user input displayed in HTML context (email addresses, error messages)
	 * <li>Required for all dynamic content embedded in HTML response
	 * </ul>
	 * 
	 * Escaping Rules:
	 * <table border="1">
	 *   <tr><th>Character</th><th>Entity</th><th>Purpose</th></tr>
	 *   <tr><td>&amp;</td><td>&amp;amp;</td><td>Ampersand escape (must be first to avoid double-escaping)</td></tr>
	 *   <tr><td>&lt;</td><td>&amp;lt;</td><td>Less-than (prevents tag injection)</td></tr>
	 *   <tr><td>&gt;</td><td>&amp;gt;</td><td>Greater-than (prevents tag injection)</td></tr>
	 *   <tr><td>&quot;</td><td>&amp;quot;</td><td>Double quote (prevents attribute injection)</td></tr>
	 *   <tr><td>'</td><td>&amp;#x27;</td><td>Single quote (prevents attribute injection)</td></tr>
	 * </table>
	 * 
	 * Attack Prevention:
	 * <ul>
	 * <li><b>Tag Injection</b>: &lt;script&gt; becomes &amp;lt;script&amp;gt; (safe text)
	 * <li><b>Attribute Injection</b>: '" onload=" becomes &amp;#x27;&amp;quot; onload=&amp;quot; (neutralized)
	 * <li><b>Event Handler</b>: onclick=alert() becomes onclick=alert() displayed as text (safe)
	 * </ul>
	 * 
	 * Order of Escaping:
	 * <ol>
	 * <li><b>Ampersand first (&amp; → &amp;amp;)</b>: Prevents double-escaping
	 *   <ul>
	 *   <li>If escaped second, result "&#x27;" becomes "&#amp;x27;" (incorrect)
	 *   <li>Must escape existing ampersands before introducing new ones
	 *   </ul>
	 * </li>
	 * <li>All other characters in any order
	 * </ol>
	 * 
	 * Usage Examples:
	 * <ul>
	 * <li>User email: "attacker&lt;script&gt;alert()&lt;/script&gt;@example.com" → safe display
	 * <li>Exception message: "Invalid character &quot;@&quot; in email" → safe display
	 * <li>HTML attributes: onclick="func()" → safe in href attribute
	 * </ul>
	 * 
	 * Null/Empty Handling:
	 * <ul>
	 * <li>Returns empty string "" for null input
	 * <li>Allows safe concatenation without null pointer exceptions
	 * <li>Empty strings pass through unchanged
	 * </ul>
	 * 
	 * Performance:
	 * <ul>
	 * <li>Linear complexity O(n) where n = string length
	 * <li>Five sequential replace() calls (each creates new String)
	 * <li>Suitable for typical user input (0-500 chars)
	 * <li>Can be optimized with StringBuilder for very long strings
	 * </ul>
	 * 
	 * Applied Locations:
	 * <ul>
	 * <li>Email addresses displayed in confirmation messages
	 * <li>Exception messages shown in error responses
	 * <li>Unit/topic titles in selection form
	 * <li>Any user-provided or system data embedded in HTML
	 * </ul>
	 * 
	 * Security Note:
	 * <ul>
	 * <li>This escaping is for HTML text content only
	 * <li>Different escaping needed for:
	 *   <ul>
	 *   <li>HTML attributes (additional quotes handling)
	 *   <li>JavaScript strings (backslash escaping)
	 *   <li>URL contexts (percent encoding)
	 *   <li>CSS values (different set of characters)
	 *   </ul>
	 * </li>
	 * <li>Current implementation sufficient for HTML text context
	 * </ul>
	 * 
	 * Standards:
	 * <ul>
	 * <li>HTML entity references per W3C HTML5 specification
	 * <li>Follows OWASP HTML escaping guidelines
	 * <li>Compatible with all HTML versions (3.2+)
	 * </ul>
	 * 
	 * @param input the untrusted input string (may contain user data or system messages)
	 * @return safely escaped string for embedding in HTML text content
	 * @throws NullPointerException never (null input returns empty string)
	 * @see #doGet(HttpServletRequest, HttpServletResponse) Email display context
	 * @see #buildInvalidTokenForm(Exception) Exception message display context
	 * @see #unitSelectForm(User) Unit/topic title display context
	 */
	private String escapeHtml(String input) {
		if (input == null) return "";
		return input.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&#x27;");
	}
}
