package org.chemvantage.chem4ap;

import java.io.Serializable;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

/**
 * Domain model for an AP Chemistry topic; persisted with Objectify.
 * Field visibility is package-private to match existing datastore usage.
 */
@Entity
public class APChemTopic implements Serializable {

	private static final long serialVersionUID = 1L;
	
	@Id 	Long id;
	@Index	Long unitId;
	@Index	int unitNumber;
	@Index 	int topicNumber;
	 		String title;
	 		String learningObjective;
	 		String[] essentialKnowledge;
	 		
	/** Default no-arg constructor required by Objectify. */
	public APChemTopic() {}

	public Long getId() { return id; }
	public Long getUnitId() { return unitId; }
	public int getUnitNumber() { return unitNumber; }
	public int getTopicNumber() { return topicNumber; }
	public String getTitle() { return title; }
	public String getLearningObjective() { return learningObjective; }
	public String[] getEssentialKnowledge() { return essentialKnowledge; }

}