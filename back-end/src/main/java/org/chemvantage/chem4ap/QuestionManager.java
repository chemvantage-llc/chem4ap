package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * QuestionManager servlet for managing AP Chemistry question items and content.
 * 
 * Handles CRUD (Create, Read, Update, Delete) operations for AP Chemistry exam questions
 * used in exercises and homework assignments. Manages question editing, previewing,
 * bulk import/export via JSON, and curriculum organization.
 * 
 * HTTP Routes (UserRequest parameter):
 * - EditQuestion: Display editor for existing question (requires QuestionId)
 * - NewQuestion: Display form to create new question (requires AssignmentType, TopicId, QuestionType)
 * - NewJson: Import questions from JSON payload (requires AssignmentType, TopicId, json)
 * - Preview: Show preview of edited question before saving
 * - Save New Question: Create new question entity in datastore
 * - Update Question: Modify existing question (requires QuestionId)
 * - Delete Question: Remove question from datastore (requires QuestionId)
 * - Edit: Prepare JSON question for bulk editing
 * - Save Item / Save Selected Items: Persist edited questions from JSON array
 * - Remove Selected Items: Delete questions from bulk import
 * - ViewQuestions (default): Display questions for assignment type/topic
 * 
 * Question Types Supported:
 * - multiple_choice: Multiple choice with single correct answer
 * - true_false: True/False questions
 * - checkbox: Select-all-that-apply questions
 * - fill_in_blank: Short answer with exact match checking
 * - numeric: Numeric answer with precision tolerance
 * - essay: Free-form student response (not auto-scored)
 * 
 * Curriculum Hierarchy:
 * - APChemUnit: AP Chemistry units (e.g., Atomic Structure, Bonding)
 * - APChemTopic: Individual topics within units (e.g., Electron Configuration)
 * - Question: Individual question items within topics
 * 
 * Question Editing Workflow:
 * 1. User selects assignment type (Exercises or Homework)
 * 2. User selects unit and topic
 * 3. User creates new question or edits existing question
 * 4. Question is previewed before saving
 * 5. Question is persisted to datastore with assignment type and topic ID
 * 
 * JSON Bulk Import:
 * - Supports importing multiple questions via JSON array
 * - Each question must be valid JSON object with required fields
 * - Supports both single question object and array of questions
 * - Questions can be reviewed, edited, and saved individually
 * - Supports deselection and removal of questions before committing
 * 
 * Data Persistence:
 * - Questions are stored in Google Cloud Datastore via Objectify ORM
 * - Indexed by assignmentType and topicId for efficient querying
 * - Success rate calculated from student answer statistics
 * 
 * Security Considerations:
 * - Requires authenticated admin/instructor session to access
 * - Question content not sanitized; XSS concerns if user input stored
 * - No access control checks visible; relies on servlet mapping restriction
 * 
 * @author ChemVantage
 * @version 2.0
 * @see Question
 * @see APChemUnit
 * @see APChemTopic
 * @see Exercises#getCurrentQuestion()
 */
@WebServlet("/questions")
public class QuestionManager extends HttpServlet {

	private static final long serialVersionUID = 1L;
	
	// HTTP request routing constants
	/** HTTP parameter for routing different question manager operations */
	private static final String USER_REQUEST = "UserRequest";
	/** Route: Edit an existing question */
	private static final String USER_REQUEST_EDIT_QUESTION = "EditQuestion";
	/** Route: Create new question form */
	private static final String USER_REQUEST_NEW_QUESTION = "NewQuestion";
	/** Route: Import questions from JSON */
	private static final String USER_REQUEST_NEW_JSON = "NewJson";
	/** Route: Preview question before saving */
	private static final String USER_REQUEST_PREVIEW = "Preview";
	/** Route: Save new question to datastore */
	private static final String USER_REQUEST_SAVE_NEW_QUESTION = "Save New Question";
	/** Route: Update existing question */
	private static final String USER_REQUEST_UPDATE_QUESTION = "Update Question";
	/** Route: Delete question from datastore */
	private static final String USER_REQUEST_DELETE_QUESTION = "Delete Question";
	/** Route: Edit bulk import question */
	private static final String USER_REQUEST_EDIT = "Edit";
	/** Route: Save individual item from bulk import */
	private static final String USER_REQUEST_SAVE_ITEM = "Save Item";
	/** Route: Save multiple selected items from bulk import */
	private static final String USER_REQUEST_SAVE_SELECTED_ITEMS = "Save Selected Items";
	/** Route: Remove selected items from bulk import */
	private static final String USER_REQUEST_REMOVE_SELECTED_ITEMS = "Remove Selected Items";
	/** Route: Display questions for assignment and topic (default) */
	private static final String USER_REQUEST_VIEW_QUESTIONS = "ViewQuestions";
	/** Route: Quit/return to question list */
	private static final String USER_REQUEST_QUIT = "Quit";
	
	// Request parameter name constants
	/** HTTP parameter: Unique question identifier */
	private static final String PARAM_QUESTION_ID = "QuestionId";
	/** HTTP parameter: Assignment type (Exercises or Homework) */
	private static final String PARAM_ASSIGNMENT_TYPE = "AssignmentType";
	/** HTTP parameter: Curriculum topic identifier */
	private static final String PARAM_TOPIC_ID = "TopicId";
	/** HTTP parameter: Curriculum unit identifier */
	private static final String PARAM_UNIT_ID = "UnitId";
	/** HTTP parameter: Question type (multiple_choice, true_false, etc.) */
	private static final String PARAM_QUESTION_TYPE = "QuestionType";
	/** HTTP parameter: Question prompt/text */
	private static final String PARAM_PROMPT = "Prompt";
	/** HTTP parameter: Answer choice text (followed by letter A-E) */
	private static final String PARAM_CHOICE = "Choice";
	/** HTTP parameter: Correct answer(s) */
	private static final String PARAM_CORRECT_ANSWER = "CorrectAnswer";
	/** HTTP parameter: Required precision for numeric answers (percent) */
	private static final String PARAM_REQUIRED_PRECISION = "RequiredPrecision";
	/** HTTP parameter: Significant figures requirement */
	private static final String PARAM_SIGNIFICANT_FIGURES = "SignificantFigures";
	/** HTTP parameter: Units or dimensions of expected answer */
	private static final String PARAM_UNITS = "Units";
	/** HTTP parameter: Parameter string for math expression parsing */
	private static final String PARAM_PARAMETER_STRING = "ParameterString";
	/** HTTP parameter: Whether to randomize answer choices */
	private static final String PARAM_SCRAMBLE_CHOICES = "ScrambleChoices";
	/** HTTP parameter: Whether to require exact spelling match */
	private static final String PARAM_STRICT_SPELLING = "StrictSpelling";
	/** HTTP parameter: JSON payload with bulk import questions */
	private static final String PARAM_JSON = "json";
	/** HTTP parameter: Bulk import item index for save/delete operations */
	private static final String PARAM_INDEX = "index";
	/** HTTP parameter: Proposed question ID for activation */
	private static final String PARAM_PROPOSED_QUESTION_ID = "ProposedQuestionId";
	
	// Question type constants
	/** Question type: Multiple choice with single correct answer */
	private static final String QUESTION_TYPE_MULTIPLE_CHOICE = "multiple_choice";
	/** Question type: True/False question */
	private static final String QUESTION_TYPE_TRUE_FALSE = "true_false";
	/** Question type: Select-multiple (checkbox) question */
	private static final String QUESTION_TYPE_CHECKBOX = "checkbox";
	/** Question type: Fill-in-the-blank question */
	private static final String QUESTION_TYPE_FILL_IN_BLANK = "fill_in_blank";
	/** Question type: Numeric answer question */
	private static final String QUESTION_TYPE_NUMERIC = "numeric";
	/** Question type: Essay/free-form response question */
	private static final String QUESTION_TYPE_ESSAY = "essay";
	
	// Assignment type constants
	/** Assignment type: Adaptive exercise questions */
	private static final String ASSIGNMENT_TYPE_EXERCISES = "Exercises";
	/** Assignment type: Homework assignment questions */
	private static final String ASSIGNMENT_TYPE_HOMEWORK = "Homework";
	
	// HTML/Form element constants
	/** HTML form field: Choice A text */
	private static final String CHOICE_A = "A";
	/** Maximum number of answer choices */
	private static final int MAX_CHOICES = 5;
	/** HTML element ID: Fake element for JSON parsing */
	private static final String ELEMENT_ID_FAKE = "fake";
	/** CSS class: Bootstrap primary button */
	private static final String CSS_CLASS_BTN_PRIMARY = "btn btn-primary";
	
	// Numeric constants
	/** Default precision tolerance for numeric answers (percent) */
	private static final double DEFAULT_PRECISION = 2.0;
	/** Zero value for default initialization */
	private static final int ZERO = 0;
	/** Index for first choice letter */
	private static final int CHOICE_INDEX_START = 0;
	
	// Message and display constants
	/** Error message prefix for display */
	private static final String ERROR_PREFIX = "Chem4AP Error: ";
	/** Heading for question editor interface */
	private static final String HEADING_EDITOR = "Editor";
	/** Heading for current question display */
	private static final String HEADING_CURRENT_QUESTION = "Current Question";
	/** Heading for question preview */
	private static final String HEADING_PREVIEW_QUESTION = "Preview Question";
	/** Heading for edit question form */
	private static final String HEADING_EDIT_QUESTION_FORM = "Edit This Question";
	/** Heading for new question form */
	private static final String HEADING_NEW_QUESTION = "New Question";
	/** Heading for manage questions page */
	private static final String HEADING_MANAGE_QUESTIONS = "Manage Question Items";
	/** Heading for question items list */
	private static final String HEADING_QUESTION_ITEMS = "Question Items";
	/** Label: Topic selection */
	private static final String LABEL_TOPIC = "Topic";
	/** Label: Question type selection */
	private static final String LABEL_QUESTION_TYPE = "Question Type";
	/** Label: Assignment type radio button (Exercises) */
	private static final String LABEL_ASSIGNMENT_EXERCISES = "Exercises";
	/** Label: Assignment type radio button (Homework) */
	private static final String LABEL_ASSIGNMENT_HOMEWORK = "Homework";
	
	// UI text constants for question type descriptions
	/** Description: Multiple-choice question instructions */
	private static final String DESC_MULTIPLE_CHOICE = "Multiple-Choice Question";
	private static final String HELP_MULTIPLE_CHOICE = "Fill in the question text and the possible answers (up to a maximum of 5). Be sure to select the single best answer to the question.";
	/** Description: True-false question instructions */
	private static final String DESC_TRUE_FALSE = "True-False Question";
	private static final String HELP_TRUE_FALSE = "Write the question as an affirmative statement. Then indicate below whether the statement is true or false.";
	/** Description: Checkbox question instructions */
	private static final String DESC_CHECKBOX = "Select-Multiple Question";
	private static final String HELP_CHECKBOX = "Fill in the question text and the possible answers (up to a maximum of 5). Be sure to select all of the correct answers to the question.";
	/** Description: Fill-in-blank question instructions */
	private static final String DESC_FILL_IN_BLANK = "Fill-in-Blank Question";
	private static final String HELP_FILL_IN_BLANK = "Write the prompt as a sentence containing a blank (________). Write the correct answer (and optionally, an alternative correct answer) in the box below using commas to separate the correct options.  The answers are not case-sensitive or punctuation-sensitive.";
	/** Description: Numeric question instructions */
	private static final String DESC_NUMERIC = "Numeric Question";
	private static final String HELP_NUMERIC = "Fill in the question text in the upper textarea box and the correct numeric answer below. Also indicate the required precision of the student's response in percent (default = 2%). Use the bottom textarea box to finish the question text and/or to indicate the expected dimensions or units of the student's answer.";
	/** Description: Essay question instructions */
	private static final String DESC_ESSAY = "Essay Question";
	private static final String HELP_ESSAY = "Fill in the question text. The user will be asked to provide a short essay response.";
	/** Error message for unexpected question type */
	private static final String ERROR_UNEXPECTED_QUESTION_TYPE = "An unexpected error occurred. Please try again.";
	
	// Topic count display constants
	/** Suffix: True/False question count */
	private static final String TOPIC_COUNT_TF = "TF-";
	/** Suffix: Multiple-choice question count */
	private static final String TOPIC_COUNT_MC = "MC-";
	/** Suffix: Fill-in-blank question count */
	private static final String TOPIC_COUNT_FB = "FB-";
	/** Suffix: Checkbox question count */
	private static final String TOPIC_COUNT_CB = "CB-";
	/** Suffix: Numeric question count */
	private static final String TOPIC_COUNT_NU = "NU-";
	
	// In-memory cache for curriculum
	/** Cached list of AP Chemistry units (refreshed on demand) */
	List<APChemUnit> unitList = null;
	/** Cached list of AP Chemistry topics (refreshed on demand) */
	List<APChemTopic> topicList = null;
	/** Cached map of topic ID to APChemTopic for fast lookups */
	Map<Long,APChemTopic> topicMap = null;

	/**
	 * Handles HTTP GET requests for question management operations.
	 * 
	 * Routes based on UserRequest parameter:
	 * - EditQuestion: Display editor for existing question
	 * - NewQuestion: Display form for new question creation
	 * - NewJson: Import questions from JSON payload
	 * - Preview: Preview question before saving
	 * - (default): Display questions for assignment type and topic
	 * 
	 * Query Parameters:
	 * - UserRequest: Operation to perform (see routes above)
	 * - QuestionId: ID of question to edit/preview (for EditQuestion)
	 * - AssignmentType: Exercises or Homework (required for NewQuestion, NewJson)
	 * - TopicId: Curriculum topic ID (optional, used to infer UnitId)
	 * - UnitId: Curriculum unit ID (optional, filters topic list)
	 * 
	 * @param request HTTP request with UserRequest parameter and associated IDs
	 * @param response HTTP response containing HTML form or question list
	 * @throws ServletException for servlet container errors
	 * @throws IOException for response stream errors
	 */
	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
	/********* temporary utility for Microsoft Partnership *************************
		if ("Get Json".equals(request.getParameter(USER_REQUEST))) {
			try {
				response.setContentType("text/plain");
				Integer n = Integer.parseInt(request.getParameter("n"));
				if (n<=0) throw new Exception("Enter a valid integer.");
				out.println(sampleQuestions(n));
			} catch (Exception e) {
				out.println(e.getMessage());
			}
			return;
		}
	*********************************************************************************/	
		
		String userRequest = request.getParameter(USER_REQUEST);
		if (userRequest == null) userRequest = "";
		
		try {
			switch (userRequest) {
			case USER_REQUEST_EDIT_QUESTION:
				Long questionId = null;
				questionId = Long.parseLong(request.getParameter(PARAM_QUESTION_ID)); 
				out.println(editQuestion(questionId));
				break;
			case USER_REQUEST_NEW_QUESTION: 
				out.println(newQuestionForm(request)); 
				break;
			case USER_REQUEST_NEW_JSON:
				out.println(newJsonForm(request));
				break;
			case USER_REQUEST_PREVIEW:
				out.println(previewQuestion(request));
				break;
			default:
				String assignmentType = request.getParameter(PARAM_ASSIGNMENT_TYPE);
				Long topicId = null;
				Long unitId = null;
				try {
					unitId = Long.parseLong(request.getParameter(PARAM_UNIT_ID));
				} catch (Exception e) {}
				try {
					topicId = Long.parseLong(request.getParameter(PARAM_TOPIC_ID)); 
					if (unitId == null) {
						APChemTopic topic = ofy().load().type(APChemTopic.class).id(topicId).safe();
						unitId = topic.unitId;
					}
				} catch (Exception e) {}
				out.println(viewQuestions(assignmentType,unitId,topicId));
			}
		} catch (Exception e) {
			response.getWriter().println(ERROR_PREFIX + (e.getMessage()==null?e.toString():e.getMessage()));
		}
	}
		
	/**
	 * Handles HTTP POST requests for question save, update, and delete operations.
	 * 
	 * Routes based on UserRequest parameter:
	 * - Preview: Preview edited question from JSON bulk import
	 * - Save New Question: Create new question from form data
	 * - Update Question: Modify existing question
	 * - Delete Question: Remove question from datastore
	 * - Edit: Prepare JSON bulk import question for editing
	 * - Save Item / Save Selected Items: Save selected questions from bulk import
	 * - Remove Selected Items: Delete questions from bulk import
	 * 
	 * All save/update operations redirect to doGet() to refresh the question list view.
	 * 
	 * Form Parameters:
	 * - UserRequest: Operation to perform
	 * - QuestionId: ID of question being edited/deleted (for Update/Delete)
	 * - AssignmentType: Exercises or Homework
	 * - TopicId: Topic for new questions
	 * - json: JSON payload for bulk import operations
	 * 
	 * @param request HTTP request with form data
	 * @param response HTTP response redirecting to question list
	 * @throws ServletException for servlet container errors
	 * @throws IOException for response stream errors
	 */
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		String userRequest = request.getParameter(USER_REQUEST);
		if (userRequest==null) return;
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		try {
			switch (userRequest) {
			case USER_REQUEST_PREVIEW:
				String json = request.getParameter(PARAM_JSON);
				if (json != null) { // bulk edit in progress
					out.println(previewQuestion(request,json));
					return;
				}
				break;
			case USER_REQUEST_SAVE_NEW_QUESTION:
				out.println(createQuestion(request));
				return;
			case USER_REQUEST_UPDATE_QUESTION:
				updateQuestion(request);
				break;
			case USER_REQUEST_DELETE_QUESTION:
				deleteQuestion(request);
				break;
			case USER_REQUEST_EDIT:
				out.println(editItem(request));
				return;
			case USER_REQUEST_SAVE_ITEM:	
			case USER_REQUEST_SAVE_SELECTED_ITEMS:
				saveItems(request);
			case USER_REQUEST_REMOVE_SELECTED_ITEMS:
				out.println(removeItems(request));
				return;
			}
		} catch (Exception e) {
			response.getWriter().println(e.getMessage()==null?e.toString():e.getMessage());
		}
		doGet(request,response);
	}
	
	/**
	 * Assembles a Question entity from HTTP request parameters.
	 * 
	 * Extracts question type from request and creates a new Question object
	 * with that type, then populates all fields via {@link #assembleQuestion(HttpServletRequest, Question)}.
	 * 
	 * @param request HTTP request containing QuestionType parameter and question field values
	 * @return Question entity with all fields populated from request, or null if assembly fails
	 */
	Question assembleQuestion(HttpServletRequest request) {
		try {
			String type = request.getParameter(PARAM_QUESTION_TYPE);
			return assembleQuestion(request,new Question(type)); 
		} catch (Exception e) {
			return null;
		}
	}
	
	/**
	 * Populates Question entity fields from HTTP request parameters.
	 * 
	 * Extracts all question content from form submission:
	 * - Prompt text and answer choices (A-E)
	 * - Correct answer(s) and type-specific parameters
	 * - Numeric answer settings (precision, significant figures, units)
	 * - Formatting options (scramble choices, strict spelling)
	 * 
	 * Question choices are validated for non-empty text;
	 * numeric parameters default to zero if not specified.
	 * 
	 * @param request HTTP request with question form fields
	 * @param q Question entity to populate (existing or new)
	 * @return Same Question object with all fields set from request
	 * @see Question#type
	 * @see Question#topicId
	 * @see Question#choices
	 * @see Question#requiredPrecision
	 */
	Question assembleQuestion(HttpServletRequest request,Question q) {
		long topicId = 0;
		try {
			topicId = Long.parseLong(request.getParameter(PARAM_TOPIC_ID));
		} catch (Exception e) {}
		String type = q.type;
		try {
			type = request.getParameter(PARAM_QUESTION_TYPE);
		}catch (Exception e) {}
		String prompt = request.getParameter(PARAM_PROMPT);
		ArrayList<String> choices = new ArrayList<String>();
		char choice = CHOICE_A.charAt(CHOICE_INDEX_START);
		for (int i=CHOICE_INDEX_START;i<MAX_CHOICES;i++) {
			String choiceText = request.getParameter(PARAM_CHOICE+ choice +"Text");
			if (choiceText==null) choiceText = "";
			if (choiceText.length() > 0) {
				choices.add(choiceText);
			}
			choice++;
		}
		double requiredPrecision = DEFAULT_PRECISION;
		int significantFigures = ZERO;
		try {
			requiredPrecision = Double.parseDouble(request.getParameter(PARAM_REQUIRED_PRECISION));
		} catch (Exception e) {
		}
		try {
			significantFigures = Integer.parseInt(request.getParameter(PARAM_SIGNIFICANT_FIGURES));
		} catch (Exception e) {
		}
		String correctAnswer = "";
		try {
			String[] allAnswers = request.getParameterValues(PARAM_CORRECT_ANSWER);
			for (int i = 0; i < allAnswers.length; i++) correctAnswer += allAnswers[i];
		} catch (Exception e) {
			correctAnswer = request.getParameter(PARAM_CORRECT_ANSWER);
			if (correctAnswer == null) correctAnswer = "";
		}
		String parameterString = request.getParameter(PARAM_PARAMETER_STRING);
		if (parameterString == null) parameterString = "";
		
		q.assignmentType = request.getParameter(PARAM_ASSIGNMENT_TYPE);
		q.topicId = topicId;
		q.type = type;
		q.prompt = prompt;
		q.choices = choices;
		q.requiredPrecision = requiredPrecision;
		q.significantFigures = significantFigures;
		q.correctAnswer = correctAnswer;
		q.units = request.getParameter(PARAM_UNITS);
		q.parameterString = parameterString;
		q.scrambleChoices = Boolean.parseBoolean(request.getParameter(PARAM_SCRAMBLE_CHOICES));
		q.strictSpelling = Boolean.parseBoolean(request.getParameter(PARAM_STRICT_SPELLING));
		return q;
	}
/*	
	String createItem(HttpServletRequest request) throws Exception {
		Question q = assembleQuestion(request);
		ofy().save().entity(q).now();
		JsonArray ja = JsonParser.parseString(request.getParameter("json")).getAsJsonArray();
		ja.remove(0);
		APChemTopic topic = ofy().load().type(APChemTopic.class).id(q.topicId).safe();
		return ja.size()==0?viewQuestions(q.assignmentType,topic.unitId,topic.id):newJsonForm(q.assignmentType,q.topicId,ja);
	}
*/	
	/**
	 * Creates a new Question entity from assembled form data and persists to datastore.
	 * 
	 * Saves the question and either returns to question list view or continues
	 * with bulk import if JSON payload is present.
	 * 
	 * @param request HTTP request with assembled question fields and optional JSON payload
	 * @return HTML response with question list or next bulk import form
	 * @throws Exception if question save or topic lookup fails
	 */
	String createQuestion(HttpServletRequest request) throws Exception { 
		Question q = assembleQuestion(request);
		ofy().save().entity(q).now();
		String json = request.getParameter(PARAM_JSON);
		if (json == null) {
			APChemTopic topic = ofy().load().type(APChemTopic.class).id(q.topicId).safe();
			return viewQuestions(q.assignmentType,topic.unitId,topic.id);
		} else {
			JsonArray ja = JsonParser.parseString(json).getAsJsonArray();
			ja.remove(0);
			return newJsonForm(q.assignmentType,q.topicId,ja);
		}
	}

	/**
	 * Deletes a Question entity from the datastore by ID.
	 * 
	 * @param request HTTP request containing QuestionId parameter
	 * @throws Exception if question ID is invalid or datastore error occurs
	 */
	void deleteQuestion(HttpServletRequest request) throws Exception {
		Long questionId = Long.parseLong(request.getParameter(PARAM_QUESTION_ID));
		ofy().delete().type(Question.class).id(questionId).now();
	}
	
	/**
	 * Generates HTML form for editing an existing question.
	 * 
	 * Displays current question with success rate and provides editor form
	 * with topic selector, question type selector, and question-specific fields.
	 * 
	 * @param questionId unique identifier of question to edit
	 * @return HTML page with question editor form
	 * @throws Exception if question not found in datastore
	 */
	String editQuestion(Long questionId) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head(HEADING_EDITOR));
		
		Question q = ofy().load().type(Question.class).id(questionId).safe();
		
		if (topicMap == null) refreshTopics();
		APChemTopic t = topicMap.get(q.topicId);
		
		if (q.requiresParser()) q.setParameters();
		buf.append("<h1>" + HEADING_EDIT_QUESTION_FORM + "</h1><h2>" + HEADING_CURRENT_QUESTION + "</h2>");
		buf.append(LABEL_TOPIC + ": " + (t==null?"n/a":t.title) + "<br/>");
		
		buf.append("Success Rate: " + q.getSuccess() + "<p>");
		
		buf.append("<FORM Action=/questions METHOD=POST>");
		
		buf.append(q.printAll());
		
		buf.append("<INPUT TYPE=HIDDEN NAME=" + PARAM_QUESTION_ID + " VALUE=" + questionId + " />"
				+ "<input type=hidden name=" + PARAM_ASSIGNMENT_TYPE + " value=" + q.assignmentType + " />");
		buf.append("<INPUT TYPE=SUBMIT NAME=" + USER_REQUEST + " VALUE='" + USER_REQUEST_DELETE_QUESTION + "' />");
		buf.append("<INPUT TYPE=SUBMIT NAME=" + USER_REQUEST + " VALUE='" + USER_REQUEST_QUIT + "' />");
		
		buf.append("<hr><h2>" + HEADING_EDIT_QUESTION_FORM + "</h2>");
		
		buf.append(LABEL_TOPIC + ":" + topicSelectBox(q.topicId) + "<br/>");
		
		buf.append(LABEL_QUESTION_TYPE + ":" + questionTypeDropDownBox(q.type) + "<br/>");
		
		buf.append(q.edit());
		
		buf.append("<INPUT TYPE=SUBMIT NAME=" + USER_REQUEST + " VALUE=" + USER_REQUEST_PREVIEW + " />");
		buf.append("</FORM>");
	
		return buf.toString() + Util.foot();
	}

	/**
	 * Generates HTML form for editing a bulk-imported question from JSON array.
	 * 
	 * Extracts first question from JSON array and prepares for preview/editing.
	 * Used as part of bulk import workflow.
	 * 
	 * @param request HTTP request containing json parameter with JsonArray
	 * @return HTML preview of question, or error message if JSON invalid
	 */
	String editItem(HttpServletRequest request) {
		try {
			String assignmentType = request.getParameter(PARAM_ASSIGNMENT_TYPE);
			Long topicId = Long.parseLong(request.getParameter(PARAM_TOPIC_ID));
			APChemTopic topic = ofy().load().type(APChemTopic.class).id(topicId).safe();
			JsonArray ja = JsonParser.parseString(request.getParameter(PARAM_JSON)).getAsJsonArray();
			if (ja.size()==0) return viewQuestions(assignmentType,topic.unitId,topic.id);
			JsonObject jq = ja.get(0).getAsJsonObject();
			Question q = new Question(jq);
			q.assignmentType = assignmentType;
			q.topicId = topicId;
			return previewQuestion(q,ja);
		} catch (Exception e) {
			return e.getMessage();
		}

	}

	/**
	 * Generates form for importing questions from JSON payload.
	 * 
	 * Extracts JSON from request parameter and delegates to 
	 * {@link #newJsonForm(String, Long, JsonElement)} for processing.
	 * 
	 * @param request HTTP request containing json parameter with JsonElement
	 * @return HTML form with bulk import review
	 * @throws Exception if JSON parsing fails
	 */
	String newJsonForm(HttpServletRequest request) throws Exception {
		String assignmentType = request.getParameter(PARAM_ASSIGNMENT_TYPE);
		Long topicId = Long.parseLong(request.getParameter(PARAM_TOPIC_ID));
		JsonElement je = JsonParser.parseString(request.getParameter(PARAM_JSON).replaceAll("'", "&apos;"));
		return newJsonForm(assignmentType,topicId,je);
	}
	
	/**
	 * Generates form for bulk import of questions from JSON.
	 * 
	 * Handles both single question objects and arrays of questions.
	 * For single object: displays preview and save/edit options.
	 * For array: displays table of questions with checkbox selection for batch operations.
	 * 
	 * Supports operations:
	 * - Save Selected Items: Create all selected questions in datastore
	 * - Remove Selected Items: Delete selected questions from import
	 * - Edit: Modify first question and reprocess
	 * 
	 * @param assignmentType Exercises or Homework
	 * @param topicId Curriculum topic ID for questions
	 * @param je JsonElement (single object or array) from import payload
	 * @return HTML form with import review and batch operations
	 * @throws Exception if topic not found or JSON invalid
	 */
	String newJsonForm(String assignmentType, Long topicId, JsonElement je) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head(HEADING_EDITOR));
		APChemTopic topic = ofy().load().type(APChemTopic.class).id(topicId).safe();
		buf.append("<h1>" + assignmentType + "</h1>"
				+ "<h2>" + topic.title + "</h2>");
		
		if (je.isJsonObject()) {  // submitting a single question
			try {
				JsonObject jq = je.getAsJsonObject();
				Question question = new Question(jq);
				question.topicId = topicId;
				question.assignmentType = assignmentType;
				return previewQuestion(question);
			} catch (Exception e) {
				buf.append(e.getMessage());
			}
		} else if (je.isJsonArray()) {
			try {
				JsonArray ja = je.getAsJsonArray();
				if(ja.size() == 0) return viewQuestions(assignmentType,topic.unitId,topic.id);
				buf.append("<form method=post action=/questions>"
						+ "<input type=hidden name=" + PARAM_ASSIGNMENT_TYPE + " value=" + assignmentType + " />"
						+ "<input type=hidden name=" + PARAM_TOPIC_ID + " value=" + topic.id + " />"
						+ "<input type=hidden name=" + PARAM_JSON + " value='" + ja.toString().replaceAll("'", "&apos;") + "' />"
						+ "<input type=submit class='" + CSS_CLASS_BTN_PRIMARY + "' name=" + USER_REQUEST + " value='" + USER_REQUEST_REMOVE_SELECTED_ITEMS + "' /> "
						+ "<input type=submit class='" + CSS_CLASS_BTN_PRIMARY + "' name=" + USER_REQUEST + " value='" + USER_REQUEST_SAVE_SELECTED_ITEMS + "' /> "
						+ "<a href=/questions?" + PARAM_ASSIGNMENT_TYPE + "=" + assignmentType + "&" + PARAM_TOPIC_ID + "=" + topicId + " class='" + CSS_CLASS_BTN_PRIMARY + "'>Quit</a>");
				buf.append("<table border=1px>");
				for (int i=CHOICE_INDEX_START;i<ja.size();i++) {
					buf.append("<tr>"
						+ "<td><label><input type=checkbox name=" + PARAM_INDEX + " value=" + i + " />&nbsp;Save/Del</label> "
						+ (i==CHOICE_INDEX_START?"<input type=submit class='" + CSS_CLASS_BTN_PRIMARY + "' name=" + USER_REQUEST + " value=" + USER_REQUEST_EDIT + " />":"") + "</td>");
					JsonObject jq = ja.get(i).getAsJsonObject();
					Question question = new Question(jq);
					buf.append("<td>" + question.printAll() + "</td>");
					buf.append("</tr>");				
				}
				buf.append("</table></form>");
			} catch (Exception e) {
				buf.append(e.getMessage());	
			}
		}
		return buf.toString();
	}
	
	/**
	 * Generates form for creating a new question of specified type.
	 * 
	 * Displays question type-specific instructions and edit form.
	 * If question type not specified in request, displays type selector.
	 * 
	 * Question Types:
	 * - multiple_choice: Select single best answer from 5 options
	 * - true_false: Affirmative statement with true/false answer
	 * - checkbox: Select all correct answers from 5 options
	 * - fill_in_blank: Short answer with comma-separated alternatives
	 * - numeric: Numeric value with precision/significant figures tolerance
	 * - essay: Free-form student response
	 * 
	 * @param request HTTP request with AssignmentType, TopicId, and optional QuestionType
	 * @return HTML form for new question creation with type-specific instructions
	 */
	String newQuestionForm(HttpServletRequest request) {
		StringBuffer buf = new StringBuffer(Util.head(HEADING_EDITOR));
		
		buf.append("<h1>" + HEADING_EDITOR + "</h1><h2>" + HEADING_NEW_QUESTION + "</h2>");
		Long topicId = null;
		try {
			topicId = Long.parseLong(request.getParameter(PARAM_TOPIC_ID));
		} catch (Exception e) {}		
		APChemTopic topic = ofy().load().type(APChemTopic.class).id(topicId).safe();
		
		String questionType = null;;		
		try {
			questionType = request.getParameter(PARAM_QUESTION_TYPE);
			switch (questionType) {
			case QUESTION_TYPE_MULTIPLE_CHOICE: 
				buf.append("<h3>" + DESC_MULTIPLE_CHOICE + "</h3>");
				buf.append(HELP_MULTIPLE_CHOICE); 
				break;
			case QUESTION_TYPE_TRUE_FALSE: 
				buf.append("<h3>" + DESC_TRUE_FALSE + "</h3>");
				buf.append(HELP_TRUE_FALSE); 
				break;
			case QUESTION_TYPE_CHECKBOX: 
				buf.append("<h3>" + DESC_CHECKBOX + "</h3>");
				buf.append(HELP_CHECKBOX); 
				break;
			case QUESTION_TYPE_FILL_IN_BLANK: 
				buf.append("<h3>" + DESC_FILL_IN_BLANK + "</h3>");
				buf.append(HELP_FILL_IN_BLANK); 
				break;
			case QUESTION_TYPE_NUMERIC: 
				buf.append("<h3>" + DESC_NUMERIC + "</h3>");
				buf.append(HELP_NUMERIC); 
				break;
			case QUESTION_TYPE_ESSAY: 
				buf.append("<h3>" + DESC_ESSAY + "</h3>");
				buf.append(HELP_ESSAY); 
				break;
			default: 
				buf.append(ERROR_UNEXPECTED_QUESTION_TYPE);
			}
			Question question = new Question(questionType);
			buf.append("<p><FORM METHOD=POST ACTION=/questions>");
			buf.append("<input type=hidden name=" + PARAM_ASSIGNMENT_TYPE + " value=" + request.getParameter(PARAM_ASSIGNMENT_TYPE) + " />"
					+ "<INPUT TYPE=HIDDEN NAME=" + PARAM_QUESTION_TYPE + " VALUE=" + questionType + " />");
			buf.append(LABEL_TOPIC + ": " + topic.title + "<br>"
					+ "<input type=hidden name=" + PARAM_TOPIC_ID + " value=" + topic.id + " />");
			
			buf.append(question.edit());
			buf.append("<INPUT TYPE=SUBMIT NAME=" + USER_REQUEST + " VALUE='" + USER_REQUEST_PREVIEW + "'></FORM>");
		} catch (Exception e) {
			buf.append(LABEL_TOPIC + ": " + topic.title);
			buf.append("<form method=get>"
					+ "<input type=hidden name=" + USER_REQUEST + " value='" + USER_REQUEST_NEW_QUESTION + "' />"
					+ "<input type=hidden name=" + PARAM_ASSIGNMENT_TYPE + " value=" + request.getParameter(PARAM_ASSIGNMENT_TYPE) + " />"
					+ "<input type=hidden name=" + PARAM_TOPIC_ID + " value=" + topicId + " />"
					+ "Select a question type: " + questionTypeDropDownBox("")
					+ "<input type=submit value=Go /></form>");
			}
		return buf.toString() + Util.foot();
	}

	/**
	 * Generates preview of question being edited from HTTP request parameters.
	 * 
	 * Assembles question from form submission and displays preview with options to:
	 * - Update existing question (if QuestionId present)
	 * - Activate proposed question (if ProposedQuestionId present)
	 * - Save as new question (otherwise)
	 * 
	 * @param request HTTP request with assembled question fields
	 * @return HTML preview page with save options
	 */
	String previewQuestion(HttpServletRequest request) {
		StringBuffer buf = new StringBuffer(Util.head(HEADING_EDITOR));
		try {
			String assignmentType = request.getParameter(PARAM_ASSIGNMENT_TYPE);
			long questionId = 0;
			boolean current = false;
			boolean proposed = false;
			try {
				questionId = Long.parseLong(request.getParameter(PARAM_QUESTION_ID));
				current = true;
			} catch (Exception e2) {}
			long proposedQuestionId = 0;
			try {
				proposedQuestionId = Long.parseLong(request.getParameter(PARAM_PROPOSED_QUESTION_ID));
				proposed = true;
				current = false;
			} catch (Exception e2) {}
			
			Long topicId = null;
			try {
				topicId = Long.parseLong(request.getParameter(PARAM_TOPIC_ID));
			} catch (Exception e) {}
			
			Question q = assembleQuestion(request);
			if (q.requiresParser()) q.setParameters();
			
			buf.append("<h1>" + HEADING_EDITOR + "</h1><h2>" + HEADING_PREVIEW_QUESTION + "</h2>");
			
			APChemTopic t = (topicId==null?null:ofy().load().type(APChemTopic.class).id(topicId).now());
			buf.append(LABEL_TOPIC + ": " + (t==null?"n/a":t.title) + "<br/>");
			
			buf.append("<FORM ACTION=/questions METHOD=POST>");
			
			buf.append(q.printAll());
			
			if (current) {
				buf.append("<INPUT TYPE=HIDDEN NAME=" + PARAM_QUESTION_ID + " VALUE=" + questionId + ">");
				buf.append("<INPUT TYPE=SUBMIT NAME=" + USER_REQUEST + " VALUE='" + USER_REQUEST_UPDATE_QUESTION + "'>");
			}
			if (proposed) {
				buf.append("<INPUT TYPE=HIDDEN NAME=" + PARAM_PROPOSED_QUESTION_ID + " VALUE=" + proposedQuestionId + ">");
				buf.append("<INPUT TYPE=SUBMIT NAME=" + USER_REQUEST + " VALUE='Activate This Question'>");
			} else buf.append("<INPUT TYPE=SUBMIT NAME=" + USER_REQUEST + " VALUE='" + USER_REQUEST_SAVE_NEW_QUESTION + "'>");
			
			buf.append("<INPUT TYPE=SUBMIT NAME=" + USER_REQUEST + " VALUE='" + USER_REQUEST_QUIT + "'>");
			
			buf.append("<hr><h2>" + HEADING_EDIT_QUESTION_FORM + "</h2>");
			buf.append(PARAM_ASSIGNMENT_TYPE + ": " + assignmentType + "<br/>"
					+ "<input type=hidden name=" + PARAM_ASSIGNMENT_TYPE + " value=" + assignmentType + " />");
			buf.append(LABEL_TOPIC + ":" + topicSelectBox(topicId) + "<br/>");
			buf.append(LABEL_QUESTION_TYPE + ":" + questionTypeDropDownBox(q.type) + "<br/>");
			buf.append(q.edit());
			
			buf.append("<INPUT TYPE=SUBMIT NAME=" + USER_REQUEST + " VALUE=" + USER_REQUEST_PREVIEW + ">");
			buf.append("</FORM>");
		} catch (Exception e) {
			buf.append(e.toString());
		}
		return buf.toString() + Util.foot();
	}

	/**
	 * Generates preview of new question from Question entity (not from request).
	 * 
	 * @param q Question entity to preview
	 * @return HTML preview page
	 */
	String previewQuestion(Question q) {
		return previewQuestion(q,null);
	}
	
	/**
	 * Generates preview of question from JSON bulk import with array of remaining questions.
	 * 
	 * Assembles question from request parameters and passes question array
	 * for continuation of bulk import workflow.
	 * 
	 * @param request HTTP request with assembled question fields
	 * @param json String containing JSON array of remaining questions
	 * @return HTML preview with save option for bulk import
	 */
	String previewQuestion (HttpServletRequest request, String json) {
		Question q = assembleQuestion(request);
		JsonArray ja = JsonParser.parseString(request.getParameter(PARAM_JSON)).getAsJsonArray();
		return previewQuestion(q,ja);
	}
	
	/**
	 * Generates preview of question with optional JSON array for bulk import continuation.
	 * 
	 * Displays question preview with option to save and continue with bulk import.
	 * If array is null, displays standalone preview for single question.
	 * 
	 * @param q Question entity to preview
	 * @param ja JsonArray of remaining questions from bulk import (null for standalone)
	 * @return HTML preview page with save options
	 */
	String previewQuestion(Question q, JsonArray ja) {
	StringBuffer buf = new StringBuffer(Util.head(HEADING_EDITOR));
		try {
			if (q.requiresParser()) q.setParameters();
			
			buf.append("<h1>" + HEADING_EDITOR + "</h1><h2>" + HEADING_PREVIEW_QUESTION + "</h2>");
			
			APChemTopic t = ofy().load().type(APChemTopic.class).id(q.topicId).now();
			buf.append(LABEL_TOPIC + ": " + (t==null?"n/a":t.title) + "<br/>");
			
			buf.append("<FORM ACTION=/questions METHOD=POST>");
			
			buf.append(q.printAll());
			
			if (ja != null) {
				buf.append("<span id=" + ELEMENT_ID_FAKE + "></span>");
				buf.append("<input type=hidden name=" + PARAM_JSON + " value='" + ja.toString().replaceAll("'", "&apos;") + "' />");
			}
			
			buf.append("<INPUT TYPE=SUBMIT NAME=" + USER_REQUEST + " VALUE='" + USER_REQUEST_SAVE_NEW_QUESTION + "' />");
			buf.append("<INPUT TYPE=SUBMIT NAME=" + USER_REQUEST + " VALUE='" + USER_REQUEST_QUIT + "' />");
			
			buf.append("<hr><h2>" + HEADING_EDIT_QUESTION_FORM + "</h2>");
			buf.append(PARAM_ASSIGNMENT_TYPE + ": " + q.assignmentType + "<br/>"
					+ "<input type=hidden name=" + PARAM_ASSIGNMENT_TYPE + " value=" + q.assignmentType + " />"
					+ "<input type=hidden name=" + PARAM_TOPIC_ID + " value=" + q.topicId + " />"
					+ "<input type=hidden name=" + PARAM_INDEX + " value=" + ZERO + " />");
			
			buf.append(LABEL_TOPIC + ":" + topicSelectBox(q.topicId) + "<br/>");
			buf.append(LABEL_QUESTION_TYPE + ":" + questionTypeDropDownBox(q.type) + "<br/>");
			buf.append(q.edit());
			
			buf.append("<INPUT TYPE=SUBMIT NAME=" + USER_REQUEST + " VALUE=" + USER_REQUEST_PREVIEW + ">");
			buf.append("</FORM>");
		} catch (Exception e) {
			buf.append(e.toString());
		}
		return buf.toString() + Util.foot();
	}

	/**
	 * Generates HTML <select> dropdown for question type selection.
	 * 
	 * Lists all supported question types with label and value.
	 * Marks specified type as SELECTED if provided.
	 * 
	 * @param questionType Currently selected question type (can be null or empty)
	 * @return HTML <select> element with question type options
	 */
	String questionTypeDropDownBox(String questionType) {
		StringBuffer buf = new StringBuffer();
		buf.append("\n<SELECT NAME=" + PARAM_QUESTION_TYPE + "><option>Select a question type</option>"
				+ "<OPTION VALUE=" + QUESTION_TYPE_MULTIPLE_CHOICE + (QUESTION_TYPE_MULTIPLE_CHOICE.equals(questionType)?" SELECTED>":">") + "Multiple Choice</OPTION>"
				+ "<OPTION VALUE=" + QUESTION_TYPE_TRUE_FALSE + (QUESTION_TYPE_TRUE_FALSE.equals(questionType)?" SELECTED>":">") + "True/False</OPTION>"
				+ "<OPTION VALUE=" + QUESTION_TYPE_CHECKBOX + (QUESTION_TYPE_CHECKBOX.equals(questionType)?" SELECTED>":">") + "Checkbox</OPTION>"
				+ "<OPTION VALUE=" + QUESTION_TYPE_FILL_IN_BLANK + (QUESTION_TYPE_FILL_IN_BLANK.equals(questionType)?" SELECTED>":">") + "Fill in Blank</OPTION>"
				+ "<OPTION VALUE=" + QUESTION_TYPE_NUMERIC + (QUESTION_TYPE_NUMERIC.equals(questionType)?" SELECTED>":">") + "Numeric</OPTION>"
				+ "<OPTION VALUE=" + QUESTION_TYPE_ESSAY + (QUESTION_TYPE_ESSAY.equals(questionType)?" SELECTED>":">") + "Essay</OPTION>"
				+ "</SELECT>");
		return buf.toString();
	}
	
/********* temporary utility for Microsoft Partnership *************************

	JsonArray sampleQuestions(int n) {
		JsonArray questions = new JsonArray();
		List<Key<Question>> keys = ofy().load().type(Question.class).keys().list();
		Map<Long,APChemTopic> concepts = getConcepts();
		Random random = new Random();
		while (questions.size() < n) {
			int i = random.nextInt(keys.size());  // choose a random position in the List
			Key<Question> k = keys.remove(i);     // remove it from the List
			Question q = ofy().load().key(k).now();  // get the Question entity
			
			if (q.topicId==null || concepts.get(q.topicId)==null) continue;  // must be authentic concept
			JsonObject question = new JsonObject();
			JsonArray choices = new JsonArray();
			question.addProperty("concept", concepts.get(q.topicId).title);
			switch (q.type) {
			case "multiple_choice":
				question.addProperty("question_type", "multiple_choice");
				question.addProperty("text",q.prompt);
				for (String choice:q.choices) choices.add(choice);
				question.add("choices", choices);
				question.addProperty("correct_answer", q.correctAnswer);
				break;
			case "true_false":
				question.addProperty("question_type", "true_false");
				question.addProperty("text",q.prompt);
				question.addProperty("correct_answer", q.correctAnswer);
				break;
			case "checkbox":
				question.addProperty("question_type", "checkbox");
				question.addProperty("text",q.prompt);
				for (String choice:q.choices) choices.add(choice);
				question.add("choices", choices);
				question.addProperty("correct_answer", q.correctAnswer);
				break;
			case "fill_in_blank":
				question.addProperty("question_type", "fill_in_blank");
				question.addProperty("text",q.prompt);
				question.addProperty("correct_answer", q.getCorrectAnswer());
				break;
			case "numeric":
				q.setParameters();
				question.addProperty("question_type", "numeric");
				question.addProperty("text",q.parseString(q.prompt));
				question.addProperty("correct_answer", q.getCorrectAnswer());
				if (q.units!=null) question.addProperty("units", q.parseString(q.units));
				break;
			default: continue;
			}		
			questions.add(question);
		}
		return questions;
	}
	
	Map<Long,APChemTopic> getConcepts() {
		Map<Long,APChemTopic> conceptMap = new HashMap<Long,APChemTopic>();
		List<APChemTopic> conceptList = ofy().load().type(APChemTopic.class).list();
		for (APChemTopic c : conceptList) conceptMap.put(c.id, c);
		return conceptMap;
	}
**********************************************************************************/

String saveItems(HttpServletRequest request) {
		try {
			String assignmentType = request.getParameter(PARAM_ASSIGNMENT_TYPE);
			Long topicId = Long.parseLong(request.getParameter(PARAM_TOPIC_ID));
			APChemTopic topic = ofy().load().type(APChemTopic.class).id(topicId).safe();
			JsonArray ja = JsonParser.parseString(request.getParameter(PARAM_JSON)).getAsJsonArray();
			String[] index = request.getParameterValues(PARAM_INDEX);
			if (ja.size()==0 || index.length == 0) return viewQuestions(assignmentType,topic.unitId,topic.id);
			JsonArray itemsToBeSaved = new JsonArray();
			for (String i : index)  itemsToBeSaved.add(ja.get(Integer.valueOf(i)));
			for (JsonElement item : itemsToBeSaved) {
				ja.remove(item);
				Question q = new Question(item.getAsJsonObject());
				q.assignmentType = assignmentType;
				q.topicId = topicId;
				ofy().save().entity(q).now();
			}
			return ja.size()==0?viewQuestions(assignmentType,topic.unitId,topic.id):newJsonForm(assignmentType,topicId,ja);
		} catch (Exception e) {
			return e.getMessage();
		}
	}
	
	String removeItems(HttpServletRequest request) {
		try {
			String assignmentType = request.getParameter(PARAM_ASSIGNMENT_TYPE);
			Long topicId = Long.parseLong(request.getParameter(PARAM_TOPIC_ID));
			APChemTopic topic = ofy().load().type(APChemTopic.class).id(topicId).safe();
			JsonArray ja = JsonParser.parseString(request.getParameter(PARAM_JSON)).getAsJsonArray();
			String[] index = request.getParameterValues(PARAM_INDEX);
			if (ja.size()==0 || index.length == 0) return viewQuestions(assignmentType,topic.unitId,topic.id);
			JsonArray itemsToBeRemoved = new JsonArray();
			for (String i : index)  itemsToBeRemoved.add(ja.get(Integer.valueOf(i)));
			for (JsonElement item : itemsToBeRemoved) ja.remove(item);
			return newJsonForm(assignmentType,topicId,ja);
		} catch (Exception e) {
			return e.getMessage();
		}
	}
	
	/**
	 * Refreshes in-memory cache of curriculum units and topics from datastore.
	 * 
	 * Called before displaying question lists to ensure latest curriculum structure.
	 * Loads all units (ordered by unitNumber) and all topics into memory with HashMap
	 * for efficient lookups.
	 * 
	 * @see #unitList
	 * @see #topicList
	 * @see #topicMap
	 */
	void refreshTopics() {
		unitList = ofy().load().type(APChemUnit.class).order("unitNumber").list();
		topicList = ofy().load().type(APChemTopic.class).list();
		topicMap = new HashMap<Long,APChemTopic>();
		for (APChemTopic t : topicList) topicMap.put(t.id, t);
	}

	/**
	 * Generates HTML <select> dropdown for topic selection.
	 * 
	 * Lists all topics from curriculum with option value and display text.
	 * Marks specified topic as selected if provided.
	 * Refreshes topic list from cache or datastore if not yet loaded.
	 * 
	 * @param topicId Currently selected topic ID (can be null)
	 * @return HTML <select> element with topic options
	 */
	String topicSelectBox(Long topicId) {
		StringBuffer buf = new StringBuffer("<select name=" + PARAM_TOPIC_ID + ">");
		if (topicList == null) refreshTopics();
		for (APChemTopic t : topicList) buf.append("<option value=" + t.id + (t.id.equals(topicId)?" selected>":">") + t.title + "</option>");
		buf.append("</select>");
		return buf.toString();
	}

	/**
	 * Updates an existing Question entity from HTTP request form data.
	 * 
	 * Loads question from datastore, reassembles with new form values,
	 * and saves back to datastore. Silently fails if question not found.
	 * 
	 * @param request HTTP request containing QuestionId and updated question fields
	 */
	void updateQuestion(HttpServletRequest request) {
		long questionId = 0;
		try {
			questionId = Long.parseLong(request.getParameter(PARAM_QUESTION_ID));	
			Question q = ofy().load().type(Question.class).id(questionId).safe();
			q = assembleQuestion(request,q);
			ofy().save().entity(q).now();
		} catch (Exception e) {
			return;
		}
	}

	/**
	 * Displays list of questions for assignment type and topic with editing options.
	 * 
	 * Primary view for question management. Displays:
	 * - Assignment type selector (Exercises or Homework)
	 * - Unit dropdown (filters available topics)
	 * - Topic dropdown (filters displayed questions)
	 * - Question table with question types summary (MC, TF, FB, etc.)
	 * - Individual question edit buttons
	 * - New question and JSON import options
	 * 
	 * @param assignmentType Exercises or Homework (null to show type selector)
	 * @param unitId Unit ID to select (optional, shows all units if null)
	 * @param topicId Topic ID to display questions (optional)
	 * @return HTML page with question list and management options
	 * @throws Exception if unit or topic not found in datastore
	 */
	String viewQuestions(String assignmentType,Long unitId, Long topicId) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head(HEADING_EDITOR));

		buf.append("<h1>" + HEADING_MANAGE_QUESTIONS + "</h1>");

		//if (topicMap == null) refreshTopics();
		refreshTopics();	
		buf.append("<h2>" + HEADING_QUESTION_ITEMS + "</h2>");
		APChemUnit unit = null;
		APChemTopic topic = null;
		
		/********* temporary utility for Microsoft Partnership *************************

		// Temporary section to extract questions as JsonArray
		buf.append("<p><form method=get>"
				+ "Get <input type=text size=3 name=n /> questions as Json array "
				+ "<input type=submit name=UserRequest value='Get Json' />"
				+ "</form>");
		***************************************************************************************/
		
		buf.append("<form><input type=hidden name=" + USER_REQUEST + " value=" + USER_REQUEST_VIEW_QUESTIONS + " />");
		
		if (assignmentType == null) {
			buf.append("<label><input type=radio required name=" + PARAM_ASSIGNMENT_TYPE + " value=" + ASSIGNMENT_TYPE_EXERCISES + " onClick=submit() /> " + LABEL_ASSIGNMENT_EXERCISES + "</label><br/>"
					+ " <label><input type=radio required name=" + PARAM_ASSIGNMENT_TYPE + " value=" + ASSIGNMENT_TYPE_HOMEWORK + " onClick=submit() /> " + LABEL_ASSIGNMENT_HOMEWORK + "</label>");
		} else {
			buf.append("<h3>" + LABEL_ASSIGNMENT_EXERCISES + "</h3>"
					+ "<input type=hidden name=" + PARAM_ASSIGNMENT_TYPE + " value=" + assignmentType + " />");

			// Make a drop-down selector for units
			unit = unitId==null?null:ofy().load().type(APChemUnit.class).id(unitId).now();
			buf.append("<select name=" + PARAM_UNIT_ID + " onchange=submit()><option>Select a Unit</option>");
			for (APChemUnit u : unitList) buf.append("<option value=" + u.id + (u.equals(unit)? " selected":"") + ">" + u.title + "</option>");
			buf.append("</select>&nbsp;");

			if (unitId !=null) {
				topicList = ofy().load().type(APChemTopic.class).filter("unitId",unit.id).order("topicNumber").list();
				// Make a drop-down selector for topics
				topic = topicId==null?null:ofy().load().type(APChemTopic.class).id(topicId).now();
				buf.append("<select name=" + PARAM_TOPIC_ID + " onchange=submit()><option>Select a topic</option>");
				for (APChemTopic t : topicList) buf.append("<option value=" + t.id + (t.equals(topic)? " selected":"") + ">" + t.title + "</option>");
				buf.append("</select>");
			}
		}
		buf.append("</form>");

		if (unit != null && topic != null) {  // display the questions
			
			buf.append("<a href=/questions?" + USER_REQUEST + "=" + USER_REQUEST_NEW_QUESTION + "&" + PARAM_ASSIGNMENT_TYPE + "=" + assignmentType + "&" + PARAM_TOPIC_ID + "=" + topic.id + ">Create a New Question</a> ");	
			buf.append("<form method=get style='display: inline;'>"
					+ "<input type=hidden name=" + USER_REQUEST + " value='" + USER_REQUEST_NEW_JSON + "' />"
					+ "<input type=hidden name=" + PARAM_ASSIGNMENT_TYPE + " value=" + assignmentType + " />"
					+ "<input type=hidden name=" + PARAM_TOPIC_ID + " value=" + topicId + " />"
					+ "or paste a JSON string (object or array) here: <input type=text name=" + PARAM_JSON + " /> "
					+ "<input type=submit class='" + CSS_CLASS_BTN_PRIMARY + "' name=JsonType /> "
					+ "</form><br/>");
			
			List<Question> questions = ofy().load().type(Question.class).filter(PARAM_ASSIGNMENT_TYPE,assignmentType).filter(PARAM_TOPIC_ID,topicId).list();
			buf.append("This topic has " + questions.size() + " question items: ");
			
			// start a new StringBuffer for the table of questions
			StringBuffer qBuf = new StringBuffer();
			qBuf.append("<table>");
			
			// Count the number of question of each type:
			int tf = 0;
			int mc = 0;
			int fb = 0;
			int cb = 0;
			int nu = 0;
			
			for (Question q : questions) {
				switch (q.type) {
				case QUESTION_TYPE_TRUE_FALSE: tf++; break;
				case QUESTION_TYPE_MULTIPLE_CHOICE: mc++; break;
				case QUESTION_TYPE_FILL_IN_BLANK: fb++; break;
				case QUESTION_TYPE_CHECKBOX: cb++; break;
				case QUESTION_TYPE_NUMERIC: nu++; break;
				}
				
				q.setParameters();
				qBuf.append("<tr>"
					+ "<td style='vertical-align: top;'>"
					+ "  <form method=get action=/questions>"
					+ "    <input type=hidden name=" + PARAM_QUESTION_ID + " value=" + q.id + " />"
					+ "    <input type=hidden name=" + USER_REQUEST + " value=" + USER_REQUEST_EDIT_QUESTION + " /><br/>"
					+ "    <input type=submit value=Edit />"
					+ "  </form>"
					+ "</td>"
					+ "<td>" + q.printAll() + "</td>"
					+ "</tr>");
			}
			qBuf.append("</table>");
			
			buf.append(TOPIC_COUNT_TF + tf + " " + TOPIC_COUNT_MC + mc + " " + TOPIC_COUNT_FB + fb + " " + TOPIC_COUNT_CB + cb + " " + TOPIC_COUNT_NU + nu + "<br/><br/>");
			buf.append(qBuf);
		}
		return buf.toString() + Util.foot();
	}
	
	class SortByPctSuccess implements Comparator<Question> {
		public int compare(Question q1,Question q2) {
			int rank = q2.getPctSuccess() - q1.getPctSuccess(); // sort more successful questions first
			if (rank==0) rank = q1.id.compareTo(q2.id); // tie breaker			
			return rank;  
		}
	}
}
