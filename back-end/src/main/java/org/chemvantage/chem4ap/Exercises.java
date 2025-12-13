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
	private static final long serialVersionUID = 1L;
	
	// UserRequest parameter constants for routing
	/** Request parameter value for instructor dashboard display */
	private static final String USER_REQUEST_INSTRUCTOR_PAGE = "InstructorPage";
	/** Request parameter value for topic selection form */
	private static final String USER_REQUEST_SELECT_TOPICS = "SelectTopics";
	/** Request parameter value for score review page */
	private static final String USER_REQUEST_REVIEW_SCORES = "ReviewScores";
	/** Request parameter value for assigning topics to assignment */
	private static final String USER_REQUEST_ASSIGN_TOPICS = "AssignTopics";
	/** Request parameter value for synchronizing scores to LMS */
	private static final String USER_REQUEST_SYNCHRONIZE_SCORES = "SynchronizeScores";
	
	// HTTP header and content type constants
	/** HTTP Authorization header name for Bearer token authentication */
	private static final String AUTHORIZATION_HEADER = "Authorization";
	/** Bearer token prefix in Authorization header */
	private static final String BEARER_PREFIX = "Bearer ";
	/** HTML content type with UTF-8 encoding */
	private static final String CONTENT_TYPE_HTML = "text/html; charset=UTF-8";
	/** JSON content type with UTF-8 encoding */
	private static final String CONTENT_TYPE_JSON = "application/json; charset=UTF-8";
	/** Authorization error message for LTI students */
	private static final String ERROR_UNAUTHORIZED = "Unauthorized. You must launch this app from the link inside your LMS.";
	
	// Request parameter and field names
	/** Request parameter name for user action/request type */
	private static final String PARAM_USER_REQUEST = "UserRequest";
	/** Request parameter name for topic IDs (array) */
	private static final String PARAM_TOPIC_ID = "TopicId";
	/** Request parameter name for user session signature */
	private static final String PARAM_SIG = "sig";
	/** Request parameter name for question ID */
	private static final String PARAM_ID = "id";
	/** Request parameter name for student answer */
	private static final String PARAM_ANSWER = "answer";
	/** Request parameter name for parameterized question parameter value */
	private static final String PARAM_PARAMETER = "parameter";
	
	// JSON response field names
	/** JSON field name for authorization token in response */
	private static final String JSON_TOKEN = "token";
	/** JSON field name for question object in response */
	private static final String JSON_QUESTION = "question";
	/** JSON field name for feedback HTML in response */
	private static final String JSON_HTML = "html";
	/** JSON field name for error message */
	private static final String JSON_ERROR = "error";
	/** JSON field name for question ID */
	private static final String JSON_ID = "id";
	/** JSON field name for question type */
	private static final String JSON_TYPE = "type";
	/** JSON field name for question prompt/text */
	private static final String JSON_PROMPT = "prompt";
	/** JSON field name for units of measurement */
	private static final String JSON_UNITS = "units";
	/** JSON field name for answer choices array */
	private static final String JSON_CHOICES = "choices";
	/** JSON field name for scrambled/randomized choices flag */
	private static final String JSON_SCRAMBLED = "scrambled";
	/** JSON field name for parameterized question parameter value */
	private static final String JSON_PARAMETER = "parameter";
	
	// Question type for error responses
	/** Question type for error messages displayed as true/false question */
	private static final String ERROR_QUESTION_TYPE = "true_false";
	/** Placeholder question ID for error responses */
	private static final String ERROR_QUESTION_ID = "1";
	
	// HTML and UI constants
	/** CSS class for unit selection checkbox */
	private static final String CSS_CLASS_UNIT_CHECKBOX = "unitCheckbox";
	/** CSS class prefix for topic checkboxes under a unit */
	private static final String CSS_CLASS_UNIT_PREFIX = "unit";
	/** CSS class prefix for list items in topic selection */
	private static final String CSS_CLASS_LIST_PREFIX = "list";
	/** HTML button class for primary action buttons */
	private static final String CSS_CLASS_BTN_PRIMARY = "btn btn-primary";
	/** Default display style for visible elements */
	private static final String CSS_DISPLAY_LIST_ITEM = "display:list-item";
	/** CSS display style for hidden elements */
	private static final String CSS_DISPLAY_NONE = "display:none";
	
	// Feedback message constants
	/** Feedback message for correct answers */
	private static final String FEEDBACK_CORRECT = "<h2>That's right! Your answer is correct.</h2>";
	/** Feedback message prefix for incorrect answers */
	private static final String FEEDBACK_INCORRECT_PREFIX = "<h2>Sorry, your answer is not correct ";
	/** Feedback message for correct answer */
	private static final String FEEDBACK_CORRECT_ANSWER = "The correct answer is: ";
	/** URL path for feedback form (incorrect answer report) */
	private static final String PATH_FEEDBACK = "/feedback";
	/** URL path for image resources */
	private static final String PATH_IMAGES = "/images";
	/** Filename for feedback icon image */
	private static final String IMAGE_FEEDBACK = "feedback.png";
	/** Alt text for feedback report button */
	private static final String IMAGE_ALT_FEEDBACK = "Report a problem";
	/** Title text for feedback report button */
	private static final String IMAGE_TITLE_FEEDBACK = "Report a problem";
	/** Image height in CSS pixels */
	private static final String IMAGE_HEIGHT = "20px";
	/** Image vertical alignment in CSS */
	private static final String IMAGE_VERTICAL_ALIGN = "8px";
	
	// Score and completion constants
	/** Score message prefix */
	private static final String MESSAGE_SCORE_PREFIX = "<br/>Your score on this assignment is ";
	/** Score message suffix (percentage) */
	private static final String MESSAGE_SCORE_SUFFIX = "%";
	/** Perfect score value for completion */
	private static final int PERFECT_SCORE = 100;
	/** URL path for assignment completion/finishing */
	private static final String PATH_LAUNCH = "/launch";
	/** Finish button text */
	private static final String BUTTON_FINISH = "Finish";
	
	// HTML form and table constants
	/** HTML form method attribute for POST requests */
	private static final String FORM_METHOD_POST = "post";
	/** HTML form method attribute for GET requests */
	private static final String FORM_METHOD_GET = "get";
	/** HTML input type for hidden fields */
	private static final String INPUT_TYPE_HIDDEN = "hidden";
	/** HTML input type for checkbox */
	private static final String INPUT_TYPE_CHECKBOX = "checkbox";
	/** HTML input type for submit button */
	private static final String INPUT_TYPE_SUBMIT = "submit";
	/** HTML table header row tag open */
	private static final String TABLE_HEADER_OPEN = "<table><tr><th>&nbsp;</th><th>Name</th><th>Email</th><th>Role</th><th>LMS Score</th><th>CV Score</th></tr>";
	/** HTML table align attribute for center alignment */
	private static final String TABLE_ALIGN_CENTER = "align=center";
	
	// LTI and score synchronization constants
	/** Learner role in LTI roster (student) */
	private static final String LTI_ROLE_LEARNER = "Learner";
	/** HTTP encoding for URL parameters */
	private static final String URL_ENCODING = "UTF-8";
	/** Path for background task creation (score reporting) */
	private static final String PATH_REPORT = "/report";
	/** Task parameter name for assignment ID */
	private static final String TASK_PARAM_ASSIGNMENT_ID = "AssignmentId=";
	/** Task parameter name for user ID */
	private static final String TASK_PARAM_USER_ID = "&UserId=";
	/** Default score display when not available */
	private static final String SCORE_NOT_AVAILABLE = " - ";
	/** Score format suffix (percentage) */
	private static final String SCORE_FORMAT_PERCENT = "%";
	
	// JavaScript and interactive elements constants
	/** JavaScript function name for topic checkbox click handler */
	private static final String JS_FUNC_TOPIC_CLICKED = "topicClicked";
	/** JavaScript function name for unit checkbox click handler */
	private static final String JS_FUNC_UNIT_CLICKED = "unitClicked";
	/** HTML onclick handler for topic checkbox */
	private static final String ONCLICK_TOPIC = "topicClicked";
	/** HTML onclick handler for unit checkbox */
	private static final String ONCLICK_UNIT = "unitClicked";
	/** HTML onclick attribute value for synchronize button (hide on click) */
	private static final String ONCLICK_SYNC_HIDE = "document.getElementById('syncsubmit').style='display:none';";
	
	// Button and link text constants
	/** Button text for assigning selected topics */
	private static final String BTN_ASSIGN_TOPICS = "Click here to assign the topics selected below.";
	/** Button text for synchronizing scores */
	private static final String BTN_SYNCHRONIZE = "Synchronize Scores";
	/** Button text for quit/return */
	private static final String BTN_QUIT = "Quit";
	/** Link text for reviewing scores */
	private static final String LINK_REVIEW_SCORES = "Review your students' scores on this assignment";
	/** Link text for selecting topics */
	private static final String LINK_SELECT_TOPICS = "add or delete topics";
	/** Link text for viewing assignment */
	private static final String LINK_VIEW_ASSIGNMENT = "View This Assignment";
	/** Link text for returning to instructor page */
	private static final String LINK_RETURN_TO_INSTRUCTOR = "Return to the instructor page";
	
	// Page title and heading constants
	/** Page title for instructor page */
	private static final String TITLE_INSTRUCTOR_PAGE = "Instructor Page";
	/** Page title for topic selection */
	private static final String TITLE_SELECT_TOPICS = "Select Topics";
	/** Page title for score review */
	private static final String TITLE_REVIEW_SCORES = "Review Scores";
	/** Page heading for scores */
	private static final String HEADING_SCORES = "<h1>Scores for This Assignment</h1>";
	/** Page heading for exercises on instructor page */
	private static final String HEADING_EXERCISES = "<h1>Exercises - Instructor Page</h1>";
	/** Page heading for topic selection */
	private static final String HEADING_SELECT_TOPICS = "<h1>Select Topics for This Assignment</h1>";
	
	// Assignment description and help text
	/** Description of formative assignment feature */
	private static final String DESC_FORMATIVE = "<li>Formative - students may work as many problems as necessary to achieve a score of 100%. The correct answer is provided after each submission, allowing students to learn as they work.</li>";
	/** Description of adaptive assignment feature */
	private static final String DESC_ADAPTIVE = "<li>Adaptive - the assignment provides a personalized learning experience by tailoring the questions to each student's needs based on their prior responses.</li>";
	/** Description of assignment purpose */
	private static final String DESC_ASSIGNMENT = "This is a formative, adaptive assignment that will help your students prepare for the AP Chemistry Exam. It is a series of questions and numeric problems drawn from the topics below that gradually increase in difficulty.";
	/** Help text for Names and Roles Provisioning Service */
	private static final String HELP_NRPS = "The roster below is obtained using the Names and Role Provisioning service offered by your learning management system, and may or may not include user's names or emails, depending on the settings of your LMS.";
	/** Help text for score synchronization */
	private static final String HELP_SYNC_SCORES = "If any of the Learner scores above are not synchronized, you may use the button below to launch a background task where ChemVantage will resubmit them to your LMS. This can take several seconds to minutes depending on the number of scores to process. Please note that you may have to adjust the settings in your LMS to accept the revised scores. For example, in Canvas you may need to change the assignment settings to Unlimited Submissions. This may also cause the submission to be counted as late if the LMS assignment deadline has passed.";
	
	// External URLs
	/** URL for AP Chemistry course information */
	private static final String URL_AP_CHEMISTRY = "https://apcentral.collegeboard.org/courses/ap-chemistry";
	
	// Numeric constants
	/** Maximum iterations for checking all list items in JavaScript */
	private static final String JS_INDEX_VAR = "i";
	/** Maximum iterations for checking all checkboxes in JavaScript */
	private static final String JS_INNER_INDEX_VAR = "j";
	
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
			String userRequest = request.getParameter(PARAM_USER_REQUEST);
			if (userRequest == null) {
				userRequest = "";
			}
			
			switch (userRequest) {
			case USER_REQUEST_INSTRUCTOR_PAGE:
				response.setContentType(CONTENT_TYPE_HTML);
				out.println(instructorPage(request));
				break;
			case USER_REQUEST_SELECT_TOPICS:
				response.setContentType(CONTENT_TYPE_HTML);
				out.println(viewTopicSelectForm(request));
				break;
			case USER_REQUEST_REVIEW_SCORES:
				response.setContentType(CONTENT_TYPE_HTML);
				out.println(reviewScores(request));
				break;
			default:
				response.setContentType(CONTENT_TYPE_JSON);
				String authHeader = request.getHeader(AUTHORIZATION_HEADER);
				if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
					throw new Exception(ERROR_UNAUTHORIZED);
				}
				String token = authHeader.substring(BEARER_PREFIX.length());
				String sig = Util.isValid(token);
				responseJson.addProperty(JSON_TOKEN, Util.getToken(sig));

				User user = User.getUser(sig);
				JsonObject q = getCurrentQuestion(user);
				if (q == null) throw new Exception("Unable to get a new question.");
				responseJson.add(JSON_QUESTION, q);
				out.println(responseJson.toString());
			}
		} catch (Exception e) {
			if (!response.getContentType().startsWith(CONTENT_TYPE_JSON.substring(0, 16))) {
				response.setContentType(CONTENT_TYPE_JSON);
			}
			JsonObject question = new JsonObject();
			question.addProperty(JSON_TYPE, ERROR_QUESTION_TYPE);
			question.addProperty(JSON_ID, ERROR_QUESTION_ID);
			question.addProperty(JSON_PROMPT, e.getMessage());
			responseJson.add(JSON_QUESTION, question);
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
		response.setContentType(CONTENT_TYPE_JSON);
		PrintWriter out = response.getWriter();
		try {
			String userRequest = request.getParameter(PARAM_USER_REQUEST);
			if (userRequest == null) {
				userRequest = "";
			}
			
			switch (userRequest) {
			case USER_REQUEST_ASSIGN_TOPICS:
				response.setContentType(CONTENT_TYPE_HTML);
				out.println(assignTopics(request));
				break;
			case USER_REQUEST_SYNCHRONIZE_SCORES:
				response.setContentType(CONTENT_TYPE_HTML);
				out.println(synchronizeScores(request));
				break;
			default:
				out.println(getResponseJson(request).toString());
			}
		} catch (Exception e) {
			JsonObject err = new JsonObject();
			err.addProperty(JSON_ERROR, e.getMessage());
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
		String sig = request.getParameter(PARAM_SIG);
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
		String[] topicIds = request.getParameterValues(PARAM_TOPIC_ID);
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
			j.addProperty(JSON_PARAMETER, parameter);
			prompt = q.parseString(q.prompt);
		}
		j.addProperty(JSON_ID, q.id);
		j.addProperty(JSON_TYPE, q.type);
		j.addProperty(JSON_PROMPT, prompt);
	
		if (q.units != null) j.addProperty(JSON_UNITS, q.units);
		if (q.choices != null) {
			j.addProperty(JSON_SCRAMBLED, q.scrambleChoices);
			JsonArray choices = new JsonArray();
			for (String c : q.choices) choices.add(c);
			j.add(JSON_CHOICES, choices);
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
		String authHeader = request.getHeader(AUTHORIZATION_HEADER);
		if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
			throw new Exception(ERROR_UNAUTHORIZED);
		}
		String token = authHeader.substring(BEARER_PREFIX.length());
		String sig = Util.isValid(token);
		
		JsonObject requestJson = null;
		try (BufferedReader reader = request.getReader()) {
			requestJson = JsonParser.parseReader(reader).getAsJsonObject();
		}
		
		if (!requestJson.has(PARAM_ID) || requestJson.get(PARAM_ID).isJsonNull()) {
			throw new IllegalArgumentException("Missing required field: id");
		}
		if (!requestJson.has(PARAM_ANSWER) || requestJson.get(PARAM_ANSWER).isJsonNull()) {
			throw new IllegalArgumentException("Missing required field: answer");
		}
		
		Long questionId = requestJson.get(PARAM_ID).getAsLong();
		String studentAnswer = requestJson.get(PARAM_ANSWER).getAsString().trim();
		
		Question q = ofy().load().type(Question.class).id(questionId).safe();
		if (q == null) {
			throw new Exception("Question not found: " + questionId);
		}
		Integer parameter = null;
		if (q.requiresParser()) {
			if (!requestJson.has(PARAM_PARAMETER) || requestJson.get(PARAM_PARAMETER).isJsonNull()) {
				throw new IllegalArgumentException("Missing required field: parameter");
			}
			parameter = requestJson.get(PARAM_PARAMETER).getAsInt();
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
		responseJson.addProperty(JSON_TOKEN, Util.getToken(sig));
		responseJson.addProperty(JSON_HTML, feedbackHtml);
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
			buf.append(FEEDBACK_CORRECT);
		} else {
			buf.append(FEEDBACK_INCORRECT_PREFIX);
			buf.append("<a href=").append(PATH_FEEDBACK).append("?sig=").append(user.getTokenSignature())
			   .append("&questionId=").append(questionId);
			if (parameter != null) {
				buf.append("&parameter=").append(parameter);
			}
			buf.append("&studentAnswer=").append(URLEncoder.encode(studentAnswer, URL_ENCODING))
			   .append(" style='display: inline;' target=_blank>")
			   .append("<img src=").append(PATH_IMAGES).append("/").append(IMAGE_FEEDBACK)
			   .append(" style='height:").append(IMAGE_HEIGHT).append(";vertical-align:").append(IMAGE_VERTICAL_ALIGN).append(";' alt='").append(IMAGE_ALT_FEEDBACK)
			   .append("' title='").append(IMAGE_TITLE_FEEDBACK).append("' /></a></h2>")
			   .append(FEEDBACK_CORRECT_ANSWER).append(q.getCorrectAnswer());
		}
		
		buf.append(MESSAGE_SCORE_PREFIX).append(s.totalScore).append(MESSAGE_SCORE_SUFFIX);
		if (s.totalScore == PERFECT_SCORE && user.platformId.equals(Util.getServerUrl())) {
			buf.append("&nbsp;<a href='").append(PATH_LAUNCH).append("?sig=").append(user.getTokenSignature())
			   .append("'><button class='").append(CSS_CLASS_BTN_PRIMARY).append("'>").append(BUTTON_FINISH).append("</button></a>");
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
		String sig = request.getParameter(PARAM_SIG);
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
		
		StringBuilder buf = new StringBuilder(Util.head(TITLE_INSTRUCTOR_PAGE));
		
		buf.append(Util.banner).append(HEADING_EXERCISES)
		   .append(DESC_ASSIGNMENT)
		   .append("<ul>")
		   .append(DESC_FORMATIVE)
		   .append(DESC_ADAPTIVE)
		   .append("</ul>")
		   .append("<a href=/exercises?UserRequest=").append(USER_REQUEST_REVIEW_SCORES).append("&sig=").append(user.getTokenSignature())
		   .append(">").append(LINK_REVIEW_SCORES).append("</a><p>");

		buf.append("<h2>Topics Covered</h2>")
		   .append("This assignment covers the following ")
		   .append("<a href=").append(URL_AP_CHEMISTRY).append(" target=_blank>")
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
		   .append("<a href=/exercises?UserRequest=").append(USER_REQUEST_SELECT_TOPICS).append("&sig=").append(user.getTokenSignature()).append(">")
		   .append(LINK_SELECT_TOPICS).append("</a> to suit the current needs of your class.<br/><br/>");
		
		buf.append("<a class='").append(CSS_CLASS_BTN_PRIMARY).append("' href='/exercises/index.html?t=").append(Util.getToken(user.getTokenSignature())).append("'>")
		   .append(LINK_VIEW_ASSIGNMENT).append("</a><p>");
		
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
		String sig = request.getParameter(PARAM_SIG);
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
		
		StringBuilder buf = new StringBuilder(Util.head(TITLE_SELECT_TOPICS));
		buf.append(Util.banner).append(HEADING_SELECT_TOPICS);
		
		buf.append("<form method=").append(FORM_METHOD_POST).append(" action=/exercises>")
		   .append("<input type=").append(INPUT_TYPE_HIDDEN).append(" name=").append(PARAM_SIG).append(" value=").append(user.getTokenSignature()).append(" />")
		   .append("<input type=").append(INPUT_TYPE_HIDDEN).append(" name=").append(PARAM_USER_REQUEST).append(" value=").append(USER_REQUEST_ASSIGN_TOPICS).append(" />")
		   .append("<input type=").append(INPUT_TYPE_SUBMIT).append(" class='").append(CSS_CLASS_BTN_PRIMARY).append("' value='").append(BTN_ASSIGN_TOPICS).append("' /> ")
		   .append("<a class='").append(CSS_CLASS_BTN_PRIMARY).append("' href=/exercises?UserRequest=").append(USER_REQUEST_INSTRUCTOR_PAGE).append("&sig=").append(user.getTokenSignature()).append(">").append(BTN_QUIT).append("</a><br/><br/>")
		   .append("<ul style='list-style: none;'>");
		
		List<APChemUnit> units = ofy().load().type(APChemUnit.class).order("unitNumber").list();
		for (APChemUnit u : units) {
			if (u == null) continue;
			buf.append("<li><label><input type=").append(INPUT_TYPE_CHECKBOX).append(" class=").append(CSS_CLASS_UNIT_CHECKBOX).append(" id=").append(u.id)
			   .append(" value=").append(u.id).append(" onclick=").append(JS_FUNC_UNIT_CLICKED).append("('").append(u.id).append("');")
			   .append(" /> <b>Unit ").append(u.unitNumber).append(" - ").append(u.title).append("</b></label></li>")
			   .append("<ul style='list-style: none;'>");
			
			List<APChemTopic> topics = ofy().load().type(APChemTopic.class).filter("unitId", u.id).order("topicNumber").list();
			for (APChemTopic t : topics) {
				if (t == null) continue;
				buf.append("<li class='").append(CSS_CLASS_LIST_PREFIX).append(u.id).append("'>")
				   .append("<label><input type=").append(INPUT_TYPE_CHECKBOX).append(" class=").append(CSS_CLASS_UNIT_PREFIX).append(u.id)
				   .append(" name=").append(PARAM_TOPIC_ID).append(" value=").append(t.id).append(" ");
				if (a.topicIds.contains(t.id)) {
					buf.append("checked");
				}
				buf.append(" onclick=").append(JS_FUNC_TOPIC_CLICKED).append("('").append(u.id).append("')")
				   .append(" /> ").append(t.title).append("</label>")
				   .append("</li>");
			}
			buf.append("</ul>");
		}
		
		buf.append("</ul>")
		   .append("<input type=").append(INPUT_TYPE_SUBMIT).append(" class='").append(CSS_CLASS_BTN_PRIMARY).append("' value='").append(BTN_ASSIGN_TOPICS).append("' /> ")
		   .append("<a class='").append(CSS_CLASS_BTN_PRIMARY).append("' href=/exercises?UserRequest=").append(USER_REQUEST_INSTRUCTOR_PAGE).append("&sig=").append(user.getTokenSignature()).append(">").append(BTN_QUIT).append("</a><br/>")
		   .append("</form>");
		
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
		script.append("\n<script>\n");
		script.append("// Initialize topic selection form state\n");
		script.append("var unitBoxes = document.querySelectorAll('input.").append(CSS_CLASS_UNIT_CHECKBOX).append("');\n");
		script.append("var topicBoxes, checkAll, checkedCount;\n");
		script.append("for (var ").append(JS_INDEX_VAR).append("=0;").append(JS_INDEX_VAR).append("<unitBoxes.length;").append(JS_INDEX_VAR).append("++) {\n");
		script.append("  topicBoxes = document.querySelectorAll('input.").append(CSS_CLASS_UNIT_PREFIX).append("' + unitBoxes[").append(JS_INDEX_VAR).append("].id);\n");
		script.append("  checkAll = document.getElementById(unitBoxes[").append(JS_INDEX_VAR).append("].id);\n");
		script.append("  checkedCount = document.querySelectorAll('input.").append(CSS_CLASS_UNIT_PREFIX).append("' + unitBoxes[").append(JS_INDEX_VAR).append("].id + ':checked').length;\n");
		script.append("  checkAll.checked = checkedCount>0;\n");
		script.append("  checkAll.indeterminate = checkedCount>0 && checkedCount<topicBoxes.length;\n");
		script.append("  var listItems = document.querySelectorAll('li.").append(CSS_CLASS_LIST_PREFIX).append("' + unitBoxes[").append(JS_INDEX_VAR).append("].id);\n");
		script.append("  for (var ").append(JS_INNER_INDEX_VAR).append("=0;").append(JS_INNER_INDEX_VAR).append("<listItems.length;").append(JS_INNER_INDEX_VAR).append("++) {\n");
		script.append("    listItems[").append(JS_INNER_INDEX_VAR).append("].style='").append(CSS_DISPLAY_LIST_ITEM).append("' + (checkedCount>0?'list-item':'none');\n");
		script.append("  }\n");
		script.append("}\n");
		script.append("\n// Handle topic checkbox change\n");
		script.append("function ").append(JS_FUNC_TOPIC_CLICKED).append("(unitId) {\n");
		script.append("  var topicCount = document.querySelectorAll('input.").append(CSS_CLASS_UNIT_PREFIX).append("' + unitId).length;\n");
		script.append("  var checkedCount = document.querySelectorAll('input.").append(CSS_CLASS_UNIT_PREFIX).append("' + unitId + ':checked').length;\n");
		script.append("  var checkAll = document.getElementById(unitId);\n");
		script.append("  checkAll.checked = checkedCount>0;\n");
		script.append("  checkAll.indeterminate = checkedCount>0 && checkedCount<topicCount;\n");
		script.append("  if (checkedCount==0) {\n");
		script.append("    var listItems = document.querySelectorAll('li.").append(CSS_CLASS_LIST_PREFIX).append("' + unitId);\n");
		script.append("    for (var ").append(JS_INNER_INDEX_VAR).append("=0;").append(JS_INNER_INDEX_VAR).append("<listItems.length;").append(JS_INNER_INDEX_VAR).append("++) {\n");
		script.append("      listItems[").append(JS_INNER_INDEX_VAR).append("].style='").append(CSS_DISPLAY_NONE).append("';\n");
		script.append("    }\n");
		script.append("  }\n");
		script.append("}\n");
		script.append("\n// Handle unit checkbox change\n");
		script.append("function ").append(JS_FUNC_UNIT_CLICKED).append("(unitId) {\n");
		script.append("  var unitBox = document.getElementById(unitId);\n");
		script.append("  var listItems = document.querySelectorAll('li.").append(CSS_CLASS_LIST_PREFIX).append("' + unitId);\n");
		script.append("  var topicBoxes = document.querySelectorAll('input.").append(CSS_CLASS_UNIT_PREFIX).append("' + unitId);\n");
		script.append("  for (var ").append(JS_INDEX_VAR).append("=0;").append(JS_INDEX_VAR).append("<topicBoxes.length;").append(JS_INDEX_VAR).append("++) {\n");
		script.append("    topicBoxes[").append(JS_INDEX_VAR).append("].checked = unitBox.checked;\n");
		script.append("    if (").append(JS_INDEX_VAR).append(" < listItems.length) {\n");
		script.append("      listItems[").append(JS_INDEX_VAR).append("].style='").append(CSS_DISPLAY_LIST_ITEM).append("';\n");
		script.append("    }\n");
		script.append("  }\n");
		script.append("}\n");
		script.append("</script>\n");
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
		String sig = request.getParameter(PARAM_SIG);
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
		
		StringBuilder buf = new StringBuilder(Util.head(TITLE_REVIEW_SCORES));
		buf.append(Util.banner).append(HEADING_SCORES);
		
		buf.append("<h2>").append(a.title == null ? "" : a.title).append("</h2>");
		buf.append("Valid: ").append(new Date()).append("<p>");
		
		try {
			if (a.lti_nrps_context_memberships_url == null) {
				throw new Exception("No Names and Roles Provisioning support.");
			}

			buf.append(HELP_NRPS)
			   .append("<p>")
			   .append("<a href=/exercises?UserRequest=").append(USER_REQUEST_INSTRUCTOR_PAGE).append("&sig=").append(user.getTokenSignature())
			   .append(">").append(LINK_RETURN_TO_INSTRUCTOR).append("</a><br/><br/>");

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
			buf.append(TABLE_HEADER_OPEN);
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
				   .append("<td ").append(TABLE_ALIGN_CENTER).append(">").append(s == null ? SCORE_NOT_AVAILABLE : s + SCORE_FORMAT_PERCENT).append("</td>")
				   .append("<td ").append(TABLE_ALIGN_CENTER).append(">").append(cvScore == null ? SCORE_NOT_AVAILABLE : cvScore.maxScore + SCORE_FORMAT_PERCENT).append("</td>")
				   .append("</tr>");
				
				synched = synched && 
						(!LTI_ROLE_LEARNER.equals(roleInfo[0]) || 
						(cvScore == null || Double.valueOf(cvScore.maxScore).equals(Double.parseDouble(s))));
			}
			buf.append("</table><br/>");
			if (!synched) {
				buf.append(HELP_SYNC_SCORES)
				   .append("<br/>")
				   .append("<form method=").append(FORM_METHOD_POST).append(" action=/exercises >")
				   .append("<input type=").append(INPUT_TYPE_HIDDEN).append(" name=").append(PARAM_SIG).append(" value=").append(user.getTokenSignature()).append(" />")
				   .append("<input type=").append(INPUT_TYPE_HIDDEN).append(" name=").append(PARAM_USER_REQUEST).append(" value='").append(USER_REQUEST_SYNCHRONIZE_SCORES).append("' />")
				   .append("<input id=syncsubmit type=").append(INPUT_TYPE_SUBMIT).append(" class='").append(CSS_CLASS_BTN_PRIMARY).append("' value='").append(BTN_SYNCHRONIZE).append("' ")
				   .append("onclick=").append(ONCLICK_SYNC_HIDE).append(" />")
				   .append("</form>");
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
		String sig = request.getParameter(PARAM_SIG);
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
			Util.createTask(PATH_REPORT,TASK_PARAM_ASSIGNMENT_ID + a.id + TASK_PARAM_USER_ID + URLEncoder.encode(platform_id + entry.getKey(),URL_ENCODING));
		}
		return instructorPage(user,a);
	}
}

