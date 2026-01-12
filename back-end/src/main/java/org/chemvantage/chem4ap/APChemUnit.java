package org.chemvantage.chem4ap;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

/**
 * Domain model for an AP Chemistry unit; persisted with Objectify.
 * 
 * Units represent the highest-level organizational structure in the AP Chemistry
 * curriculum, containing multiple topics and serving as the root for student
 * assignment creation. Each unit encompasses a major area of AP Chemistry content
 * as defined by the College Board.
 * 
 * Units are immutable after creation and are typically loaded from curriculum
 * management systems. They serve as the top-level organization for assignments,
 * topics, and questions.
 * 
 * Hierarchy: Unit > Topic > Question
 * 
 * @see APChemTopic for topics within this unit
 * @see Question for questions within unit topics
 * @see Assignment for assignments created from unit topics
 */
@Entity
public class APChemUnit implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;
	
	/** Unique unit identifier (database key) */
	@Id 	Long id;
	
	/** Unit number for curriculum ordering (indexed) */
	@Index 	int unitNumber;
	
	/** Display title of the unit (e.g., "Atomic Structure and Periodicity") */
	 		String title;
	
	/** Summary describing the unit's scope and key concepts */
	 		String summary;
	
	/** List of topic IDs contained in this unit (for assignment creation) */
	 		List<Long> topicIds;

	/** Default no-arg constructor required by Objectify. */
	public APChemUnit() {}

	/** @return the unique identifier for this unit */
	public Long getId() { return id; }
	
	/** @return the unit number for curriculum ordering */
	public int getUnitNumber() { return unitNumber; }
	
	/** @return the display title of this unit */
	public String getTitle() { return title; }
	
	/** @return the summary describing scope and key concepts */
	public String getSummary() { return summary; }
	
	/** @return list of topic IDs contained in this unit */
	public List<Long> getTopicIds() { return topicIds; }

}
