package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.key;
import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
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
 * Exercises servlet for managing student question delivery and adaptive scoring.
 * 
 * This servlet handles the complete exercise delivery lifecycle including:
 * - Student question requests and answer submissions with immediate feedback
 * - Adaptive question selection based on student performance history
 * - Instructor dashboard for managing assignments and student progress
 * - Topic selection and customization for assignments
 * - Score review and synchronization with LMS via LTI AGS (Assignment and Grade Services)
 * 
 * The assignment is formative (designed for learning, not grading) and adaptive (personalizes
 * questions based on student responses). Students can reattempt unlimited times to reach 100%,
 * with the correct answer revealed after each submission.
 * 
 * HTTP Routes:
 *   GET  /exercises                                    Default - get next question for current user (LTI Bearer token required)
 *   GET  /exercises?UserRequest=InstructorPage        Display instructor dashboard
 *   GET  /exercises?UserRequest=SelectTopics          Display topic selection/customization form
 *   GET  /exercises?UserRequest=ReviewScores          Display student scores with LMS roster
 *   POST /exercises?UserRequest=AssignTopics          Assign selected topics to assignment
 *   POST /exercises?UserRequest=SynchronizeScores     Sync student scores back to LMS
 *   POST /exercises (default)                          Process student answer submission and return feedback
 * 
 * Security: All routes except GET default require "sig" parameter (user session signature) or Bearer token.
 *           Instructor routes verify user.isInstructor() before allowing access.
 *           All LTI operations require proper platform deployment configuration.
 * 
 * @author ChemVantage
 * @version 2.0
 * @see User
 * @see Score
 * @see Assignment
 * @see Question
 * @see APChemTopic
 * @see Util
 * @see LTIMessage
 */
@WebServlet(urlPatterns={"/exercises"})
public class Exercises extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 1L;
	
	/**
	 * Handles HTTP GET requests for exercises.
	 * 
	 * Routes requests based on UserRequest parameter:
	 * - InstructorPage: Display instructor dashboard with assignment info
	 * - SelectTopics: Display topic selection/customization form
	 * - ReviewScores: Display student roster with LMS scores and CV scores
	 * - Default: Return next question in JSON format for student (requires Bearer token)
	 * 
	 * @param request The HTTP request containing UserRequest parameter and optional Bearer token
	 * @param response The HTTP response object
	 * @throws ServletException if a servlet error occurs
	 * @throws IOException if an I/O error occurs while writing response
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
			case "Explanation":
				long questionId = Long.parseLong(request.getParameter("qid"));
				String pParam = request.getParameter("p");
				long parameter = (pParam != null) ? Long.parseLong(pParam) : 0;
				Question question = ofy().load().type(Question.class).id(questionId).now();
				if (question == null) {
					out.println("<h2>Error: Question not found</h2>");
				} else {
					if (question.requiresParser()) question.setParameters(parameter);
					out.println(question.getExplanation());
				}
				break;
			default:
				response.setContentType("application/json; charset=UTF-8");
				String authHeader = request.getHeader("Authorization");
				if (authHeader == null || !authHeader.startsWith("Bearer ")) {
					throw new Exception("Unauthorized. You must launch this app from the link inside your LMS.");
				}
				String token = authHeader.substring("Bearer ".length());
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
	 * Handles HTTP POST requests for exercises.
	 * 
	 * Routes requests based on UserRequest parameter:
	 * - AssignTopics: Save topic selections to assignment (instructor only)
	 * - SynchronizeScores: Resubmit scores to LMS via AGS (instructor only)
	 * - Default: Process student answer submission and return feedback
	 * 
	 * @param request The HTTP request containing form data or JSON body
	 * @param response The HTTP response object
	 * @throws ServletException if a servlet error occurs
	 * @throws IOException if an I/O error occurs while reading request or writing response
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
	 * Assigns selected topics to an assignment based on instructor selection.
	 * Retrieves topic IDs from form submission, updates assignment, and displays updated instructor page.
	 * 
	 * Security: Verifies user is instructor before allowing topic changes.
	 * 
	 * @param request The HTTP request containing sig parameter and TopicId[] checkboxes
	 * @return HTML instructor page after topic assignment
	 * @throws Exception if user is not instructor, sig is missing, or assignment cannot be loaded
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
	 * Retrieves the next question for a student from their current score state.
	 * 
	 * If the question contains parameterized variables, generates a new random parameter value
	 * and parses the question text with that parameter. The parameter is included in the response
	 * so the client can verify it during answer checking.
	 * 
	 * The question is selected by the adaptive algorithm in Score.update() based on student performance.
	 * 
	 * @param user The student user object
	 * @return JsonObject containing question id, type, prompt (parsed if needed), units, choices (if multiple choice),
	 *         and parameter value (if parameterized). Returns null if question cannot be retrieved.
	 * @throws Exception if score cannot be retrieved or question cannot be loaded
	 * @see Score#getCurrentQuestion(long)
	 * @see Question#requiresParser()
	 * @see Question#setParameters(int)
	 * @see Question#parseString(String)
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
	 * Processes a student answer submission and generates feedback response.
	 * 
	 * Validates the Bearer token, extracts the answer from request JSON, checks correctness,
	 * updates the student's score and gets the next question, then builds HTML feedback
	 * showing whether the answer was correct and the current total score.
	 * 
	 * The response includes:
	 * - Updated authorization token (for session refresh)
	 * - HTML feedback with correct/incorrect message and student's total score
	 * - Link to finish assignment if student has achieved perfect score
	 * 
	 * @param request The HTTP request containing Bearer token in Authorization header
	 *        and JSON body with required fields: id (question ID), answer (student answer),
	 *        and optional parameter (for parameterized questions)
	 * @return JsonObject with "token" and "html" fields containing feedback
	 * @throws Exception if authorization fails, required fields are missing, or question cannot be loaded
	 */
	JsonObject getResponseJson(HttpServletRequest request) throws Exception {
		String authHeader = request.getHeader("Authorization");
		if (authHeader == null || !authHeader.startsWith("Bearer ")) {
			throw new Exception("Unauthorized. You must launch this app from the link inside your LMS.");
		}
		String token = authHeader.substring("Bearer ".length());
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
	 * Builds HTML feedback message for an answer submission.
	 * 
	 * Displays different feedback based on correctness:
	 * - If correct: Shows success message and current score
	 * - If incorrect: Shows failure message, correct answer, link to provide feedback/report issue, and current score
	 * 
	 * If student achieves perfect score (100%) on a standalone assignment (not LTI launch),
	 * a "Finish" button is shown to return to launch page.
	 * 
	 * @param correct Whether the answer was correct
	 * @param user The student user object (for signature and platform info)
	 * @param q The question that was answered (for correct answer retrieval)
	 * @param questionId The ID of the question answered (for feedback link)
	 * @param parameter The parameter value used (null if not parameterized) (for feedback link)
	 * @param studentAnswer The student's submitted answer (for feedback link encoding)
	 * @return HTML string ready for inclusion in response
	 * @throws Exception if feedback cannot be built (e.g., user not found)
	 */
	String buildFeedback(boolean correct, User user, Question q, Long questionId, Integer parameter, String studentAnswer) throws Exception {
		Score s = getScore(user);
		StringBuilder buf = new StringBuilder();
		
		if (correct) {
			buf.append("<h2>That's right! Your answer is correct.</h2>");
		} else {
			String feedbackUrl = "/feedback?sig=" + user.getTokenSignature() + "&questionId=" + questionId;
			if (parameter != null) {
				feedbackUrl += "&parameter=" + parameter;
			}
			feedbackUrl += "&studentAnswer=" + URLEncoder.encode(studentAnswer, "UTF-8");
			
			buf.append("<h2>Sorry, your answer is not correct " +
			   "<a href=" + feedbackUrl + " style='display: inline;' target=_blank>" +
			   "<img src=/images/feedback.png style='height:20px;vertical-align:8px;' alt='Report a problem' title='Report a problem' /></a></h2>" +
			   "The correct answer is: " + q.getCorrectAnswer() +
			   "<div id='explanation'>" +
			   "<button id='explainBtn' class='btn btn-secondary' onclick='window.fetchExplanation(" + questionId + ", " + parameter + ")'>Explain This</button>" +
			   "</div>");
		}
		
		buf.append("<br/>Your score on this assignment is " + s.totalScore + "%");
		if (s.totalScore == 100 && user.platformId.equals(Util.getServerUrl())) {
			buf.append("&nbsp;<a href='/launch?sig=" + user.getTokenSignature() + "'><button class='btn btn-primary'>Finish</button></a>");
		}
		
		return buf.toString();
	}

	/**
	 * Gets or creates the score object for a user's current assignment.
	 * 
	 * Retrieves the user's current assignment, then calls overloaded getScore(User, Assignment) method.
	 * 
	 * @param user The student user object
	 * @return The Score object for the user's current assignment (creates new if doesn't exist)
	 * @throws Exception if assignment cannot be loaded or score cannot be created
	 * @see #getScore(User, Assignment)
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
	 * Gets or creates the score object for a user on a specific assignment.
	 * 
	 * Retrieves the Score entity from datastore. If the Score exists but the assignment topics
	 * have changed, repairs the Score object to sync with the new topic list. If the Score
	 * doesn't exist, creates a new one and saves it to datastore.
	 * 
	 * This is necessary because instructors can add/remove topics from assignments, and existing
	 * scores need to be updated to include/exclude questions from the new topics.
	 * 
	 * @param user The student user object
	 * @param a The assignment object
	 * @return The Score object (creates new if doesn't exist)
	 * @throws Exception if score cannot be created or retrieved
	 * @see Score#repairMe(Assignment)
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
	 * 
	 * Loads the user's current assignment and displays information about the assignment,
	 * including a description of formative and adaptive features, list of covered topics,
	 * and buttons to select/modify topics or view the assignment.
	 * 
	 * @param request The HTTP request containing sig parameter
	 * @return HTML page content for instructor dashboard
	 * @throws Exception if user is not instructor, sig is missing, or assignment not found
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
	 * Generates the instructor dashboard page with assignment details.
	 * 
	 * Displays:
	 * - Assignment description and benefits (formative, adaptive)
	 * - List of AP Chemistry topics covered in the assignment
	 * - Link to add/remove topics from assignment
	 * - Link to review student scores with LMS roster
	 * - Button to launch the assignment and view as student would see it
	 * 
	 * @param user The instructor user object
	 * @param a The current assignment object
	 * @return HTML page content for instructor dashboard
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
		
		buf.append(Util.banner + "<h1>Exercises - Instructor Page</h1>" +
		   "<h2>Assignment Type</h2><p>This is an <strong>Assignment</strong> for your students. You select the topics covered and the students can click the topics on their copy to indicate comprehension.</p>" +
		   "<ul>" +
		   "<li><strong>Formative Assessment:</strong> Students receive immediate feedback on their answers and can revise, in a safe learning environment.</li>" +
		   "<li><strong>Adaptive:</strong> Students receive questions in a personalized sequence based on their mastery level.</li>" +
		   "</ul>" +
		   "<a href=/exercises?UserRequest=ReviewScores&sig=" + user.getTokenSignature() + ">View Student Scores</a><p>");

		buf.append("<h2>Topics Covered</h2>" +
		   "This assignment covers the following " +
		   "<a href=https://apcentral.collegeboard.org/courses/ap-chemistry target=_blank>" +
		   "AP Chemistry</a> topics:");
		
		Map<Long, APChemTopic> topics = ofy().load().type(APChemTopic.class).ids(a.topicIds);
		buf.append("<ul>");
		for (Long tId : a.topicIds) {
			APChemTopic topic = topics.get(tId);
			if (topic != null) {
				buf.append("<li>").append(topic.title).append("</li>");
			}
		}
		buf.append("</ul>" +
		   "You may " +
		   "<a href=/exercises?UserRequest=SelectTopics&sig=" + user.getTokenSignature() + ">" +
		   "select topics</a> to suit the current needs of your class.<br/><br/>");
		
		buf.append("<a class='btn btn-primary' href='/exercises/index.html?t=" + Util.getToken(user.getTokenSignature()) + "'>" +
		   "View Assignment</a><p>");
		
		return buf.toString() + Util.foot();
	}
	
	/**
	 * Generates the topic selection form for instructors to customize assignment topics.
	 * 
	 * Displays hierarchical checkboxes organized by AP Chemistry Unit and Topic, with JavaScript
	 * for smart checkbox management: selecting a unit checks all its topics; unchecking topics
	 * hides the unit and shows/hides topics based on selection state.
	 * 
	 * Form submission POSTs to /exercises?UserRequest=AssignTopics
	 * 
	 * @param request The HTTP request containing sig parameter
	 * @return HTML page with topic selection form and JavaScript
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
		buf.append(Util.banner + "<h1>Select Topics for Assignment</h1>");
		
		buf.append("<form method='post' action=/exercises>" +
		   "<input type='hidden' name='sig' value='" + user.getTokenSignature() + "' />" +
		   "<input type='hidden' name='UserRequest' value='AssignTopics' />" +
		   "<input type='submit' class='btn btn-primary' value='Assign Topics' /> " +
		   "<a class='btn btn-primary' href=/exercises?UserRequest=InstructorPage&sig=" + user.getTokenSignature() + ">Quit</a><br/><br/>" +
		   "<ul style='list-style: none;'>");
		
		List<APChemUnit> units = ofy().load().type(APChemUnit.class).order("unitNumber").list();
		for (APChemUnit u : units) {
			if (u == null) continue;
			buf.append("<li><label><input type='checkbox' class='unit-checkbox' id=" + u.id +
			   " value=" + u.id + " onclick=\"unitClicked('" + u.id + "');\"" +
			   " /> <b>Unit " + u.unitNumber + " - " + u.title + "</b></label></li>" +
			   "<ul style='list-style: none;'>");
			
			List<APChemTopic> topics = ofy().load().type(APChemTopic.class).filter("unitId", u.id).order("topicNumber").list();
			for (APChemTopic t : topics) {
				if (t == null) continue;
				buf.append("<li class='list-unit-" + u.id + "'>" +
				   "<label><input type='checkbox' class='unit-" + u.id +
				   " name='topicId' value=" + t.id + " ");
				if (a.topicIds.contains(t.id)) {
					buf.append("checked");
				}
				buf.append(" onclick=\"topicClicked('" + u.id + "')\"" +
				   " /> " + t.title + "</label>" +
				   "</li>");
			}
			buf.append("</ul>");
		}
		
		buf.append("</ul>" +
		   "<input type='submit' class='btn btn-primary' value='Assign Topics' /> " +
		   "<a class='btn btn-primary' href=/exercises?UserRequest=InstructorPage&sig=" + user.getTokenSignature() + ">Quit</a><br/>" +
		   "</form>");
		
		buf.append(buildTopicSelectionScript());
		return buf.toString() + Util.foot();
	}
	
	/**
	 * Builds JavaScript code for topic and unit checkbox interaction.
	 * 
	 * Manages:
	 * - Initial state: sets unit checkboxes checked/indeterminate based on selected topics
	 * - Topic visibility: hides topics if none are selected for a unit
	 * - Topic click: updates unit checkbox state (checked/indeterminate/unchecked)
	 * - Unit click: checks/unchecks all topics in unit and shows/hides topic list
	 * 
	 * @return JavaScript code as string ready to be embedded in HTML
	 */
	String buildTopicSelectionScript() {
		StringBuilder script = new StringBuilder();
		script.append("\n<script>\n" +
		"// Initialize topic selection form state\n" +
		"var unitBoxes = document.querySelectorAll('input.unit-checkbox');\n" +
		"var topicBoxes, checkAll, checkedCount;\n" +
		"for (var i=0;i<unitBoxes.length;i++) {\n" +
		"  topicBoxes = document.querySelectorAll('input.unit-' + unitBoxes[i].id);\n" +
		"  checkAll = document.getElementById(unitBoxes[i].id);\n" +
		"  checkedCount = document.querySelectorAll('input.unit-' + unitBoxes[i].id + ':checked').length;\n" +
		"  checkAll.checked = checkedCount>0;\n" +
		"  checkAll.indeterminate = checkedCount>0 && checkedCount<topicBoxes.length;\n" +
		"  var listItems = document.querySelectorAll('li.list-unit-' + unitBoxes[i].id);\n" +
		"  for (var j=0;j<listItems.length;j++) {\n" +
		"    listItems[j].style='display:' + (checkedCount>0?'list-item':'none');\n" +
		"  }\n" +
		"}\n" +
		"\n// Handle topic checkbox change\n" +
		"function topicClicked(unitId) {\n" +
		"  var topicCount = document.querySelectorAll('input.unit-' + unitId).length;\n" +
		"  var checkedCount = document.querySelectorAll('input.unit-' + unitId + ':checked').length;\n" +
		"  var checkAll = document.getElementById(unitId);\n" +
		"  checkAll.checked = checkedCount>0;\n" +
		"  checkAll.indeterminate = checkedCount>0 && checkedCount<topicCount;\n" +
		"  if (checkedCount==0) {\n" +
		"    var listItems = document.querySelectorAll('li.list-unit-' + unitId);\n" +
		"    for (var j=0;j<listItems.length;j++) {\n" +
		"      listItems[j].style='display:none';\n" +
		"    }\n" +
		"  }\n" +
		"}\n" +
		"\n// Handle unit checkbox change\n" +
		"function unitClicked(unitId) {\n" +
		"  var unitBox = document.getElementById(unitId);\n" +
		"  var listItems = document.querySelectorAll('li.list-unit-' + unitId);\n" +
		"  var topicBoxes = document.querySelectorAll('input.unit-' + unitId);\n" +
		"  for (var i=0;i<topicBoxes.length;i++) {\n" +
		"    topicBoxes[i].checked = unitBox.checked;\n" +
		"    if (i < listItems.length) {\n" +
		"      listItems[i].style='display:list-item';\n" +
		"    }\n" +
		"  }\n" +
		"}\n" +
		"</script>\n");
		return script.toString();
	}
	
	/**
	 * Generates a page showing student scores for the assignment, obtained from LMS roster.
	 * 
	 * Uses LTI Names and Roles Provisioning Service (NRPS) to get student roster from LMS,
	 * and LTI Assignment and Grade Services (AGS) to read score data. Displays a table with:
	 * - Row number, student name, email, role (from NRPS)
	 * - LMS score (from AGS or cached data)
	 * - ChemVantage score (from CV datastore)
	 * 
	 * If scores are not synchronized (students have CV scores not in LMS), displays button
	 * to launch background synchronization task.
	 * 
	 * @param request The HTTP request containing sig parameter
	 * @return HTML page with score table and optional sync button
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
		
		StringBuilder buf = new StringBuilder(Util.head("Review Student Scores"));
		buf.append(Util.banner + "<h1>Scores</h1>");
		
		buf.append("<h2>" + (a.title == null ? "" : a.title) + "</h2>");
		buf.append("Valid: " + new Date() + "<p>");
		
		try {
			if (a.lti_nrps_context_memberships_url == null) {
				throw new Exception("No Names and Roles Provisioning support.");
			}

			buf.append("<p><strong>About NRPS:</strong> This page uses the LTI Names and Roles Provisioning Service (NRPS) to retrieve your class roster from your Learning Management System.</p>" +
			   "<p>" +
			   "<a href=/exercises?UserRequest=InstructorPage&sig=" + user.getTokenSignature() +
			   ">Return to Instructor Page</a><br/><br/>");

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
			buf.append("<table border=1 align=center>");
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
buf.append("<tr><td>" + i + ".&nbsp;</td>" +
			   "<td>" + roleInfo[1] + "</td>" +
			   "<td>" + roleInfo[2] + "</td>" +
			   "<td>" + roleInfo[0] + "</td>" +
			   "<td align=center>" + (s == null ? "Score not available" : s + "%") + "</td>" +
			   "<td align=center>" + (cvScore == null ? "Score not available" : cvScore.maxScore + "%") + "</td>" +
			   "</tr>");
				
				synched = synched && 
						(!"Learner".equals(roleInfo[0]) || 
						(cvScore == null || Double.valueOf(cvScore.maxScore).equals(Double.parseDouble(s))));
			}
			buf.append("</table><br/>");
			if (!synched) {
				buf.append("<p><strong>Sync Scores:</strong> Your ChemVantage scores are not synchronized with your Learning Management System. Click the button below to send them to the LMS.</p>" +
				   "<br/>" +
				   "<form method='post' action=/exercises >" +
				   "<input type='hidden' name='sig' value='" + user.getTokenSignature() + "' />" +
				   "<input type='hidden' name='UserRequest' value='SynchronizeScores' />" +
				   "<input id=syncsubmit type='submit' class='btn btn-primary' value='Synchronize Scores' " +
				   "onclick=\"document.getElementById('syncsubmit').style='display:none'; return true;\" />" +
				   "</form>");
			}
			return buf.toString() + Util.foot();
		} catch (Exception e) {
			buf.append(e.toString());
			return buf.toString() + Util.foot();
		}
	}
	
	/**
	 * Synchronizes assignment scores between ChemVantage and the LMS.
	 * 
	 * For each student whose ChemVantage score differs from LMS score, creates a background task
	 * to resubmit the CV score to the LMS using LTI Assignment and Grade Services (AGS).
	 * Compares all student scores against LMS roster and only processes Learner (student) scores.
	 * 
	 * After task creation, displays updated instructor page.
	 * 
	 * @param request The HTTP request containing sig parameter
	 * @return HTML instructor page after synchronization task creation
	 * @throws Exception if user is not instructor, LMS is not configured for AGS/NRPS, or sig is missing
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
			Util.createTask("/report","a=" + a.id + "&u=" + URLEncoder.encode(platform_id + entry.getKey(),"UTF-8"));
		}
		return instructorPage(user,a);
	}
}

