package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.key;
import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlecode.objectify.Key;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Exercises servlet for managing student question delivery and scoring.
 * Supports student question requests, answer submission/scoring, and instructor management.
 * Routes:
 *   GET  /exercises: Default - get next question for current user (LTI)
 *   GET  /exercises?UserRequest=InstructorPage: Display instructor dashboard
 *   GET  /exercises?UserRequest=SelectTopics: Display topic selection form
 *   GET  /exercises?UserRequest=ReviewScores: Display student scores
 *   POST /exercises?UserRequest=AssignTopics: Assign topics to assignment
 *   POST /exercises?UserRequest=SynchronizeScores: Sync scores back to LMS
 */
@WebServlet(urlPatterns={"/exercises"})
public class Exercises extends HttpServlet {
	private static final long serialVersionUID = 1L;
	
	/**
	 * GET: Handles exercise requests from students (LTI) and instructor pages.
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {

		PrintWriter out = response.getWriter();

		JsonObject responseJson = new JsonObject();
		
		try {
			String userRequest = request.getParameter("UserRequest");
			if (userRequest == null) {
				userRequest = "";
			}
			
			switch (userRequest) {
			case "InstructorPage":
				response.setContentType("text/html; charset=UTF-8");
				out.println(instructorPage(request));
				break;
			case "SelectTopics":
				response.setContentType("text/html; charset=UTF-8");
				out.println(viewTopicSelectForm(request));
				break;
			case "ReviewScores":
				response.setContentType("text/html; charset=UTF-8");
				out.println(reviewScores(request));
				break;
			default:
				response.setContentType("application/json; charset=UTF-8");
				String authHeader = request.getHeader("Authorization");
				if (authHeader == null || !authHeader.startsWith("Bearer ")) {
					throw new Exception("Unauthorized. You must launch this app from the link inside your LMS.");
				}
				String token = authHeader.substring(7);
				String sig = Util.isValid(token);
				responseJson.addProperty("token", Util.getToken(sig));

				User user = User.getUser(sig);
				JsonObject q = getCurrentQuestion(user);
				if (q == null) throw new Exception("Unable to get a new question.");
				responseJson.add("question", q);
				out.println(responseJson.toString());
			}
		} catch (Exception e) {
			if (!response.getContentType().startsWith("application/json")) {
				response.setContentType("application/json; charset=UTF-8");
			}
			JsonObject question = new JsonObject();
			question.addProperty("type", "true_false");
			question.addProperty("id", "1");
			question.addProperty("prompt", e.getMessage());
			responseJson.add("question", question);
			out.println(responseJson.toString());
		}
	}
	
	/**
	 * POST: Handles student answer submissions and instructor management requests.
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {
		response.setContentType("application/json; charset=UTF-8");
		PrintWriter out = response.getWriter();
		try {
			String userRequest = request.getParameter("UserRequest");
			if (userRequest == null) {
				userRequest = "";
			}
			
			switch (userRequest) {
			case "AssignTopics":
				response.setContentType("text/html; charset=UTF-8");
				out.println(assignTopics(request));
				break;
			case "SynchronizeScores":
				response.setContentType("text/html; charset=UTF-8");
				out.println(synchronizeScores(request));
				break;
			default:
				out.println(getResponseJson(request).toString());
			}
		} catch (Exception e) {
			JsonObject err = new JsonObject();
			err.addProperty("error", e.getMessage());
			out.println(err);
		}
	}
	
	/**
	 * Assigns topics to an assignment from topic IDs in request.
	 * @param request The HTTP request containing sig and TopicId[] parameters
	 * @return HTML instructor page
	 * @throws Exception if user is not instructor or parameters are invalid
	 */
	String assignTopics(HttpServletRequest request) throws Exception {
		String sig = request.getParameter("sig");
		if (sig == null || sig.isEmpty()) {
			throw new IllegalArgumentException("Missing required parameter: sig");
		}
		User user = User.getUser(sig);
		if (!user.isInstructor()) {
			throw new Exception("Unauthorized: Only instructors can assign topics");
		}
		Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).safe();
		if (a == null) {
			throw new Exception("Assignment not found");
		}
		String[] topicIds = request.getParameterValues("TopicId");
		if (topicIds != null) {
			a.topicIds = new ArrayList<Long>();
			for (String tId : topicIds) {
				a.topicIds.add(Long.parseLong(tId));
			}
			ofy().save().entity(a).now();
		}
		return instructorPage(user, a);
	}

	/**
	 * Gets the next question for a student based on their current score.
	 * @param user The student user
	 * @return JsonObject representing the question, or null if no question available
	 * @throws Exception if score cannot be retrieved or question cannot be loaded
	 */
	JsonObject getCurrentQuestion(User user) throws Exception {
		if (user == null) {
			throw new IllegalArgumentException("User cannot be null");
		}
		Score s = getScore(user);
		Question q = ofy().load().type(Question.class).id(s.currentQuestionId).safe();
		if (q == null) {
			throw new Exception("Question not found: " + s.currentQuestionId);
		}
		JsonObject j = new JsonObject();
	
		String prompt = q.prompt;
		if (q.requiresParser()) {
			Integer parameter = new Random().nextInt();
			q.setParameters(parameter);
			j.addProperty("parameter", parameter);
			prompt = q.parseString(q.prompt);
		}
		j.addProperty("id", q.id);
		j.addProperty("type", q.type);
		j.addProperty("prompt", prompt);
	
		if (q.units != null) j.addProperty("units", q.units);
		if (q.choices != null) {
			j.addProperty("scrambled", q.scrambleChoices);
			JsonArray choices = new JsonArray();
			for (String c : q.choices) choices.add(c);
			j.add("choices", choices);
		}
		return j;
	}
	
	/**
	 * Processes student answer submission and returns feedback.
	 * @param request The HTTP request containing answer data in JSON body
	 * @return JsonObject with token and HTML feedback
	 * @throws Exception if authorization fails or answer data is invalid
	 */
	JsonObject getResponseJson(HttpServletRequest request) throws Exception {
		String authHeader = request.getHeader("Authorization");
		if (authHeader == null || !authHeader.startsWith("Bearer ")) {
			throw new Exception("Unauthorized. You must launch this app from the link inside your LMS.");
		}
		String token = authHeader.substring(7);
		String sig = Util.isValid(token);
		
		JsonObject requestJson = null;
		try (BufferedReader reader = request.getReader()) {
			requestJson = JsonParser.parseReader(reader).getAsJsonObject();
		}
		
		if (!requestJson.has("id") || requestJson.get("id").isJsonNull()) {
			throw new IllegalArgumentException("Missing required field: id");
		}
		if (!requestJson.has("answer") || requestJson.get("answer").isJsonNull()) {
			throw new IllegalArgumentException("Missing required field: answer");
		}
		
		Long questionId = requestJson.get("id").getAsLong();
		String studentAnswer = requestJson.get("answer").getAsString().trim();
		
		Question q = ofy().load().type(Question.class).id(questionId).safe();
		if (q == null) {
			throw new Exception("Question not found: " + questionId);
		}
		Integer parameter = null;
		if (q.requiresParser()) {
			if (!requestJson.has("parameter") || requestJson.get("parameter").isJsonNull()) {
				throw new IllegalArgumentException("Missing required field: parameter");
			}
			parameter = requestJson.get("parameter").getAsInt();
			q.setParameters(parameter);
		}
		
		User user = User.getUser(sig);
		if (user == null) {
			throw new Exception("User not found");
		}
		Score s = getScore(user);
		boolean correct = q.isCorrect(studentAnswer);
		s.update(user, q, correct ? 1 : 0);
		
		String feedbackHtml = buildFeedback(correct, user, q, questionId, parameter, studentAnswer);
		
		JsonObject responseJson = new JsonObject();
		responseJson.addProperty("token", Util.getToken(sig));
		responseJson.addProperty("html", feedbackHtml);
		return responseJson;
	}
	
	/**
	 * Builds HTML feedback for an answer submission.
	 * @param correct Whether the answer was correct
	 * @param user The student user
	 * @param q The question answered
	 * @param questionId The question ID
	 * @param parameter The parameter used (if applicable)
	 * @param studentAnswer The student's answer
	 * @return HTML feedback string
	 */
	String buildFeedback(boolean correct, User user, Question q, Long questionId, Integer parameter, String studentAnswer) throws Exception {
		Score s = getScore(user);
		StringBuilder buf = new StringBuilder();
		
		if (correct) {
			buf.append("<h2>That's right! Your answer is correct.</h2>");
		} else {
			buf.append("<h2>Sorry, your answer is not correct ");
			buf.append("<a href=/feedback?sig=").append(user.getTokenSignature())
			   .append("&questionId=").append(questionId);
			if (parameter != null) {
				buf.append("&parameter=").append(parameter);
			}
			buf.append("&studentAnswer=").append(URLEncoder.encode(studentAnswer, "UTF-8"))
			   .append(" style='display: inline;' target=_blank>")
			   .append("<img src=/images/feedback.png style='height:20px;vertical-align:8px;' alt='Report a problem' title='Report a problem' /></a></h2>")
			   .append("The correct answer is: ").append(q.getCorrectAnswer());
		}
		
		buf.append("<br/>Your score on this assignment is ").append(s.totalScore).append("%");
		if (s.totalScore == 100 && user.platformId.equals(Util.getServerUrl())) {
			buf.append("&nbsp;<a href='/launch?sig=").append(user.getTokenSignature())
			   .append("'><button class='btn btn-primary'>Finish</button></a>");
		}
		
		return buf.toString();
	}

	/**
	 * Gets the score object for a user's current assignment.
	 * Creates a new score if none exists.
	 * @param user The student user
	 * @return The Score object
	 * @throws Exception if assignment cannot be loaded
	 */
	Score getScore(User user) throws Exception {
		if (user == null) {
			throw new IllegalArgumentException("User cannot be null");
		}
		Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
		if (a == null) {
			throw new Exception("Assignment not found");
		}
		return getScore(user, a);
	}
	
	/**
	 * Gets the score object for a user and specific assignment.
	 * Creates a new score if none exists; repairs if topics have changed.
	 * @param user The student user
	 * @param a The assignment
	 * @return The Score object
	 * @throws Exception if score cannot be created
	 */
	Score getScore(User user, Assignment a) throws Exception {
		if (user == null) {
			throw new IllegalArgumentException("User cannot be null");
		}
		if (a == null) {
			throw new IllegalArgumentException("Assignment cannot be null");
		}
		Score s = null;
		Key<User> userKey = key(User.class, user.hashedId);
		Key<Score> scoreKey = key(userKey, Score.class, a.id);
		try {
			s = ofy().load().key(scoreKey).safe();
			if (s != null && !s.topicIds.equals(a.topicIds)) {
				s.repairMe(a);
			}
		} catch (Exception e) {
			s = new Score(user.hashedId, a);
			ofy().save().entity(s).now();
		}
		return s;
	}
	
	/**
	 * Generates the instructor dashboard page.
	 * @param request The HTTP request containing sig parameter
	 * @return HTML page content
	 * @throws Exception if user is not instructor or sig is missing
	 */
	String instructorPage(HttpServletRequest request) throws Exception {
		String sig = request.getParameter("sig");
		if (sig == null || sig.isEmpty()) {
			throw new IllegalArgumentException("Missing required parameter: sig");
		}
		User user = User.getUser(sig);
		if (user == null) {
			throw new Exception("User not found");
		}
		Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).safe();
		if (a == null) {
			throw new Exception("Assignment not found");
		}
		return instructorPage(user, a);
	}
	
	/**
	 * Generates the instructor dashboard page.
	 * @param user The instructor user
	 * @param a The current assignment
	 * @return HTML page content
	 * @throws Exception if user is not instructor
	 */
	static String instructorPage(User user, Assignment a) throws Exception {
		if (user == null) {
			throw new IllegalArgumentException("User cannot be null");
		}
		if (a == null) {
			throw new IllegalArgumentException("Assignment cannot be null");
		}
		if (!user.isInstructor()) {
			throw new Exception("Unauthorized: Only instructors can access this page");
		}
		
		StringBuilder buf = new StringBuilder(Util.head("Instructor Page"));
		
		buf.append(Util.banner).append("<h1>Exercises - Instructor Page</h1>")
		   .append("This is a formative, adaptive assignment that will help your students ")
		   .append("prepare for the AP Chemistry Exam. It is a series of questions and numeric ")
		   .append("problems drawn from the topics below that gradually increase in difficulty.")
		   .append("<ul>")
		   .append("<li>Formative - students may work as many problems as necessary to ")
		   .append("achieve a score of 100%. The correct answer is provided after each ")
		   .append("submission, allowing students to learn as they work.</li>")
		   .append("<li>Adaptive - the assignment provides a personalized learning experience ")
		   .append("by tailoring the questions to each student's needs based on their prior ")
		   .append("responses.</li>")
		   .append("</ul>")
		   .append("<a href=/exercises?UserRequest=ReviewScores&sig=").append(user.getTokenSignature())
		   .append(">Review your students' scores on this assignment</a><p>");

		buf.append("<h2>Topics Covered</h2>")
		   .append("This assignment covers the following ")
		   .append("<a href=https://apcentral.collegeboard.org/courses/ap-chemistry target=_blank>")
		   .append("AP Chemistry</a> topics:");
		
		Map<Long, APChemTopic> topics = ofy().load().type(APChemTopic.class).ids(a.topicIds);
		buf.append("<ul>");
		for (Long tId : a.topicIds) {
			APChemTopic topic = topics.get(tId);
			if (topic != null) {
				buf.append("<li>").append(topic.title).append("</li>");
			}
		}
		buf.append("</ul>")
		   .append("You may ")
		   .append("<a href=/exercises?UserRequest=SelectTopics&sig=").append(user.getTokenSignature()).append(">")
		   .append("add or delete topics</a> to suit the current needs of your class.<br/><br/>");
		
		buf.append("<a class='btn btn-primary' href='/exercises/index.html?t=").append(Util.getToken(user.getTokenSignature())).append("'>")
		   .append("View This Assignment</a><p>");
		
		return buf.toString() + Util.foot();
	}
	
	/**
	 * Generates the topic selection form for instructors to customize assignment topics.
	 * @param request The HTTP request containing sig parameter
	 * @return HTML page with topic selection form
	 * @throws Exception if sig is missing or user is not instructor
	 */
	String viewTopicSelectForm(HttpServletRequest request) throws Exception {
		String sig = request.getParameter("sig");
		if (sig == null || sig.isEmpty()) {
			throw new IllegalArgumentException("Missing required parameter: sig");
		}
		User user = User.getUser(sig);
		if (user == null) {
			throw new Exception("User not found");
		}
		if (!user.isInstructor()) {
			throw new Exception("Unauthorized: Only instructors can select topics");
		}
		
		Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).safe();
		if (a == null) {
			throw new Exception("Assignment not found");
		}
		
		StringBuilder buf = new StringBuilder(Util.head("Select Topics"));
		buf.append(Util.banner).append("<h1>Select Topics for This Assignment</h1>");
		
		buf.append("<form method=post action=/exercises>")
		   .append("<input type=hidden name=sig value=").append(user.getTokenSignature()).append(" />")
		   .append("<input type=hidden name=UserRequest value=AssignTopics />")
		   .append("<input type=submit class='btn btn-primary' value='Click here to assign the topics selected below.' /> ")
		   .append("<a class='btn btn-primary' href=/exercises?UserRequest=InstructorPage&sig=").append(user.getTokenSignature()).append(">Quit</a><br/><br/>")
		   .append("<ul style='list-style: none;'>");
		
		List<APChemUnit> units = ofy().load().type(APChemUnit.class).order("unitNumber").list();
		for (APChemUnit u : units) {
			if (u == null) continue;
			buf.append("<li><label><input type=checkbox class=unitCheckbox id=").append(u.id)
			   .append(" value=").append(u.id).append(" onclick=unitClicked('").append(u.id).append("');")
			   .append(" /> <b>Unit ").append(u.unitNumber).append(" - ").append(u.title).append("</b></label></li>")
			   .append("<ul style='list-style: none;'>");
			
			List<APChemTopic> topics = ofy().load().type(APChemTopic.class).filter("unitId", u.id).order("topicNumber").list();
			for (APChemTopic t : topics) {
				if (t == null) continue;
				buf.append("<li class='list").append(u.id).append("'>")
				   .append("<label><input type=checkbox class=unit").append(u.id)
				   .append(" name=TopicId value=").append(t.id).append(" ");
				if (a.topicIds.contains(t.id)) {
					buf.append("checked");
				}
				buf.append(" onclick=topicClicked('").append(u.id).append("')")
				   .append(" /> ").append(t.title).append("</label>")
				   .append("</li>");
			}
			buf.append("</ul>");
		}
		
		buf.append("</ul>")
		   .append("<input type=submit class='btn btn-primary' value='Click here to assign the topics selected above.' /> ")
		   .append("<a class='btn btn-primary' href=/exercises?UserRequest=InstructorPage&sig=").append(user.getTokenSignature()).append(">Quit</a><br/>")
		   .append("</form>");
		
		buf.append(buildTopicSelectionScript());
		return buf.toString() + Util.foot();
	}
	
	/**
	 * Builds the JavaScript for topic/unit selection form interaction.
	 * Manages indeterminate checkbox states and topic visibility.
	 * @return JavaScript code string
	 */
	String buildTopicSelectionScript() {
		StringBuilder script = new StringBuilder();
		script.append("\n<script>\n");
		script.append("// Initialize topic selection form state\n");
		script.append("var unitBoxes = document.querySelectorAll('input.unitCheckbox');\n");
		script.append("var topicBoxes, checkAll, checkedCount;\n");
		script.append("for (var i=0;i<unitBoxes.length;i++) {\n");
		script.append("  topicBoxes = document.querySelectorAll('input.unit' + unitBoxes[i].id);\n");
		script.append("  checkAll = document.getElementById(unitBoxes[i].id);\n");
		script.append("  checkedCount = document.querySelectorAll('input.unit' + unitBoxes[i].id + ':checked').length;\n");
		script.append("  checkAll.checked = checkedCount>0;\n");
		script.append("  checkAll.indeterminate = checkedCount>0 && checkedCount<topicBoxes.length;\n");
		script.append("  var listItems = document.querySelectorAll('li.list' + unitBoxes[i].id);\n");
		script.append("  for (var j=0;j<listItems.length;j++) {\n");
		script.append("    listItems[j].style='display:' + (checkedCount>0?'list-item':'none');\n");
		script.append("  }\n");
		script.append("}\n");
		script.append("\n// Handle topic checkbox change\n");
		script.append("function topicClicked(unitId) {\n");
		script.append("  var topicCount = document.querySelectorAll('input.unit' + unitId).length;\n");
		script.append("  var checkedCount = document.querySelectorAll('input.unit' + unitId + ':checked').length;\n");
		script.append("  var checkAll = document.getElementById(unitId);\n");
		script.append("  checkAll.checked = checkedCount>0;\n");
		script.append("  checkAll.indeterminate = checkedCount>0 && checkedCount<topicCount;\n");
		script.append("  if (checkedCount==0) {\n");
		script.append("    var listItems = document.querySelectorAll('li.list' + unitId);\n");
		script.append("    for (var j=0;j<listItems.length;j++) {\n");
		script.append("      listItems[j].style='display:none';\n");
		script.append("    }\n");
		script.append("  }\n");
		script.append("}\n");
		script.append("\n// Handle unit checkbox change\n");
		script.append("function unitClicked(unitId) {\n");
		script.append("  var unitBox = document.getElementById(unitId);\n");
		script.append("  var listItems = document.querySelectorAll('li.list' + unitId);\n");
		script.append("  var topicBoxes = document.querySelectorAll('input.unit' + unitId);\n");
		script.append("  for (var i=0;i<topicBoxes.length;i++) {\n");
		script.append("    topicBoxes[i].checked = unitBox.checked;\n");
		script.append("    if (i < listItems.length) {\n");
		script.append("      listItems[i].style='display:list-item';\n");
		script.append("    }\n");
		script.append("  }\n");
		script.append("}\n");
		script.append("</script>\n");
		return script.toString();
	}
	
	/**
	 * Generates a page showing student scores for the assignment.
	 * Uses LTI Names and Roles Provisioning Service to get student roster.
	 * @param request The HTTP request containing sig parameter
	 * @return HTML page with score table
	 * @throws Exception if sig is missing or user is not instructor
	 */
	String reviewScores(HttpServletRequest request) throws Exception {
		String sig = request.getParameter("sig");
		if (sig == null || sig.isEmpty()) {
			throw new IllegalArgumentException("Missing required parameter: sig");
		}
		User user = User.getUser(sig);
		if (user == null) {
			throw new Exception("User not found");
		}
		if (!user.isInstructor()) {
			throw new Exception("Unauthorized: Only instructors can review scores");
		}
		
		Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).safe();
		if (a == null) {
			throw new Exception("Assignment not found");
		}
		
		StringBuilder buf = new StringBuilder(Util.head("Review Scores"));
		buf.append(Util.banner).append("<h1>Scores for This Assignment</h1>");
		
		buf.append("<h2>").append(a.title == null ? "" : a.title).append("</h2>");
		buf.append("Valid: ").append(new Date()).append("<p>");
		
		try {
			if (a.lti_nrps_context_memberships_url == null) {
				throw new Exception("No Names and Roles Provisioning support.");
			}

			buf.append("The roster below is obtained using the Names and Role Provisioning service offered by your learning management system, ")
			   .append("and may or may not include user's names or emails, depending on the settings of your LMS.<p>")
			   .append("<a href=/exercises?UserRequest=InstructorPage&sig=").append(user.getTokenSignature())
			   .append(">Return to the instructor page</a><br/><br/>");

			Map<String, String> scores = LTIMessage.readMembershipScores(a);
			if (scores == null) {
				scores = new HashMap<>();
			}

			Map<String, String[]> membership = LTIMessage.getMembership(a);
			if (membership == null) {
				membership = new HashMap<>();
			}

			Map<String, Key<Score>> keys = new HashMap<>();
			Deployment d = ofy().load().type(Deployment.class).id(a.platform_deployment_id).safe();
			if (d == null) {
				throw new Exception("Deployment not found");
			}
			String platformId = d.getPlatformId();
			if (platformId == null) {
				throw new Exception("Cannot determine platform ID");
			}
			String platform_id = platformId + "/";
			for (String id : membership.keySet()) {
				keys.put(id, key(key(User.class, Util.hashId(platform_id + id)), Score.class, a.id));
			}
			Map<Key<Score>, Score> cvScores = ofy().load().keys(keys.values());
			buf.append("<table><tr><th>&nbsp;</th><th>Name</th><th>Email</th><th>Role</th><th>LMS Score</th><th>CV Score</th></tr>");
			int i = 0;
			boolean synched = true;
			for (Map.Entry<String, String[]> entry : membership.entrySet()) {
				if (entry == null) {
					continue;
				}
				String s = scores.get(entry.getKey());
				Score cvScore = cvScores.get(keys.get(entry.getKey()));
				String[] roleInfo = entry.getValue();
				if (roleInfo == null || roleInfo.length < 3) {
					continue;
				}
				i++;
				buf.append("<tr><td>").append(i).append(".&nbsp;</td>")
				   .append("<td>").append(roleInfo[1]).append("</td>")
				   .append("<td>").append(roleInfo[2]).append("</td>")
				   .append("<td>").append(roleInfo[0]).append("</td>")
				   .append("<td align=center>").append(s == null ? " - " : s + "%").append("</td>")
				   .append("<td align=center>").append(cvScore == null ? " - " : cvScore.maxScore + "%").append("</td>")
				   .append("</tr>");
				
				synched = synched && 
						(!"Learner".equals(roleInfo[0]) || 
						(cvScore == null || Double.valueOf(cvScore.maxScore).equals(Double.parseDouble(s))));
			}
			buf.append("</table><br/>");
			if (!synched) {
				buf.append("If any of the Learner scores above are not synchronized, you may use the button below to launch a background task ")
				   .append("where ChemVantage will resubmit them to your LMS. This can take several seconds to minutes depending on the ")
				   .append("number of scores to process. Please note that you may have to adjust the settings in your LMS to accept the ")
				   .append("revised scores. For example, in Canvas you may need to change the assignment settings to Unlimited Submissions. ")
				   .append("This may also cause the submission to be counted as late if the LMS assignment deadline has passed.<br/>")
				   .append("<form method=post action=/exercises >")
				   .append("<input type=hidden name=sig value=").append(user.getTokenSignature()).append(" />")
				   .append("<input type=hidden name=UserRequest value='SynchronizeScores' />")
				   .append("<input id=syncsubmit type=submit class='btn btn-primary' value='Synchronize Scores' ")
				   .append("onclick=document.getElementById('syncsubmit').style='display:none'; />")
				   .append("</form>");
			}
			return buf.toString() + Util.foot();
		} catch (Exception e) {
			buf.append(e.toString());
			return buf.toString() + Util.foot();
		}
	}
	
	/**
	 * Synchronizes assignment scores between ChemVantage and the LMS.\n\t * Resubmits scores that differ from LMS scores using a background task.
	 * @param request The HTTP request containing sig parameter
	 * @return HTML instructor page after synchronization
	 * @throws Exception if user is not instructor or LMS is not configured
	 */
	String synchronizeScores(HttpServletRequest request) throws Exception {
		String sig = request.getParameter("sig");
		if (sig == null || sig.isEmpty()) {
			throw new IllegalArgumentException("Missing required parameter: sig");
		}
		User user = User.getUser(sig);
		if (user == null) {
			throw new Exception("User not found");
		}
		Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).safe();
		if (a == null) {
			throw new Exception("Assignment not found");
		}

		if (!user.isInstructor()) {
			throw new Exception("Unauthorized: Only instructors can synchronize scores");
		}
		if (a.lti_ags_lineitem_url == null || a.lti_nrps_context_memberships_url == null) {
			throw new Exception("Error: Your LMS is not configured for this operation.");
		}

		Map<String,String> scores = LTIMessage.readMembershipScores(a);
		if (scores==null || scores.size()==0) throw new Exception();  // this only works if we can get info from the LMS
		Map<String,String[]> membership = LTIMessage.getMembership(a);
		if (membership==null || membership.size()==0) throw new Exception();  // there must be some members of this class
		Map<String,Key<Score>> keys = new HashMap<String,Key<Score>>();
		Deployment d = ofy().load().type(Deployment.class).id(a.platform_deployment_id).safe();
		String platform_id = d.getPlatformId() + "/";
		for (String id : membership.keySet()) {
			String hashedUserId = Util.hashId(platform_id + id);
			keys.put(id,key(key(User.class,hashedUserId),Score.class,a.id));
		}
		Map<Key<Score>,Score> cvScores = ofy().load().keys(keys.values());
		for (Map.Entry<String,String[]> entry : membership.entrySet()) {
			if (entry == null) continue;
			Score cvScore = cvScores.get(keys.get(entry.getKey()));
			if (cvScore==null) continue;
			String s = scores.get(entry.getKey());
			if (String.valueOf(cvScore.maxScore).equals(s)) continue;  // the scores match (good!)
			Util.createTask("/report","AssignmentId=" + a.id + "&UserId=" + URLEncoder.encode(platform_id + entry.getKey(),"UTF-8"));
		}
		return instructorPage(user,a);
	}
}

