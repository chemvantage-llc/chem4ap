package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.key;
import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
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
 * Launch servlet for independent user access and LTI 1.3 launch endpoint.
 * Handles two main flows:
 * 1. Independent users: Email-based authentication with JWT token generation
 *    - GET /launch?t=<token> - Validate JWT and display unit selection form
 *    - POST /launch with Email - Generate JWT token and send via email
 * 2. LTI users: Direct launch with unit selection
 *    - POST /launch with sig, UnitId - Create assignment and redirect to exercises
 * Authentication uses HMAC256-signed JWT tokens valid for 7 days.
 */
@WebServlet(urlPatterns = {"/launch"})
public class Launch extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final long LAUNCH_TOKEN_VALIDITY_MS = 604800000L;  // 7 days in milliseconds
	private static final String EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@(.+)$";
	private static final String CHEM4AP_URL = "/launch";
	private static final String ASSIGNMENTS_TYPE = "Exercises";
	
	/**
	 * Handles GET requests for JWT token validation and unit selection.
	 * Validates login tokens and presents unit selection form for authenticated users.
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
	 * Handles POST requests for unit selection (authenticated) or email-based login requests.
	 * Processes two distinct request types:
	 * 1. Unit selection: POST with sig (signature) and UnitId parameters
	 * 2. Login request: POST with Email parameter to generate and send JWT token
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
					a = new Assignment(ASSIGNMENTS_TYPE, "", unitId, Util.getServerUrl());
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
			Pattern pattern = Pattern.compile(EMAIL_REGEX);
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

			buf.append("<h1>Please Check Your Email</h1>"
				+ "We just sent a personalized secure link to you at ").append(escapeHtml(email)).append(". <br/>"
				+ "This is just for you; please do not share the link with anyone else.<br/>"
				+ "Click the link in your email to launch your session in Chem4AP.<br/>"
				+ "If the link doesn't work, or you encounter difficulties launching the app, "
				+ "please contact us at <a href='mailto:admin@chemvantage.org'>admin@chemvantage.org</a>.");
		} catch (Exception e) {
			buf.append("<h1>Error</h1>" + escapeHtml(e.getMessage()));
		}
		out.println(buf.toString() + Util.foot());
	}
	
	/**
	 * Builds the unit selection form displaying available units and topics.
	 * Shows premium content notice for non-premium users.
	 * Displays user's progress scores and enables resuming previous work.
	 *
	 * @param user the authenticated user (cannot be null)
	 * @return HTML form for unit selection
	 */
	String unitSelectForm(User user) {
		if (user == null) {
			return "<h1>Error: User not authenticated</h1>";
		}
		
		StringBuilder buf = new StringBuilder("<h1>Select a Unit</h1>");
		
		if (!user.isPremium()) {
			buf.append("<div style='max-width:600px'>Your free trial account includes access to Unit 0 - Prepare for AP Chemistry. ")
				.append("This will give you a feel for how to use the app by progressing through the different types of questions. ")
				.append("If you start Units 1-9 you will be asked to purchase a subscription.</div><br/><br/>");
		}
		
		List<APChemUnit> units = ofy().load().type(APChemUnit.class).order("unitNumber").list();
		if (units == null || units.isEmpty()) {
			buf.append("<h1>Error: No units available</h1>");
			return buf.toString();
		}
		
		// Create a Map of Chem4AP assignments by UnitID
		List<Assignment> assignmentList = ofy().load().type(Assignment.class)
			.filter("assignmentType", ASSIGNMENTS_TYPE)
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
				.append("<input type=radio name=UnitId value=").append(u.id).append(" onclick=unitClicked('").append(u.id).append("') />")
				.append("<b> Unit ").append(u.unitNumber).append(" - ").append(escapeHtml(u.title));
			if (userPctScore > 0) {
				buf.append(" (").append(userPctScore).append("%)");
			}
			buf.append("</b></label>&nbsp;")
				.append("<input id=start").append(u.id).append(" class='startButton btn btn-primary' style='display:none' type=submit value='")
				.append(userPctScore == 0 ? "Start" : "Resume").append("' />")
				.append("</li>");
			
			buf.append("<ul style='list-style: none;'>");
			List<APChemTopic> topics = ofy().load().type(APChemTopic.class).filter("unitId", u.id).order("topicNumber").list();
			if (topics != null) {
				for(APChemTopic t : topics) {
					if (t != null) {
						buf.append("<li class='topic list").append(u.id).append("' style='display:none'>")
							.append(escapeHtml(t.title)).append("</li>");
					}
				}
			}
			buf.append("</ul><br/>");
		}
		
		buf.append("</ul>")
			.append("<input type=hidden name=sig value=").append(user.getTokenSignature()).append(" />")
			.append("</form>");
		
		// Add JavaScript for topic visibility management
		buf.append(buildUnitSelectionScript(units, user.isPremium()));
		
		return buf.toString();
	}
	
	/**
	 * Builds JavaScript for unit/topic selection form interaction.
	 * Shows/hides topics based on selected unit and auto-selects unit 0 for free users.
	 *
	 * @param units list of available units
	 * @param isPremium whether user is premium
	 * @return JavaScript code block
	 */
	private String buildUnitSelectionScript(List<APChemUnit> units, boolean isPremium) {
		StringBuilder script = new StringBuilder("<script>")
			.append("function unitClicked(unitId) {")
			.append("  var allListItems = document.querySelectorAll('li.topic');")
			.append("  for (var i=0; i<allListItems.length;i++) {")
			.append("    allListItems[i].style='display:none';")
			.append("  }")
			.append("  var unitListItems = document.querySelectorAll('li.list' + unitId);")
			.append("  for (var i=0;i<unitListItems.length;i++) {")
			.append("    unitListItems[i].style='display:list-item';")
			.append("  }")
			.append("  var startButtons = document.querySelectorAll('.startButton');")
			.append("  for (var i=0;i<startButtons.length;i++) {")
			.append("    startButtons[i].style='display:none';")
			.append("  }")
			.append("  document.getElementById('start' + unitId).style='display:inline';")
			.append("}");
		
		if (!isPremium && !units.isEmpty() && units.get(0) != null) {
			APChemUnit unitZero = units.get(0);
			script.append("unitClicked(").append(unitZero.id).append(");")
				.append("document.querySelector('input[name=\"UnitId\"]').checked=true;")
				.append("var firstInput = document.querySelector('input[name=\"UnitId\"]');")
				.append("if (firstInput) firstInput.checked=true;");
		}
		
		script.append("</script>");
		return script.toString();
	}
	
	/**
	 * Builds the login email message with token link.
	 *
	 * @param token JWT authentication token
	 * @param expiration token expiration date
	 * @return HTML formatted email message
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
	 * Handles expired token response by resending a new login link.
	 *
	 * @param email user email address
	 * @param algorithm HMAC256 algorithm for token signing
	 * @return HTML response message
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
	 * Builds HTML form for invalid token response.
	 * Allows user to enter email and request new login link.
	 *
	 * @param exception the exception that caused token validation to fail
	 * @return HTML form for email input
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
	 * @param input the input string to escape
	 * @return escaped string safe for HTML context
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
