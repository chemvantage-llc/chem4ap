package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.text.DecimalFormat;

import com.googlecode.objectify.annotation.Id;

public class StarRatings {

	@Id static Long id = 1L;
	static double avgStars = 0;
	static int nStarReports = 0;
	static StarRatings s;
	
	static {
		try {  // retrieve values from datastore when a new software version is installed
			if (nStarReports == 0) s = ofy().load().type(StarRatings.class).id(1L).safe();
		} catch (Exception e) { // this will run only once when project is initiated
			s = new StarRatings();
			ofy().save().entity(s);
		}
	}
	
	static double getAvgStars() {
		DecimalFormat df2 = new DecimalFormat("#.#");
		return Double.valueOf(df2.format(avgStars));
	}
	
	static void addStarReport(int stars) {
		StarRatings s = new StarRatings();
		avgStars = (avgStars*nStarReports + stars)/(nStarReports+1);
		nStarReports++;
		ofy().save().entity(s);
	}
}
