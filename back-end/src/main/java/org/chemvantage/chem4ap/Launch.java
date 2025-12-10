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

@WebServlet(urlPatterns = {"/launch"})
public class Launch extends HttpServlet {
	private static final long serialVersionUID = 1L;
	
	/*
	 * This servlet is used to launch chem4ap for individual users not accessing the app through an LMS.
	 * Overview:
	 * 1) User POSTS an email address to this servlet; gets an email with a tokenized launch link
	 * 2) User clicks a link pointing to /launch
	 *   a. If the token is missing, redirect to Chem4AP home page
	 *   b. If the link has expired, send a new link to the same (embedded) email address.
	 *   c. If the link is otherwise invalid, give a POST form to submit the user's email address
	 *   d. If the link is valid, present a page with all units/topics and allow the user to choose a starting point
	 */
	
	@Override
	public void doGet(HttpServletRequest request, HttpServletResponse response) 
			throws IOException {
		
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		
		String sig = request.getParameter("sig");
		if (sig!=null) {
			User user = User.getUser(sig);
			if (user!=null) {
				out.println(Util.head("Chem4AP Login") + Util.banner + unitSelectForm(user) + Util.foot());
				return;
			} else response.sendRedirect(Util.getServerUrl());
		}
		
		String token = request.getParameter("t");
		if (token==null) {
			response.sendRedirect(Util.getServerUrl());
			return;
		}
		
		StringBuffer buf = new StringBuffer(Util.head("Chem4AP Login") + Util.banner);
		String email = null;
		Algorithm algorithm = null;
		try {
			algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
			DecodedJWT claims = JWT.decode(token);
			email = claims.getSubject();
			JWT.require(algorithm).build().verify(claims);
			User user = new User(email);
			ofy().save().entity(user).now();
			buf.append(unitSelectForm(user));
		} catch (TokenExpiredException e1) {
			Date now = new Date();
			Date exp = new Date(now.getTime() + 604800000L); // seven days from now
			token = JWT.create()
				.withIssuer(Util.getServerUrl())
				.withSubject(email)
				.withExpiresAt(exp)
				.sign(algorithm);

			String emailMessage = "<h1>Chem4AP Login Link</h1>"
				+ "<a href='" + Util.getServerUrl() + "/launch?t=" + token + "'>"
				+ "<button style='border:none;color:white;padding:10px 10px;margin:4px 2px;font-size:16px;cursor:pointer;border-radius:10px;background-color:blue;'>"
				+ "Login to Chem4AP Now"
				+ "</button>"
				+ "</a><br/><br/>"
				+ "You may reuse this login button multiple times for a week until it expires on " + exp.toString() + "<br/.<br/>"
				+ "If the button doesn't work, paste the following link into your browser:<br/>"
				+ Util.getServerUrl() + "/launch?t=" + token;

			Util.sendEmail(null, email, "Chem4AP Login Link", emailMessage);

			buf.append("<h1>Please Check Your Email</h1>"
				+ "Login links are only valid for 7 days, and yours had expired. But don't worry! We just sent an updated login link "
				+ "to you at " + email + ". You can use it for the next 7 days.<br/><br/>");
			
		} catch (Exception e2) {
			buf.append("<h1>The Login Token Was Invalid</h1>"
				+ e2.getMessage() + "<br/><br/>"
				+ "Use this form to get a valid login link:<br/>"
				+ "<form action='/launch' method='POST' class='mt-4'>\n"
				+ "  <div class='input-group input-group-lg shadow-sm' style='max-width:800px'>\n"
				+ "    <input type='email' class='form-control' name='Email' placeholder='Enter your email address' aria-label='Enter your email address' required>\n"
				+ "    <button class='btn btn-primary' type='submit'>Send Me A Secure Login Link</button>\n"
				+ "  </div>\n"
				+ "  <div class='form-text mt-2'>\n"
				+ "    By clicking, you agree to our <a href='/terms.html'>Terms of Service</a>.\n"
				+ "  </div>\n"
				+ "</form><br/><br/>");
			
		}
		out.println(buf.toString() + Util.foot());
		return;
	}

	@Override
	public void doPost(HttpServletRequest request, HttpServletResponse response) 
			throws IOException {
		
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		StringBuffer buf = new StringBuffer(Util.head("Chem4AP Login") + Util.banner);

		try { // see if this is a valid launch request 
			Long unitId = Long.parseLong(request.getParameter("UnitId"));
			User user = User.getUser(request.getParameter("sig"));
			if (user==null) {
				buf.append("<h1>Unauthorized</h1>");
			}
			Assignment a = ofy().load().type(Assignment.class).filter("platform_deployment_id",Util.getServerUrl()).filter("unitId",unitId).first().now();
			if (a==null) {  //create a new Assignment entity
				a = new Assignment("Exercises","",unitId,Util.getServerUrl());
				ofy().save().entity(a).now();
			}
			user.setAssignment(a.id);
			APChemUnit u = ofy().load().type(APChemUnit.class).id(unitId).safe();
			if (user.isPremium() || u.unitNumber==0) response.sendRedirect(Util.getServerUrl() + "/" + a.assignmentType.toLowerCase() + "/index.html?t=" + Util.getToken(user.getTokenSignature()));
			else response.sendRedirect("/checkout?sig=" + user.getTokenSignature());
			return;
		} catch (Exception e) {}
		
		try {
			String email = request.getParameter("Email");

			// Validate the email address structure
			String regex = "^[A-Za-z0-9+_.-]+@(.+)$";		 
			Pattern pattern = Pattern.compile(regex);
			if (pattern.matcher(email).matches()) {
				Date now = new Date();
				Date exp = new Date(now.getTime() + 604800000L); // seven days from now
				Algorithm algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
				String token = JWT.create()
						.withIssuer(Util.getServerUrl())
						.withSubject(email)
						.withExpiresAt(exp)
						.sign(algorithm);

				String emailMessage = "<h1>Chem4AP Login Link</h1>"
						+ "<a href='" + Util.getServerUrl() + "/launch?t=" + token + "'>"
						+ "<button style='border:none;color:white;padding:10px 10px;margin:4px 2px;font-size:16px;cursor:pointer;border-radius:10px;background-color:blue;'>"
						+ "Login to Chem4AP Now"
						+ "</button>"
						+ "</a><br/><br/>"
						+ "You may reuse this login button multiple times for a week until it expires on " + exp.toString() + "<br/.<br/>"
						+ "If the button doesn't work, paste the following link into your browser:<br/>"
						+ Util.getServerUrl() + "/launch?t=" + token;

				Util.sendEmail(null, email, "Chem4AP Login Link", emailMessage);

				buf.append("<h1>Please Check Your Email</h1>"
						+ "We just sent a personalized secure link to you at " + email + ".<br/>"
						+ "This is just for you; please do not share the link with anyone else.<br/>"
						+ "Click the link in your email to launch your session in Chem4AP.<br/>"
						+ "If the link doesn't work, or you encounter difficulties launching the app, "
						+ "please contact us at <a href=mailto:admin@chemvantage.org>admin@chemvantage.org</a>.");
			} else buf.append("<h1>Your email address was missing or not formatted correctly.</h1>");
		} catch (Exception e) {
			buf.append(e.getMessage());
		}
		out.println(buf.toString() + Util.foot());
	}
	
	String unitSelectForm(User user) {
		StringBuffer buf = new StringBuffer("<h1>Select a Unit</h1>");
		if (!user.isPremium()) {
			buf.append("<div style='max-width:600px'>Your free trial account includes access to Unit 0 - Prepare for AP Chemistry. "
					+ "This will give you a feel for how to use the app by progressing through the different types of questions. "
					+ "If you start Units 1-9 you will be asked to purchase a subscription.</div><br/><br/>");
		}
		List<APChemUnit> units = ofy().load().type(APChemUnit.class).order("unitNumber").list();
		
		// Create a Map of Chem4AP assignments by UnitID
		List<Assignment> assignmentList = ofy().load().type(Assignment.class).filter("assignmentType","Exercises").filter("platform_deployment_id",Util.getServerUrl()).list();
		Map<Long,Assignment> assignmentMap = new HashMap<Long,Assignment>();
		for (Assignment a : assignmentList) assignmentMap.put(a.unitId, a);
		
		// Create a Map of the user's Scores by key
		Key<User> userKey = key(User.class,user.hashedId);
		List<Key<Score>> scoreKeys = ofy().load().type(Score.class).ancestor(userKey).keys().list();
		Map<Key<Score>, Score> scores = ofy().load().keys(scoreKeys);
		
		buf.append("<form method=post action='/launch'>"
				+ "<ul style='list-style: none;'>");
		for (APChemUnit u : units) {
			int userPctScore = 0;
			try { // userScore will be 0 if the Assignment or Score does not exist
				Assignment a = assignmentMap.get(u.id);
				userPctScore = scores.get(key(userKey,Score.class,a.id)).totalScore;
			} catch (Exception e) {}
			
			buf.append("<li><label>"
					+ "<input type=radio name=UnitId value=" + u.id + " onclick=unitClicked('" + u.id + "') />"
					+ "<b> Unit " + u.unitNumber + " - " + u.title + (userPctScore==0?"":" (" + userPctScore + "%)") + "</b></label>&nbsp;"
					+ "<input id=start" + u.id + " class='startButton btn btn-primary' style='display:none' type=submit value='" + (userPctScore==0?"Start":"Resume") + "' />"
					+ "</li>");
			buf.append("<ul style='list-style: none;'>");
			List<APChemTopic> topics = ofy().load().type(APChemTopic.class).filter("unitId",u.id).order("topicNumber").list();
			for(APChemTopic t : topics) {
				buf.append("<li class='topic list" + u.id + "' style='display:none'>" + t.title + "</li>");
			}
			buf.append("</ul><br/>"); // topic loop
		}
		buf.append("</ul>"
				+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
				+ "</form>");
		
		// Build a script to show/hide topics for the selected unit
		buf.append("<script>"
				+ "function unitClicked(unitId) {"
				+ "  var allListItems = document.querySelectorAll('li.topic');"
				+ "  for (var i=0; i<allListItems.length;i++) {"
				+ "    allListItems[i].style='display:none';"
				+ "  }"
				+ "  var unitListItems = document.querySelectorAll('li.list' + unitId);"
				+ "  for (var i=0;i<unitListItems.length;i++) {"
				+ "    unitListItems[i].style='display:list-item';"
				+ "  }"
				+ "  var startButtons = document.querySelectorAll('.startButton');"
				+ "  for (var i=0;i<startButtons.length;i++) {"
				+ "    startButtons[i].style='display:none';"
				+ "  }"
				+ "  document.getElementById('start' + unitId).style='display:inline';"
				+ "}"
				+ "</script>");
		
		if (!user.isPremium()) { // autoselects Unit0 for users on free trial
			Long unitZeroId = units.get(0).id;
			buf.append("<script>"
				+ "unitClicked(" + unitZeroId + ");"
				+ "document.querySelector('input[name=\"UnitId\"]').checked=true;"
				+ "</script>");
		}
		return buf.toString();
	}
}
