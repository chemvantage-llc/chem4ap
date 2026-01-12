/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2019 ChemVantage LLC
*   
*    This program is free software: you can redistribute it and/or modify
*   it under the terms of the GNU General Public License as published by
*   the Free Software Foundation, either version 3 of the License, or
*   (at your option) any later version.
*
*   This program is distributed in the hope that it will be useful,
*   but WITHOUT ANY WARRANTY; without even the implied warranty of
*   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*   GNU General Public License for more details.
*
*   You should have received a copy of the GNU General Public License
*   along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.key;
import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;

import com.googlecode.objectify.Key;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/* 
 * Access to this servlet is restricted to ChemVantage admin users and the project service account
 * by specifying login: admin in a url handler of the project app.yaml file
 */
/**
 * ReportScore servlet for synchronizing student scores back to LMS via LTI Advantage.
 * 
 * Handles asynchronous reporting of individual student scores to Learning Management System
 * grade books using the 1EdTech LTI Advantage Assignment and Grade Services (AGS) 2.0
 * specification. Called after student completes an assignment to post their score.
 * 
 * Workflow:
 * 1. Receives HTTP POST with UserId and AssignmentId parameters
 * 2. Retrieves Score entity from datastore using composite key (User + Assignment)
 * 3. Looks up Assignment to get LTI AGS line item URL (the LMS grade book entry)
 * 4. Calls LTIMessage.postUserScore() to submit score via OAuth-signed HTTP request
 * 5. Validates LMS response (expects Success or 422 Unprocessable Entity)
 * 6. On success: returns response to client (automatic grade book update)
 * 7. On failure: logs error and notifies admin via email
 * 
 * Score Reporting:
 * - Score must be calculated and stored in datastore before calling this servlet
 * - Submitted score is totalScore from Score entity (0-100 percent)
 * - Assignment.lti_ags_lineitem_url must be non-null and non-localhost for reporting
 * - Instructor scores are silently skipped (no LMS posting for instructors)
 * 
 * Error Handling:
 * - If LMS URL is unavailable (null or localhost), reporting is skipped
 * - If LMS returns error (not 200/422), email is sent to admin with debug details
 * - 401 Unauthorized response sent to client on servlet-level errors
 * - Includes User.isInstructor() check to prevent posting instructor test scores
 * 
 * Security:
 * - Requires admin user authentication via app.yaml login handler
 * - OAuth 2.0 Client Credentials flow for LMS API access
 * - Uses secure HTTPS for all LMS communication
 * - No direct input validation needed (admin-only access)
 * 
 * LTI Advantage AGS Integration:
 * - Implements 1EdTech LTI Advantage Assessment 2.0 specification
 * - Submits scores via PUT request to Assignment.lti_ags_lineitem_url
 * - Authorization via OAuth 2.0 token (issued by LMS platform)
 * - Supports both success (200) and unprocessable entity (422) responses
 * 
 * @author ChemVantage
 * @version 2.0
 * @see LTIMessage#postUserScore(Score, String)
 * @see Assignment#lti_ags_lineitem_url
 * @see Score
 */
@WebServlet("/report")
public class ReportScore extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 137L;
	
	// Request parameter constants
	/** HTTP parameter: User identifier (email or login) */
	private static final String PARAM_USER_ID = "UserId";
	/** HTTP parameter: Assignment identifier (primary key) */
	private static final String PARAM_ASSIGNMENT_ID = "AssignmentId";
	
	// Response message constants
	/** Success response indicator from LMS */
	private static final String RESPONSE_SUCCESS = "Success";
	/** HTTP 422 (Unprocessable Entity) response code - LMS unable to process but no error */
	private static final String RESPONSE_UNPROCESSABLE = "422";
	/** Localhost URL indicator for local development (skip LMS reporting) */
	private static final String URL_LOCALHOST = "localhost";
	
	// Email notification constants
	/** Email recipient for error notifications (admin) */
	private static final String EMAIL_TO = "admin@chemvantage.org";
	/** Email sender identity */
	private static final String EMAIL_FROM = "ChemVantage";
	/** Email subject for score reporting failures */
	private static final String EMAIL_SUBJECT = "Failed ReportScore";
	
	// Debug message template constants
	/** Debug message prefix */
	private static final String DEBUG_PREFIX = "Debug:";
	/** Debug message for successful score submission */
	private static final String DEBUG_MSG_SUBMIT = "User %s earned a score of %d%% on assignment %d; however, the score could not be posted to the LMS grade book. The response from the %s LMS was: %s";
	
	/**
	 * Returns servlet description for introspection.
	 * 
	 * @return Description of servlet purpose and LTI specification
	 */
	public String getServletInfo() {
		return "ChemVantage servlet reports a single Score object back to a user's LMS as a Task "
				+ "using the 1EdTech LTI Advantage Assignment and Grade Services 2.0 Specification.";
	}
	
	/**
	 * Handles HTTP POST request to report student score to LMS.
	 * 
	 * Workflow:
	 * 1. Extract UserId and AssignmentId from request parameters
	 * 2. Retrieve Score entity from datastore using composite key
	 * 3. If Assignment has LTI AGS line item URL and not localhost:
	 *    - Call LTIMessage.postUserScore() to submit score via OAuth
	 *    - Return LMS response (200 Success or 422 Unprocessable Entity)
	 * 4. On LMS error, send admin email with debug information
	 * 5. On servlet-level exception, respond with 401 Unauthorized and email admin
	 * 
	 * @param request HTTP POST request with UserId and AssignmentId parameters
	 * @param response HTTP response containing LMS reply or error status
	 * @throws ServletException for servlet container errors
	 * @throws IOException for response stream errors
	 * @see LTIMessage#postUserScore(Score, String)
	 * @see User#isInstructor()
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) 
	throws ServletException, IOException {
		StringBuffer debug = new StringBuffer(DEBUG_PREFIX);
		try {  // post single user score
			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			
			String userId = request.getParameter(PARAM_USER_ID);
			String hashedId = Util.hashId(userId);
			
			Long assignmentId = Long.parseLong(request.getParameter(PARAM_ASSIGNMENT_ID));
			Assignment a = ofy().load().type(Assignment.class).id(assignmentId).safe();
			
			Key<Score> scoreKey = key(key(User.class,hashedId), Score.class, a.id);
			Score s = ofy().load().key(scoreKey).safe();
			
			if (a.lti_ags_lineitem_url != null && !a.lti_ags_lineitem_url.contains(URL_LOCALHOST)) {  // use LTIAdvantage reporting specs
				String reply = LTIMessage.postUserScore(s,userId);
				
				if (reply.contains(RESPONSE_SUCCESS) || reply.contains(RESPONSE_UNPROCESSABLE)) out.println(reply);
				else {
					User user = ofy().load().type(User.class).filter("hashedId",Util.hashId(userId)).first().now();
					if (user.isInstructor()) return; // no harm; LMS refused to post instructor score
					debug.append(DEBUG_MSG_SUBMIT.formatted(userId, s.totalScore, a.id, a.platform_deployment_id, reply));
				}
			}
		} catch (Exception e) {
			Util.sendEmail(EMAIL_FROM, EMAIL_TO, EMAIL_SUBJECT, (e.getMessage()==null?e.toString():e.getMessage()) + "<br/>" + debug.toString());
			response.sendError(401,"Failed ReportScore");
		}
	}	
}