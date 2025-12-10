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
@WebServlet("/report")
public class ReportScore extends HttpServlet {
	private static final long serialVersionUID = 137L;
	
	public String getServletInfo() {
		return "ChemVantage servlet reports a single Score object back to a user's LMS as a Task "
				+ "using the 1EdTech LTI Advantage Assignment and Grade Services 2.0 Specification.";
	}
	
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) 
	throws ServletException, IOException {
		StringBuffer debug = new StringBuffer("Debug:");
		try {  // post single user score
			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			
			String userId = request.getParameter("UserId");
			String hashedId = Util.hashId(userId);
			
			Long assignmentId = Long.parseLong(request.getParameter("AssignmentId"));
			Assignment a = ofy().load().type(Assignment.class).id(assignmentId).safe();
			
			Key<Score> scoreKey = key(key(User.class,hashedId), Score.class, a.id);
			Score s = ofy().load().key(scoreKey).safe();
			
			if (a.lti_ags_lineitem_url != null && !a.lti_ags_lineitem_url.contains("localhost")) {  // use LTIAdvantage reporting specs
				String reply = LTIMessage.postUserScore(s,userId);
				
				if (reply.contains("Success") || reply.contains("422")) out.println(reply);
				else {
					User user = ofy().load().type(User.class).filter("hashedId",Util.hashId(userId)).first().now();
					if (user.isInstructor()) return; // no harm; LMS refused to post instructor score
					debug.append("User " + userId + " earned a score of " + s.totalScore + "% on assignment "
							+ a.id + "; however, the score could not be posted to the LMS grade book. "
							+ "The response from the " + a.platform_deployment_id + " LMS was: " + reply);
				}
			}
		} catch (Exception e) {
			Util.sendEmail("ChemVantage","admin@chemvantage.org","Failed ReportScore",(e.getMessage()==null?e.toString():e.getMessage()) + "<br/>" + debug.toString());
			response.sendError(401,"Failed ReportScore");
		}
	}	
}