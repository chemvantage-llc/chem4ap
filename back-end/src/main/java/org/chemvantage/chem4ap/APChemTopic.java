package org.chemvantage.chem4ap;

import java.io.Serializable;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

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
	 		
	APChemTopic() {}

}