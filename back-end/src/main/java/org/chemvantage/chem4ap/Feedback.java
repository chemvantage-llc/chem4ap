package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Feedback servlet for submitting and viewing user problem reports.
 * Routes:
 * GET /feedback?sig=...&UserRequest=ViewFeedback - View all submitted feedback (admin only)
 * GET /feedback?sig=...&questionId=...&parameter=...&studentAnswer=... - Display feedback form
 * POST /feedback with UserRequest=Delete Report - Delete report (admin only)
 * POST /feedback with Comment - Submit new problem report
 */
@WebServlet("/feedback")
public class Feedback extends HttpServlet {

	private static final long serialVersionUID = 1L;
	
	@Override
	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html; charset=UTF-8");

		try {
			String sig = request.getParameter("sig");
			if (sig == null || sig.isEmpty()) {
				response.sendRedirect("/");
				return;
			}
			User user = User.getUser(sig);
			if (user == null) {
				response.sendRedirect("/");
				return;
			}
			String userRequest = request.getParameter("UserRequest");
			if (userRequest==null) userRequest = "";
			
			switch (userRequest) {
			case "ViewFeedback":
				out.println(viewUserReports(user));
				break;
			default:
				out.println(feedbackForm(user,request));						
			}
		} catch (Exception e) {
			out.println("Error: " + e.getMessage());
		}
	}
	
	@Override
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html; charset=UTF-8");

		String sig = request.getParameter("sig");
		if (sig == null || sig.isEmpty()) {
			response.sendError(401, "Unauthorized: missing sig parameter");
			return;
		}
		User user = User.getUser(sig);
		if (user == null) {
			response.sendError(401, "Unauthorized: invalid sig");
			return;
		}

		String userRequest = request.getParameter("UserRequest");
		if (userRequest == null) userRequest = "";
		
		if ("Delete Report".equals(userRequest)) {
			if (!user.isChemVantageAdmin()) {
				response.sendError(401, "Unauthorized: admin required");
				return;
			}
			try {
				String reportIdStr = request.getParameter("ReportId");
				if (reportIdStr == null || reportIdStr.isEmpty()) {
					out.println("Error: ReportId parameter required");
					return;
				}
				Long reportId = Long.parseLong(reportIdStr);
				ofy().delete().type(UserReport.class).id(reportId).now();
				out.println(viewUserReports(user));
			} catch (NumberFormatException e) {
				out.println("Error: Invalid ReportId format");
			}
			return;
		}
		
		try {	
			createUserReport(user,request);

			out.println(Util.head("Feedback") + Util.banner
					+ "<h1>Thank you for your feedback</h1>"
					+ "An editor will review your comment."
					+ "\n<script>"
					+ "setTimeout(() => {window.close();}, '3000');"
					+ "</script>"
					+ Util.foot());
		} catch (Exception e) {
			out.println(e.getMessage());
		}
	}
	
	/**
	 * Creates a new user problem report and notifies administrator.
	 * Attempts to retrieve user's email address from LMS or local account.
	 *
	 * @param user the user submitting the report (cannot be null)
	 * @param request HTTP request containing QuestionId, Parameter, Comment, StudentAnswer
	 */
	void createUserReport(User user, HttpServletRequest request) {
		if (user == null) {
			throw new IllegalArgumentException("User cannot be null");
		}
		
		try {
			String questionIdStr = request.getParameter("QuestionId");
			if (questionIdStr == null || questionIdStr.isEmpty()) {
				throw new IllegalArgumentException("QuestionId parameter required");
			}
			Long questionId = Long.parseLong(questionIdStr);
			
			String p = request.getParameter("Parameter");
			Integer parameter = null;
			if (p != null && !p.isEmpty()) {
				try {
					parameter = Integer.parseInt(p);
				} catch (NumberFormatException e) {
					// parameter is optional, ignore parse errors
				}
			}
			
			String comment = request.getParameter("Comment");
			String studentAnswer = request.getParameter("StudentAnswer");
			if (studentAnswer == null) studentAnswer = "";
			
			UserReport r = new UserReport(user.hashedId, questionId, parameter, studentAnswer, comment);
			ofy().save().entity(r);
			
			String email = user.hashedId;
			try {
				if (user.platformId != null && user.platformId.equals(Util.getServerUrl())) {
					// Independent user - use local email
					email = user.getId();
				} else {
					// LTI user - retrieve email from LMS
					Long assignmentId = user.getAssignmentId();
					if (assignmentId != null) {
						Assignment a = ofy().load().type(Assignment.class).id(assignmentId).safe();
						if (a != null) {
							Map<String,String[]> contextMembership = LTIMessage.getMembership(a);
							if (contextMembership != null) {
								String userId = user.getId();
								if (userId != null) {
									String rawId = userId.substring(userId.lastIndexOf("/") + 1);
									String[] memberData = contextMembership.get(rawId);
									if (memberData != null && memberData.length > 2) {
										email = memberData[2];
									}
								}
							}
						}
					}
				}
			} catch (Exception e) {
				// Failed to retrieve email from LMS, use fallback
			}
			
			String msg = "On " + r.submitted + " a problem report was submitted by " + email + "<br/>" + r.view();
			try {
				Util.sendEmail("ChemVantage", "admin@chemvantage.org", "User Report", msg);
			} catch (Exception e) {
				// Email notification failed, but report was saved
			}
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("QuestionId must be a valid number");
		}
	}
	
/**
	 * Builds the feedback form for users to report problem questions.
	 * Displays the question, user's answer, and relevant hints for the question type.
	 *
	 * @param user the user (cannot be null)
	 * @param request HTTP request containing questionId, parameter (optional), studentAnswer
	 * @return HTML form for feedback submission
	 * @throws Exception if question not found or invalid parameters
	 */
	String feedbackForm(User user, HttpServletRequest request) throws Exception {
		if (user == null) {
			throw new IllegalArgumentException("User cannot be null");
		}
		
		String questionIdStr = request.getParameter("questionId");
		if (questionIdStr == null || questionIdStr.isEmpty()) {
			throw new IllegalArgumentException("questionId parameter required");
		}
		
		Long questionId = Long.parseLong(questionIdStr);
		Question q = ofy().load().type(Question.class).id(questionId).safe();
		if (q == null) {
			throw new Exception("Question not found: " + questionId);
		}
		
		Integer parameter = null;
		if (q.requiresParser()) {
			String paramStr = request.getParameter("parameter");
			if (paramStr != null && !paramStr.isEmpty()) {
				try {
					parameter = Integer.parseInt(paramStr);
					q.setParameters(parameter);
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("parameter must be a valid integer");
				}
			}
		}
		
		String studentAnswer = request.getParameter("studentAnswer");
		if (studentAnswer == null) studentAnswer = "";
		
		StringBuilder buf = new StringBuilder(Util.head("Feedback")).append(Util.banner)
			.append("<h1>Report a Problem</h1>")
			.append("At Chem4AP, we strive to ensure that every question item is clear, and every ")
			.append("answer is scored correctly. However, with thousands of questions, sometimes a problem can ")
			.append("arise. We welcome feedback from our users because this helps to ensure the high ")
			.append("quality of the app. Thank you in advance for your comments below.<br/><br/>")
			.append("<h2>Question Item</h2>").append(q.printAll())
			.append("Your answer was: ");
		
		// Append answer with type-specific reminder
		if (q.type != null) {
			switch (q.type) {
			case "multiple_choice":
				if (studentAnswer.length() > 0) {
					int choiceIndex = studentAnswer.charAt(0) - 'a';
					if (choiceIndex >= 0 && choiceIndex < q.choices.size()) {
						buf.append("<b>").append(q.choices.get(choiceIndex)).append("</b>");
					}
				}
				buf.append("<br/><br/>Reminder: The correct answer is shown in bold print above.");
				break;
			case "true_false":
				buf.append("<b>").append(studentAnswer).append("</b><br/><br/>")
					.append("Reminder: The correct answer is shown in bold print above.");
				break;
			case "checkbox":
				buf.append("<ul>");
				for (int i = 0; i < studentAnswer.length(); i++) {
					int choiceIndex = studentAnswer.charAt(i) - 'a';
					if (choiceIndex >= 0 && choiceIndex < q.choices.size()) {
						buf.append("<li><b>").append(q.choices.get(choiceIndex)).append("</b></li>");
					}
				}
				buf.append("</ul>")
					.append("Reminder: The correct answers are shown in bold print above. You must select all of them.");
				break;
			case "fill_in_blank":
				buf.append("<b>").append(studentAnswer).append("</b><br/><br/>")
					.append("Reminder: The correct answer will always form a complete, grammatically correct sentence.");
				break;
			case "numeric":
				buf.append("<b>").append(studentAnswer).append("</b><br/><br/>");
				int numericType = q.getNumericItemType();
				switch (numericType) {
				case 0:
					buf.append("Reminder: Your answer must have exactly the same value as the correct answer.");
					break;
				case 1:
					buf.append("Reminder: Your answer must have exactly the same value as the correct answer and must have ")
						.append(q.significantFigures).append(" significant figures.");
					break;
				case 2:
					buf.append("Reminder: Your answer must be within ").append(q.requiredPrecision)
						.append("% of the correct answer.");
					break;
				case 3:
					buf.append("Reminder: Your answer must have ").append(q.significantFigures)
						.append(" significant figures and be within ").append(q.requiredPrecision)
						.append("% of the correct answer.");
					break;
				default:
				}
				break;
			default:
			}
		}
		
		buf.append("<form method=post action=/feedback>")
			.append("<input type=hidden name=sig value=").append(user.getTokenSignature()).append(" />");
		if (parameter != null) {
			buf.append("<input type=hidden name=Parameter value=").append(parameter).append(" />");
		}
		buf.append("<input type=hidden name=QuestionId value=").append(q.id).append(" />")
			.append("<input type=hidden name=StudentAnswer value='").append(studentAnswer).append("' />")
			.append("Your Comment: <INPUT TYPE=TEXT SIZE=80 NAME=Comment /><br/><br/>")
			.append("<input type=submit class='btn btn-primary' value='Submit Feedback' />&nbsp;&nbsp;")
			.append("<button class='btn btn-primary' onClick='window.close();'>Never Mind</button>")
			.append("</form>");
		
		return buf.toString() + Util.foot();
	}
	
	/**
	 * Displays all user feedback reports (admin only).
	 * Shows submitted problem reports with options to edit the related question or delete the report.
	 *
	 * @param user the user (must be admin, cannot be null)
	 * @return HTML page with user feedback reports or unauthorized message
	 */
	String viewUserReports(User user) {
		if (user == null) {
			return "<h1>Unauthorized</h1>";
		}
		if (!user.isChemVantageAdmin()) {
			return "<h1>Unauthorized</h1>";
		}
		
		StringBuilder buf = new StringBuilder(Util.head("User Feedback")).append(Util.banner)
			.append("<h1>User Feedback</h1>");
		
		List<UserReport> reports = ofy().load().type(UserReport.class).list();
		if (reports == null || reports.isEmpty()) {
			buf.append("There are no new user reports.");
		} else {
			for (UserReport r : reports) {
				if (r != null) {
					buf.append("On ").append(r.submitted).append(" user ").append(r.userId)
						.append(" commented:<br/>").append(r.view())
						.append("<a href=/questions?UserRequest=EditQuestion&QuestionId=").append(r.questionId)
						.append(">Edit Question</a> or ")
						.append("<form method=post action=/feedback style='display:inline'>")
						.append("<input type=hidden name=sig value=").append(user.getTokenSignature()).append(" />")
						.append("<input type=hidden name=ReportId value=").append(r.id).append(" />")
						.append("<input type=submit name=UserRequest value='Delete Report' class='btn btn-primary' />")
						.append("</form><hr>");
				}
			}
		}
		return buf.toString() + Util.foot();
	}
}
