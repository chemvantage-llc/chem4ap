package org.chemvantage.chem4ap;

import java.io.Serializable;
import java.util.List;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

@Entity
public class APChemUnit implements Serializable {

	private static final long serialVersionUID = 1L;
	
	@Id 	Long id;
	@Index 	int unitNumber;
	 		String title;
	 		String summary;
	 		List<Long> topicIds;

	APChemUnit() {}

}
