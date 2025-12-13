package org.chemvantage.chem4ap;

import java.io.Serializable;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

/**
 * Domain model for an AP Chemistry topic; persisted with Objectify.
 * 
 * Topics represent individual learning units within the AP Chemistry curriculum,
 * organized hierarchically as Unit > Topic > Questions. Each topic defines the
 * learning objectives and essential knowledge elements that guide question design
 * and student assessment.
 * 
 * Topics are immutable after creation and are typically loaded from curriculum
 * management. They serve as the organizational basis for student score tracking
 * and adaptive question selection.
 * 
 * @see APChemUnit for parent curriculum unit
 * @see Question for questions associated with this topic
 * @see Score for student progress tracking by topic
 */
@Entity
public class APChemTopic implements Serializable {

	private static final long serialVersionUID = 1L;
	
	/** Unique topic identifier (database key) */
	@Id 	Long id;
	
	/** Parent unit ID this topic belongs to (indexed for organizational queries) */
	@Index	Long unitId;
	
	/** Unit number for curriculum ordering (indexed) */
	@Index	int unitNumber;
	
	/** Topic number within the unit for curriculum ordering (indexed) */
	@Index 	int topicNumber;
	
	/** Display title of the topic (e.g., "Atomic Structure and Periodicity") */
	 		String title;
	
	/** Learning objective describing what students should be able to do */
	 		String learningObjective;
	
	/** Essential knowledge points (core content students must master) */
	 		String[] essentialKnowledge;
	
	/** Default no-arg constructor required by Objectify. */
	public APChemTopic() {}

	/** @return the unique identifier for this topic */
	public Long getId() { return id; }
	
	/** @return the parent unit ID containing this topic */
	public Long getUnitId() { return unitId; }
	
	/** @return the curriculum unit number for ordering */
	public int getUnitNumber() { return unitNumber; }
	
	/** @return the topic number within the unit for ordering */
	public int getTopicNumber() { return topicNumber; }
	
	/** @return the display title of this topic */
	public String getTitle() { return title; }
	
	/** @return the learning objective describing student learning outcomes */
	public String getLearningObjective() { return learningObjective; }
	
	/** @return array of essential knowledge points students must master */
	public String[] getEssentialKnowledge() { return essentialKnowledge; }

}