/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2011 ChemVantage LLC
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

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

/**
 * UserReport tracks student feedback and issue reports for individual questions.
 * 
 * Students can submit reports about questions they find problematic, including
 * feedback on question quality, clarity, or potential errors. Reports are timestamped
 * and linked to the specific question, parameter set, and student response for
 * instructor review and curriculum improvement.
 * 
 * Each report includes:
 * - Question identification (questionId, parameter set)
 * - Student context (userId, studentAnswer, submitted timestamp)
 * - Feedback (stars rating, comments)
 * 
 * @see Question for the associated question entity
 * @see User for the reporting student
 */
@Entity
public class UserReport implements Serializable {
	@Serial
	private static final long serialVersionUID = 137L;
	
	/** Unique report identifier (database key) */
	@Id 	Long id;
	
	/** Report submission timestamp (indexed for chronological queries) */
	@Index 	Date submitted;
	
	/** Hashed user identifier of the student submitting the report */
			String userId;
	
	/** Star rating (1-5) from the student assessment, 0 if not set */
			int stars;
	
	/** ID of the question this report addresses */
			Long questionId;
	
	/** Parameter set number used when the student answered the question */
			Integer parameter;
	
	/** The answer the student entered (for context) */
			String studentAnswer;
	
	/** Student comments describing the issue or feedback */
			String comments = "";
	
	/** Default constructor for Objectify ORM deserialization. */
	UserReport() {}
	
	/**
	 * Creates a new UserReport with feedback about a specific question attempt.
	 * 
	 * @param userId the student's user ID (will be hashed for privacy)
	 * @param questionId the unique ID of the question being reported
	 * @param parameter the parameter set number used in that question instance
	 * @param studentAnswer the answer the student submitted (for context)
	 * @param comments descriptive feedback or issue description from the student
	 */
	UserReport(String userId,Long questionId,Integer parameter,String studentAnswer,String comments) {
		this.userId = Util.hashId(userId);
		this.questionId = questionId;
		this.parameter = parameter;
		this.studentAnswer = studentAnswer;
		this.comments = comments;
		this.submitted = new Date();
	}
	
	/**
	 * Generates an HTML view of the report for instructor review.
	 * 
	 * Displays the student's feedback comments and the question that was reported,
	 * showing how it appeared to the student with their submitted answer.
	 * 
	 * @return HTML string containing comments and question context
	 */
	public String view() {
		StringBuilder buf = new StringBuilder();
		buf.append("<FONT COLOR=RED>").append(comments).append("</FONT><br>");
		try {			
			Question q = ofy().load().type(Question.class).id(this.questionId).safe();
			if (q.requiresParser()) q.setParameters(parameter);

			buf.append(q.printAllToStudents(studentAnswer,true,false)).append("<br/>");
		} catch (Exception e) {
			buf.append(e.getMessage()).append("<br/>(question not be found)<br/><br/>");
		}
		return buf.toString();
	}
}