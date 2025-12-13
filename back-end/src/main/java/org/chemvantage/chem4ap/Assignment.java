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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

/**
 * Domain model for an AP Chemistry assignment/exercise set; persisted with Objectify.
 * Integrates with LTI 1.3 for learning management system deployment.
 */
@Entity
public class Assignment implements java.lang.Cloneable {
	private static final int MIN_QUESTIONS_PER_TOPIC = 5;
	private static final long VALIDATION_TIMEOUT_MS = 2635200000L; // ~1 month

	@Id 	Long id;
	@Index	String platform_deployment_id;  // LTI platform deployment identifier
	@Index	String assignmentType;          // e.g., "Exercises", "Homework"
	@Index	String resourceLinkId;          // LTI resource link identifier
	@Index 	Date created;                   // Assignment creation timestamp
	@Index	String lti_ags_lineitems_url;   // LTI Assignment and Grade Services endpoint
	@Index	String lti_ags_lineitem_url;    // LTI specific line item URL
	@Index	Date valid;                     // Last validation timestamp
			String lti_nrps_context_memberships_url;  // LTI Names and Role Provisioning Services URL
			String title;
	@Index	Long unitId;                    // Reference to parent APChemUnit
			List<Long> topicIds = new ArrayList<Long>();  // Topics included in this assignment
			
	/** Default no-arg constructor required by Objectify. */
	public Assignment() {}
	
	/**
	 * Constructs an assignment and auto-populates topics with sufficient question coverage.
	 * Only topics with > 5 questions are included to ensure adequate exercise variety.
	 * 
	 * @param assignmentType type of assignment (e.g., "Exercises")
	 * @param title display title
	 * @param unitId reference to parent unit
	 * @param platform_deployment_id LTI platform deployment ID
	 */
	public Assignment(String assignmentType, String title, Long unitId, String platform_deployment_id) {
		this.assignmentType = assignmentType;
		this.title = title;
		this.unitId = unitId;
		this.platform_deployment_id = platform_deployment_id;
		this.created = new Date();
		APChemUnit unit = ofy().load().type(APChemUnit.class).id(unitId).now();
		List<APChemTopic> topics = ofy().load().type(APChemTopic.class).filter("unitId",unit.id).order("topicNumber").list();
		for (APChemTopic t: topics) {
			int nQuestions = ofy().load().type(Question.class).filter("assignmentType","Exercises").filter("topicId",t.id).count();
			if (nQuestions > MIN_QUESTIONS_PER_TOPIC) topicIds.add(t.id);
		}
	}

	@Override
	public Assignment clone() throws CloneNotSupportedException {
		return (Assignment) super.clone();
	}
	
	/**
	 * Compares this assignment to another for equivalence.
	 * All fields must match (or both be null), except valid must be within 1 month tolerance.
	 * 
	 * @param a the assignment to compare against
	 * @return true if assignments are equivalent, false otherwise
	 */
	boolean equivalentTo(Assignment a) {
		if (a == null) return false;
		
		return fieldsEqual(this.id, a.id) &&
			   fieldsEqual(this.platform_deployment_id, a.platform_deployment_id) &&
			   fieldsEqual(this.assignmentType, a.assignmentType) &&
			   fieldsEqual(this.resourceLinkId, a.resourceLinkId) &&
			   fieldsEqual(this.lti_ags_lineitems_url, a.lti_ags_lineitems_url) &&
			   fieldsEqual(this.lti_ags_lineitem_url, a.lti_ags_lineitem_url) &&
			   fieldsEqual(this.lti_nrps_context_memberships_url, a.lti_nrps_context_memberships_url) &&
			   (this.valid != null && a.valid != null && 
			    Math.abs(this.valid.getTime() - a.valid.getTime()) < VALIDATION_TIMEOUT_MS);
	}
	
	/** Helper method to safely compare nullable fields. */
	private boolean fieldsEqual(Object field1, Object field2) {
		return (field1 != null && field1.equals(field2)) || (field1 == null && field2 == null);
	}
		
	// Getter methods
	public Long getId() { return id; }
	public String getAssignmentType() { return assignmentType; }
	public String getTitle() { return title; }
	public Long getUnitId() { return unitId; }
	public List<Long> getTopicIds() { return topicIds; }
	public Date getCreated() { return created; }
	public Date getValid() { return valid; }
	public String getPlatformDeploymentId() { return platform_deployment_id; }
	public String getResourceLinkId() { return resourceLinkId; }
	public String getLtiAgsLineitemsUrl() { return lti_ags_lineitems_url; }
	public String getLtiAgsLineitemUrl() { return lti_ags_lineitem_url; }
	public String getLtiNrpsContextMembershipsUrl() { return lti_nrps_context_memberships_url; }
}