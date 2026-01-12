package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.key;
import static com.googlecode.objectify.ObjectifyService.ofy;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;

/**
 * Score tracks a user's performance across topics in an assignment.
 * 
 * This entity manages the user's progress through an assignment by:
 * - Tracking overall progress (totalScore, maxScore)
 * - Monitoring topic-specific performance (topicScores)
 * - Selecting appropriate questions based on adaptive algorithms
 * - Reporting progress back to the learning management system
 * 
 * Scoring Algorithm:
 * The totalScore uses a weighted running average that responds quickly to
 * improvements but maintains a performance floor based on the current decile.
 *   totalScore = (150*qScore + 14*totalScore)/15
 * 
 * Topic Scores:
 * Each topic is tracked separately using:
 *   topicScore = (100*qScore + 2*topicScore)/3
 * This increases three times faster than totalScore but caps at lower values,
 * preventing artificial inflation.
 * 
 * Question Selection:
 * The next question is selected through two stages:
 * 1. Topic Selection: Topics with lower scores get higher probability
 * 2. Question Type Selection: Based on totalScore quintile
 *    - Q1: true_false and multiple_choice
 *    - Q2: multiple_choice and fill_in_blank
 *    - Q3: fill_in_blank and checkbox
 *    - Q4: checkbox and numeric
 *    - Q5: numeric only
 * 
 * @see Assignment for the assignment being tracked
 * @see User for the student completing the assignment
 * @see Question for individual assessment items
 */
@Entity
public class Score {
	/**
	 * Weight multipliers for adaptive question selection based on topic performance gap.
	 * Maps distance from average (0-3) to weight probability for randoming topic selection.
	 */
	private static final int WEIGHT_DISTANCE_0 = 8;
	private static final int WEIGHT_DISTANCE_1 = 4;
	private static final int WEIGHT_DISTANCE_2 = 2;
	private static final int WEIGHT_DISTANCE_3 = 1;

	/**
	 * Numerator in the total score calculation formula:
	 * totalScore = (TOTAL_SCORE_MULTIPLIER * qScore + TOTAL_SCORE_DENOMINATOR - 1) / TOTAL_SCORE_DENOMINATOR
	 * This creates a weighted running average that quickly reflects improvements while respecting current level.
	 */
	private static final int TOTAL_SCORE_MULTIPLIER = 150;
	
	/**
	 * Total score denominator - provides the weighting baseline (15).
	 * Formula: totalScore = (150*qScore + 14*totalScore) / 15
	 */
	private static final int TOTAL_SCORE_DENOMINATOR = 15;

	/**
	 * Numerator in the topic score calculation formula:
	 * topicScore = (TOPIC_SCORE_MULTIPLIER * qScore + TOPIC_SCORE_DENOMINATOR - 1) / TOPIC_SCORE_DENOMINATOR
	 * Topic scores increase 3x faster than total scores to provide faster feedback.
	 */
	private static final int TOPIC_SCORE_MULTIPLIER = 100;
	
	/**
	 * Total topic score denominator - provides the weighting baseline (3).
	 * Formula: topicScore = (100*qScore + 2*topicScore) / 3
	 */
	private static final int TOPIC_SCORE_DENOMINATOR = 3;

	/**
	 * Maximum possible topic score before capping behavior applies.
	 * Used in distance calculations for topic weight distribution.
	 */
	private static final int MAX_TOPIC_SCORE = 100;

	/**
	 * Assignment type identifier for exercise questions (as opposed to exam or review content).
	 */
	private static final String ASSIGNMENT_TYPE_EXERCISES = "Exercises";

    
	@Id	Long id;  // assignmentId
	@Parent Key<User> owner;
	int totalScore = 0;
	int maxScore = 0; // totalScore can decrease, so only report maxScore to the LMS
	List<Long> topicIds = new ArrayList<Long>();
	List<Integer> topicScores = new ArrayList<Integer>();  // each 0-100 in order of topicId
	List<Key<Question>> recentQuestionKeys = new ArrayList<Key<Question>>();
	Long currentQuestionId;
	
	Score() {}
	
	Score(String hashedId, Assignment a) throws Exception {
		id = a.id;
		owner = key(User.class,hashedId);
		topicIds = a.topicIds;
		for (int i = 0; i < topicIds.size(); i++) topicScores.add(0);
		currentQuestionId = getNewQuestionId();
	}
	
	/**
	 * Selects a weighted question difficulty based on current score quintile.
	 * 
	 * Questions are weighted toward the current performance level and adjacent levels,
	 * creating focused practice with gradual progression. This implements an adaptive
	 * algorithm that adapts question difficulty to student performance.
	 * 
	 * Weight mapping by distance from current quintile:
	 * - Distance 0 (current level): weight = 8 (primary focus)
	 * - Distance 1 (±1 quintile): weight = 4 (secondary focus)  
	 * - Distance 2 (±2 quintile): weight = 2 (occasional challenge)
	 * - Distance 3 (±3 quintile): weight = 1 (rare stretching)
	 * 
	 * @param score the student's current total score (0-100)
	 * @return a difficulty level (1-5) weighted by quintile-based distribution
	 */
	int weightedRandomQuestionType(int score) {
		Random random = new Random();
		
        // Step 1: Map score to quintile (1 to 5)
        int quintile = score / 20 + 1;
        if (quintile > 5) quintile = 5;  // in case score = 100%

        // Step 2: Create map of weights for values 1 through 5
        Map<Integer, Integer> weights = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            int distance = Math.abs(i - quintile);
            int weight = switch (distance) {
                case 0 -> WEIGHT_DISTANCE_0;
                case 1 -> WEIGHT_DISTANCE_1;
                case 2 -> WEIGHT_DISTANCE_2;
                case 3 -> WEIGHT_DISTANCE_3;
                default -> 0;
            };
            weights.put(i, weight);
        }

        // Step 3: Weighted random selection
        int totalWeight = weights.values().stream().mapToInt(Integer::intValue).sum();
        int rand = random.nextInt(totalWeight);

        for (Map.Entry<Integer, Integer> entry : weights.entrySet()) {
            rand -= entry.getValue();
            if (rand < 0) {
                return entry.getKey();
            }
        }

        throw new RuntimeException("Weighted selection failed");
    }
	
	void repairMe(Assignment a) {
		/* 
		 * This method is required in the event that the instructor changes the topics
		 * covered after a user has started the assignment.
		 * Required changes:
		 * 1) replace topicIds with the one from the assignment
		 * 2) shuffle the scores to match the new list, discard obsolete, add new [0]
		 */
		List<Integer> newTopicScores = new ArrayList<Integer>();
		for (Long tId : a.topicIds) {
			if (topicIds.contains(tId)) newTopicScores.add(topicScores.get(topicIds.indexOf(tId)));
			else newTopicScores.add(0);
		}
		topicIds = a.topicIds;
		topicScores = newTopicScores;
		ofy().save().entity(this).now();
	}

	/**
	 * Updates the user's score after answering a question correctly or incorrectly.
	 * 
	 * Scoring Algorithm:
	 * Total Score: Uses weighted running average that heavily weights the new score
	 *   totalScore = (150*qScore + 14*totalScore) / 15
	 *   - When qScore=1 (correct), increases by ~10% initially, then 3% when approaching 100
	 *   - Score floors at the current decile (0, 10, 20, ..., 100) to prevent excessive drops
	 *   - Maximum is capped at 100
	 * 
	 * Topic Score: Updates for the current topic using faster weighting
	 *   topicScore = (100*qScore + 2*topicScore) / 3
	 *   - Increases three times faster than totalScore
	 *   - Provides faster feedback on topic mastery
	 * 
	 * Question Tracking: Records the current question to prevent skipping
	 * 
	 * Next Question: Selects a new question based on updated scores
	 * 
	 * @param user the user whose score is being updated
	 * @param q the question that was just answered
	 * @param qScore the question score (0 for incorrect, 1 for correct)
	 * @throws Exception if unable to save or retrieve questions
	 */
	void update(User user, Question q, int qScore) throws Exception {
		// Update the totalScore using weighted running average:
		int floor = totalScore/10*10;  // 0, 10, 20, 30,... 100
		totalScore = (TOTAL_SCORE_MULTIPLIER*qScore + (TOTAL_SCORE_DENOMINATOR-1)*totalScore) / TOTAL_SCORE_DENOMINATOR;
		if (totalScore > 100) totalScore = 100;
		if (totalScore < floor) totalScore = floor;
		
		// Update the topicScore using faster weighting:
		Long topicId = q.topicId;
		int index = topicIds.indexOf(topicId);
		topicScores.set(index, (TOPIC_SCORE_MULTIPLIER*qScore + (TOPIC_SCORE_DENOMINATOR-1)*topicScores.get(index)) / TOPIC_SCORE_DENOMINATOR);
		
		// Select a new questionId
		currentQuestionId = getNewQuestionId();
		
		// Create a Task to report the score to the LMS
		if (totalScore > maxScore) { // only report if maxScore increases
			maxScore = totalScore;
			if (!user.platformId.equals(Util.getServerUrl())) {
				String payload = "AssignmentId=" + id + "&UserId=" + URLEncoder.encode(user.getId(),"UTF-8");
				Util.createTask("/report",payload);
			}
		}
		
		ofy().save().entity(this).now();
		
	}
	
	/**
	 * Selects the next question to present to the student based on adaptive algorithm.
	 * 
	 * Two-Stage Selection Process:
	 * 1. Topic Selection: Uses weighted random selection where weight equals (MAX_TOPIC_SCORE - currentTopicScore)
	 *    - Topics with lower scores get higher selection probability
	 *    - Focuses student practice on weaker topics
	 *    - Falls back to equal probability if all topics are mastered
	 * 
	 * 2. Question Type Selection: Based on student's totalScore quintile (via weightedRandomQuestionType)
	 *    - Question type (difficulty/format) progresses as student improves
	 *    - Creates natural progression from simpler to more complex question types
	 * 
	 * Question Avoidance: Previously attempted questions are tracked in recentQuestionKeys
	 * to prevent showing the same question twice in succession.
	 * 
	 * @return the unique ID of the selected question to present
	 * @throws Exception if unable to query the database for available questions
	 */
	Long getNewQuestionId() throws Exception {
		// Select a topic based on current topic scores
		Long topicId = null;
		int range = 0;
		for (Long tId : topicIds) {
			range += MAX_TOPIC_SCORE - topicScores.get(topicIds.indexOf(tId));
		}
		Random random = new Random();
		if (range > 0) {
			int r = random.nextInt(range);
			range = 0;
			for (Long tId : topicIds) {
				range += MAX_TOPIC_SCORE - topicScores.get(topicIds.indexOf(tId));
				if (r < range) {
					topicId = tId;
					break;
				}
			}
		} else topicId = topicIds.get(random.nextInt(topicIds.size()));

		int questionType = weightedRandomQuestionType(totalScore);
		
		List<Key<Question>> questionKeys = switch (questionType) {
			case 1 -> ofy().load().type(Question.class).filter("assignmentType",ASSIGNMENT_TYPE_EXERCISES).filter("topicId",topicId).filter("type",Question.TYPE_TRUE_FALSE).keys().list();
			case 2 -> ofy().load().type(Question.class).filter("assignmentType",ASSIGNMENT_TYPE_EXERCISES).filter("topicId",topicId).filter("type",Question.TYPE_MULTIPLE_CHOICE).keys().list();
			case 3 -> ofy().load().type(Question.class).filter("assignmentType",ASSIGNMENT_TYPE_EXERCISES).filter("topicId",topicId).filter("type",Question.TYPE_FILL_IN_BLANK).keys().list();
			case 4 -> ofy().load().type(Question.class).filter("assignmentType",ASSIGNMENT_TYPE_EXERCISES).filter("topicId",topicId).filter("type",Question.TYPE_CHECKBOX).keys().list();
			default -> ofy().load().type(Question.class).filter("assignmentType",ASSIGNMENT_TYPE_EXERCISES).filter("topicId",topicId).filter("type",Question.TYPE_NUMERIC).keys().list();		
		};
		
/*
		// Gather question keys for types based on quartile score for the topic
		// Quartile 1 gets TF & MC, Q2 gets MC & FB, Q3 gets FB & CB, Q4 gets CB & NU
		int quartile = totalScore/25 + 1;
		if (quartile > 4) quartile = 4;

		List<Key<Question>> questionKeys = new ArrayList<Key<Question>>();
		switch (quartile) {
		case 1:
			questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Exercises").filter("topicId",topicId).filter("type","true_false").keys().list());
		case 2:
			questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Exercises").filter("topicId",topicId).filter("type","multiple_choice").keys().list());
			if (quartile == 1) break;
		case 3:
			questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Exercises").filter("topicId",topicId).filter("type","fill_in_blank").keys().list());
			if (quartile == 2) break;
		case 4:
			questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Exercises").filter("topicId",topicId).filter("type","checkbox").keys().list());
			if (quartile == 3) break;
			questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Exercises").filter("topicId",topicId).filter("type","numeric").keys().list());
		}
*/
		// Eliminate any questions recently answered or on deck
		questionKeys.removeAll(recentQuestionKeys);

		// Select one key at random and convert it to a Long id
		Key<Question> k = questionKeys.get(random.nextInt(questionKeys.size()));

		// Add the key to the list of recent and trim, if necessary
		recentQuestionKeys.add(k);
		if (recentQuestionKeys.size() > 5) recentQuestionKeys.removeFirst();

		return k.getId();
	}
}