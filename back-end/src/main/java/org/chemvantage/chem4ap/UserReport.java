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

import java.io.Serializable;
import java.util.Date;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

@Entity
public class UserReport implements Serializable {
	private static final long serialVersionUID = 137L;
	@Id 	Long id;
	@Index 	Date submitted;
			String userId;
			int stars;
			Long questionId;
			Integer parameter;
			String studentAnswer;
			String comments = "";
	
	UserReport() {}
	
	UserReport(String userId,Long questionId,Integer parameter,String studentAnswer,String comments) {
		this.userId = Util.hashId(userId);
		this.questionId = questionId;
		this.parameter = parameter;
		this.studentAnswer = studentAnswer;
		this.comments = comments;
		this.submitted = new Date();
	}
	
	public String view() {
		StringBuffer buf = new StringBuffer();
		buf.append("<FONT COLOR=RED>" + comments + "</FONT><br>");
		try {			
			Question q = ofy().load().type(Question.class).id(this.questionId).safe();
			if (q.requiresParser()) q.setParameters(parameter);

			buf.append(q.printAllToStudents(studentAnswer,true,false) + "<br/>");
		} catch (Exception e) {
			buf.append(e.getMessage() + "<br/>(question not be found)<br/><br/>");
		}
		return buf.toString();
	}
}