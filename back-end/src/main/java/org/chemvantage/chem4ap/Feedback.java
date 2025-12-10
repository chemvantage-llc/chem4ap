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

@WebServlet("/feedback")
public class Feedback extends HttpServlet {

	private static final long serialVersionUID = 1L;
	
	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");

		try {
			User user = User.getUser(request.getParameter("sig"));
			if (user == null) response.sendRedirect("/");
			String userRequest = request.getParameter("UserRequest");
			if (userRequest==null) userRequest = "";
			
			switch (userRequest) {
			case "ViewFeedback":
				out.println(viewUserReports(user));
			default:
				out.println(feedbackForm(user,request));						
			}
		} catch (Exception e) {
			out.println("Error: " + e.getMessage());
		}
	}
	
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");

		User user = User.getUser(request.getParameter("sig"));
		if (user == null) response.sendError(401,"Unauthorized");

		String userRequest = request.getParameter("UserRequest");
		if (userRequest==null) userRequest = "";
		
		if ("Delete Report".equals(userRequest)) {
			if (!user.isChemVantageAdmin()) return;
			Long reportId = Long.parseLong(request.getParameter("ReportId"));
			ofy().delete().type(UserReport.class).id(reportId).now();
			out.println(viewUserReports(user));
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
	
	void createUserReport(User user, HttpServletRequest request) {
		String userId = user==null?"":user.getId();
		Long questionId = Long.parseLong(request.getParameter("QuestionId"));
		String p = request.getParameter("Parameter");
		Integer parameter = p==null?null:Integer.parseInt(p);
		String comment = request.getParameter("Comment");
		String studentAnswer = request.getParameter("StudentAnswer");
		UserReport r = new UserReport(user.hashedId,questionId,parameter,studentAnswer,comment);
		ofy().save().entity(r);
		String msg = "On " + r.submitted + " a problem report was submitted by ";
		String email = user.hashedId;
		try {
			if (user.platformId.equals(Util.getServerUrl())) {  // independent user
				email = user.getId();
			} else {											// LTI user
				Long assignmentId = user.getAssignmentId();
				Assignment a = ofy().load().type(Assignment.class).id(assignmentId).safe();
				Map<String,String[]> contextMembership = LTIMessage.getMembership(a);
				String rawId = userId.substring(userId.lastIndexOf("/")+1);
				email = contextMembership.get(rawId)[2];
			}
		} catch (Exception e) {}  // failed to retrieve email address
		msg += email + "<br/>" + r.view();
		try {
			Util.sendEmail("ChemVantage","admin@chemvantage.org","User Report", msg);
		} catch (Exception e) {}
	}
	
	String feedbackForm(User user, HttpServletRequest request) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head("Feedback"));
		
		buf.append(Util.banner + "<h1>Report a Problem</h1>"
				+ "At Chem4AP, we strive to ensure that every question item is clear, and every "
				+ "answer is scored correctly. However, with thousands of questions, sometimes a problem can "
				+ "arise. We welcome feedback from our users because this helps to ensure the high "
				+ "quality of the app. Thank you in advance for your comments below.<br/><br/>");
		
		Long questionId = Long.parseLong(request.getParameter("questionId"));
		Question q = ofy().load().type(Question.class).id(questionId).safe();
		Integer parameter = null;
		if (q.requiresParser()) {
			parameter = Integer.parseInt(request.getParameter("parameter"));
			q.setParameters(parameter);
		}
		 buf.append("<h2>Question Item</h2>" + q.printAll()
		 		+ "Your answer was: ");
		 
		 String studentAnswer = request.getParameter("studentAnswer");
		 if (studentAnswer==null) studentAnswer = "";
		 
		 
		 switch (q.type) {
		 case "multiple_choice":
			 buf.append("<b>" + q.choices.get(studentAnswer.charAt(0)-'a') + "</b><br/><br/>");
			 buf.append("Reminder: The correct answer is shown in bold print above."); 
			 break;
		 case "true_false":
			 buf.append("<b>" + studentAnswer + "</b><br/><br/>");
			 buf.append("Reminder: The correct answer is shown in bold print above."); 
			 break;
		 case "checkbox":
			 buf.append("<ul>");
			 for (int i=0;i<studentAnswer.length();i++) {
				 buf.append("<li><b>" + q.choices.get(studentAnswer.charAt(i)-'a') + "</b></li>");
			 }
			 buf.append("</ul>");
			 buf.append("Reminder: The correct answers are shown in bold print above. You must select all of them."); 
			 break;
		 case "fill_in_blank": 
			 buf.append("<b>" + studentAnswer + "</b><br/><br/>");
			 buf.append("Reminder: The correct answer will always form a complete, grammatically correct sentence."); 
			 break;
		 case "numeric":
			 buf.append("<b>" + studentAnswer + "</b><br/><br/>");
			 switch (q.getNumericItemType()) {
			 case 0: buf.append("Reminder: Your answer must have exactly the same value as the correct answer."); break;
			 case 1: buf.append("Reminder: Your answer must have exactly the same value as the correct answer and must have " + q.significantFigures + " significant figures."); break;
			 case 2: buf.append("Reminder: Your answer must be within " + q.requiredPrecision + "% of the correct answer."); break;
			 case 3: buf.append("Reminder: Your answer must have " + q.significantFigures + " significant figures and be within " + q.requiredPrecision + "% of the correct answer."); break;
			 default:
			 }
		 default:
		 }
		 
		 buf.append("<form method=post action=/feedback>"
		 		+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
 				+ "<input type=hidden name=QuestionId value=" + q.id + " />"
				+ (parameter==null?"":"<input type=hidden name=Parameter value=" + parameter + " />")
		 		+ "<input type=hidden name=StudentAnswer value='" + studentAnswer + "' />"
				+ "Your Comment: <INPUT TYPE=TEXT SIZE=80 NAME=Comment /><br/><br/>"
		 		+ "<input type=submit class='btn btn-primary' value='Submit Feedback' />&nbsp;&nbsp;"
		 		+ "<button class='btn btn-primary' onClick='window.close();'>Never Mind</button>"
		 		+ "</form>");
				
		return buf.toString() + Util.foot();
	}
	
	String viewUserReports(User user) {
		StringBuffer buf = new StringBuffer(Util.banner + Util.head("User Feedback"));
		if (!user.isChemVantageAdmin()) return "<h1>Unauthorized</h1>";
		
		buf.append("<h1>User Feedback</h1>");
		List<UserReport> reports = ofy().load().type(UserReport.class).list();
		if (reports.size()==0) buf.append("There are no new user reports.");
		
		for (UserReport r : reports) {
			buf.append("On " + r.submitted + " user " + r.userId + " commented:<br/>"
					+ r.view());
			
			buf.append("<a href=/questions?UserRequest=EditQuestion&QuestionId=" + r.questionId + ">Edit Question</a>"
					+ " or "
					+ "<form method=post action=/feedback style='display:inline'>"
					+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
					+ "<input type=hidden name=ReportId value=" + r.id + " />"
					+ "<input type=submit name=UserRequest value='Delete Report' class='btn btn-primary' />"
					+ "</form><hr>");
		}
		return buf.toString() + Util.foot();
	}
}
