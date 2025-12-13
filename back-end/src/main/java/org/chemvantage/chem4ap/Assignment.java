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
 * 
 * Assignment represents a collection of topics and questions for students to complete.
 * Assignments are typically created from an LTI platform and include references to
 * LTI resources for grading and roster management.
 * 
 * Key Features:
 * - Automatic topic selection based on question availability (min 5 questions per topic)
 * - LTI 1.3 integration for platform authentication and grade reporting
 * - Assignment and Grade Services (AGS) endpoints for score submission
 * - Names and Role Provisioning Services (NRPS) for user roster management
 * - Validation timeout tracking to ensure assignment data currency
 * 
 * Each assignment is associated with:
 * - A parent APChemUnit containing curriculum organization
 * - One or more APChemTopics from that unit
 * - Multiple Question entities for each topic
 * - Student Score entities tracking individual progress
 * 
 * @see APChemUnit for parent curriculum unit
 * @see APChemTopic for individual topic coverage
 * @see Question for individual assessment items
 * @see Score for student progress tracking
 */
@Entity
public class Assignment implements java.lang.Cloneable {
	/** Minimum questions per topic required to include in assignment (ensures variety) */
	private static final int MIN_QUESTIONS_PER_TOPIC = 5;
	
	/** Validation timeout: assignment considered stale after ~1 month without validation */
	private static final long VALIDATION_TIMEOUT_MS = 2635200000L; // ~1 month
	
	/** Assignment type identifier for exercise questions (as opposed to exam or review content) */
	private static final String ASSIGNMENT_TYPE_EXERCISES = "Exercises";

	@Id 	Long id;
	
	/** LTI platform deployment identifier (indexed for organizational queries) */
	@Index	String platform_deployment_id;
	
	/** Assignment type (indexed): "Exercises", "Homework", "Quiz", etc. */
	@Index	String assignmentType;
	
	/** LTI resource link identifier (indexed for gradebook integration) */
	@Index	String resourceLinkId;
	
	/** Assignment creation timestamp (indexed for chronological queries) */
	@Index 	Date created;
	
	/** LTI Assignment and Grade Services (AGS) lineitems endpoint for roster/grading */
	@Index	String lti_ags_lineitems_url;
	
	/** LTI specific line item URL for this assignment's grades */
	@Index	String lti_ags_lineitem_url;
	
	/** Last validation timestamp (indexed for staleness detection) */
	@Index	Date valid;
	
	/** LTI Names and Role Provisioning Services (NRPS) URL for user roster management */
			String lti_nrps_context_memberships_url;
	
	/** Display title shown to students and instructors */
			String title;
	
	/** Reference to parent APChemUnit for curriculum organization (indexed) */
	@Index	Long unitId;
	
	/** List of topic IDs included in this assignment (sorted for consistency) */
			List<Long> topicIds = new ArrayList<Long>();
			
	/** Default no-arg constructor required by Objectify. */
	public Assignment() {}
	
	/**
	 * Constructs an assignment and auto-populates topics with sufficient question coverage.
	 * 
	 * Automatically includes only topics that have more than the minimum required questions
	 * (MIN_QUESTIONS_PER_TOPIC) to ensure students have adequate variety and multiple
	 * attempts per topic. Topics are included in curriculum order.
	 * 
	 * The created timestamp is set to the current date/time, and the assignment is NOT
	 * automatically persisted. The caller must save this assignment using Objectify.
	 * 
	 * @param assignmentType the type of assignment (e.g., "Exercises", "Homework", "Quiz")
	 * @param title the display title shown to students and instructors
	 * @param unitId the ID of the parent APChemUnit containing curriculum organization
	 * @param platform_deployment_id the LTI platform deployment identifier for this instance
	 * @throws NullPointerException if unitId refers to a non-existent unit
	 * @see #MIN_QUESTIONS_PER_TOPIC for the minimum question threshold
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
			int nQuestions = ofy().load().type(Question.class).filter("assignmentType",ASSIGNMENT_TYPE_EXERCISES).filter("topicId",t.id).count();
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