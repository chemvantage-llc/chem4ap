package org.chemvantage.chem4ap;

import java.io.Serializable;
import java.util.List;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

/**
 * Domain model for an AP Chemistry unit; persisted with Objectify.
 * Field visibility is package-private to match existing datastore usage.
 */
@Entity
public class APChemUnit implements Serializable {

	private static final long serialVersionUID = 1L;
	
	@Id 	Long id;
	@Index 	int unitNumber;
	 		String title;
	 		String summary;
	 		List<Long> topicIds;

	/** Default no-arg constructor required by Objectify. */
	public APChemUnit() {}

	public Long getId() { return id; }
	public int getUnitNumber() { return unitNumber; }
	public String getTitle() { return title; }
	public String getSummary() { return summary; }
	public List<Long> getTopicIds() { return topicIds; }

}
