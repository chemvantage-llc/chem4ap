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

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Collator;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.springframework.boot.autoconfigure.kafka.KafkaProperties.Retry.Topic;

import com.bestcode.mathparser.IMathParser;
import com.bestcode.mathparser.MathParserFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.Index;

/**
 * Question represents a learning assessment question entity supporting multiple question types.
 * 
 * This entity manages questions used in assignments and quizzes, supporting the following types:
 * - multiple_choice: Single correct answer from list of choices
 * - checkbox: Multiple correct answers (all must be selected)
 * - true_false: Boolean true/false answer
 * - fill_in_blank: Text-based short answer
 * - numeric: Numeric answer with precision/significant figure validation
 * 
 * Question Features:
 * - Parameterized questions: Uses variables a,b,c,d with ranges (e.g., "a 3:6 b 5:8")
 * - Math expression parsing: Supports equations with #-delimited expressions
 * - Smart numeric formatting: Decimal, scientific notation, or significant figures
 * - Scrambled choices: Can randomize multiple choice/checkbox options
 * - Performance tracking: Tracks correct answers and total attempts
 * 
 * The prompt text supports:
 * - Mathematical expressions delimited by # (e.g., "#3*a+2#")
 * - Parameterized variables a,b,c,d that are substituted before parsing
 * - Fractions in format (|numerator|denominator|)
 * - Text numbers like "forty-two" converted to digits
 * 
 * @see Assignment for question usage in assignments
 * @see Topic for question organization
 */
@Entity
public class Question implements Serializable, Cloneable {
	@Serial
	private static final long serialVersionUID = 137L;
	
	/** Question ID (database key) */
	@Id Long id;
	
	/** Topic ID this question belongs to (indexed for queries) */
	@Index Long topicId; 
	
	/** Assignment type (indexed): quiz, homework, exam, etc. */
	@Index String assignmentType;
	
	/** Question type (indexed): multiple_choice, checkbox, true_false, fill_in_blank, numeric */
	@Index String type;
	
	/** Question prompt/stem (supports math expressions with # delimiters and variables a,b,c,d) */
	String prompt;
	
	/** Correct answer(s) - varies by type: single char for multiple_choice, string for checkbox, etc. */
	String correctAnswer;
	
	/** Units of measurement for numeric answers (e.g., "kg", "m/s", "%") */
	String units;
	
	/** List of answer choices for multiple_choice and checkbox questions */
	List<String> choices = new ArrayList<String>();
	
	/** Required precision for numeric answers (e.g., 0.01 for 2 decimal places; 0.0 = exact match required) */
	double requiredPrecision = 0;
	
	/** Number of significant figures for numeric answers (0 = no sig fig requirement) */
	int significantFigures = 0;
	
	/** Parameter definition string: "a 3:6 b 5:8 c -3:3 d 1:1" defines ranges for random values */
	String parameterString;
	
	/** Whether to randomize choice order for multiple_choice and checkbox questions */
	boolean scrambleChoices;
	
	/** Whether fill_in_blank answers must match spelling exactly */
	boolean strictSpelling;
	
	/** Count of student responses marked as correct (for analytics) */
	Integer nCorrectAnswers = null;
	
	/** Total count of student responses (for analytics) */
	Integer nTotalAttempts = null;
	
	/** Runtime parameter values: a, b, c, d (not persisted; set by setParameters()) */
	@Ignore int[] parameters = {0, 0, 0, 0};
	
	// Question type constants
	public static final String TYPE_MULTIPLE_CHOICE = "multiple_choice";
	public static final String TYPE_CHECKBOX = "checkbox";
	public static final String TYPE_TRUE_FALSE = "true_false";
	public static final String TYPE_FILL_IN_BLANK = "fill_in_blank";
	public static final String TYPE_NUMERIC = "numeric";
	public static final String TYPE_ESSAY = "essay";
	
	// Numeric answer format type constants
	private static final int FORMAT_EXACT = 0;        // Exact value match
	private static final int FORMAT_SIG_FIGS = 1;     // Significant figures format
	private static final int FORMAT_PRECISION = 2;    // Precision percentage match
	private static final int FORMAT_SIG_FIGS_PRECISION = 3;  // Both sig figs and precision
	
	// HTML/UI formatting constants
	private static final String STYLE_INSTRUCTION = "color:#EE0000;font-size: small;";
	private static final String STYLE_PLACEHOLDER = "color: gray; font-size: 0.8em;";
	private static final String HTML_UL_START = "<ul>";
	private static final String HTML_UL_END = "</ul>";
	private static final String HTML_LI_START = "<li>";
	private static final String HTML_LI_END = "</li>";
	private static final String HTML_BR_DOUBLE = "<br/><br/>";
	
	// Decimal format patterns
	private static final String FORMAT_SMALL_INTEGER = "0";
	private static final String FORMAT_DECIMAL = "0.0#####";
	private static final String FORMAT_SCIENTIFIC = "0.####E0";
	
	// Numeric formatting thresholds
	private static final double THRESHOLD_INTEGER = 100.0;
	private static final double THRESHOLD_EXPONENTIAL_UPPER = 1.0E5;
	private static final double THRESHOLD_EXPONENTIAL_LOWER = 1.0E-2;
	
	/** Default constructor for Objectify ORM deserialization */
	Question() {}

	/**
	 * Constructs a Question with the specified type.
	 * @param type the question type (multiple_choice, checkbox, true_false, fill_in_blank, numeric)
	 */
	Question(String type) {
		this.type = type;
		this.correctAnswer = "";
		this.parameterString = "";
	}
	
	/**
	 * Constructs a complete Question with all properties.
	 * 
	 * @param topicId the topic this question belongs to
	 * @param prompt the question text (supports # delimited math expressions and variables)
	 * @param type the question type
	 * @param choices list of answer choices (for multiple_choice and checkbox)
	 * @param requiredPrecision precision requirement for numeric answers
	 * @param significantFigures significant figures requirement for numeric answers
	 * @param correctAnswer the correct answer or answers
	 * @param units units of measurement for numeric answers
	 * @param pointValue point value for this question
	 * @param parameterString parameter definitions (e.g., "a 3:6 b 5:8")
	 * @param hint hint text for students
	 * @param solution detailed solution text
	 * @param authorId user ID of question author
	 * @param contributorId user ID of contributor
	 * @param editorId user ID of editor
	 * @param notes internal notes about the question
	 */
	Question (long topicId, String prompt, String type, List<String> choices,
			double requiredPrecision, int significantFigures, String correctAnswer, String units, int pointValue, String parameterString,
			String hint, String solution, String authorId, String contributorId, String editorId, String notes) {
		this.topicId = topicId;
		this.prompt = prompt;
		this.type = type;
		this.choices = choices;
		this.requiredPrecision = requiredPrecision;
		this.significantFigures = significantFigures;
		this.correctAnswer = correctAnswer;
		this.units = units;
	
		this.parameterString = parameterString;
	}

	Question (JsonObject jq) {
		type = jq.get("type").getAsString();
		prompt = jq.get("prompt").getAsString();
		correctAnswer = jq.get("correctAnswer").getAsString();
		switch (type) {
		case TYPE_MULTIPLE_CHOICE:
		case TYPE_CHECKBOX:
			JsonArray chs = jq.get("choices").getAsJsonArray();
			for (int i=0;i<chs.size();i++) {
				choices.add(chs.get(i).getAsString());
			}
			scrambleChoices = true;  //jq.get("scrambled").getAsBoolean();
			break;
		case TYPE_NUMERIC:
			JsonElement unts = jq.get("units");
			if (unts != null) units = unts.getAsString();
			break;
		}
	}
	
	/**
	 * Returns the correct answer(s) formatted as HTML for display.
	 * 
	 * The formatting varies by question type:
	 * - multiple_choice: Returns the selected choice text
	 * - checkbox: Returns an HTML list of all correct choices
	 * - true_false: Returns the boolean answer
	 * - fill_in_blank: Returns the first correct answer
	 * - numeric: Returns the parsed numerical value with optional units
	 * 
	 * @return HTML-formatted correct answer string, or null if type is unrecognized
	 */
	public String getCorrectAnswer() {
		switch (type) {
		case TYPE_MULTIPLE_CHOICE:
			return choices.get(correctAnswer.charAt(0)-'a') + HTML_BR_DOUBLE;
		case TYPE_TRUE_FALSE:
			return correctAnswer + HTML_BR_DOUBLE;
		case TYPE_CHECKBOX:
			StringBuilder buf = new StringBuilder(HTML_UL_START);
			for (int i=0; i<correctAnswer.length(); i++) {
				buf.append(HTML_LI_START).append(choices.get(correctAnswer.charAt(i)-'a')).append(HTML_LI_END);
			}
			buf.append(HTML_UL_END);
			return buf.toString();
		case TYPE_FILL_IN_BLANK:
			String[] answers = correctAnswer.split(",");
			return answers[0] + HTML_BR_DOUBLE;
		case TYPE_NUMERIC:
			return parseString(correctAnswer) + (units==null?"":(" " + units)) + HTML_BR_DOUBLE;
		default: return null;
		}
	}
	
	public boolean requiresParser() {
		return prompt.contains("#") || this.parameterString != null && !this.parameterString.isEmpty();
	}
	
	public void setParameters() {
		if (this.requiresParser()) setParameters(-1L); // set parameters with a random seed
	}

	public void setParameters(long seed) {
		if (!this.requiresParser()) return;     // bulletproofing

		Random rand = new Random();
		// use seed=-1 for randomly fluctuating parameters, non-zero for deterministic pseudo-random
		if (seed != -1) rand.setSeed((long)seed);

		char p = 'a';
		while (p <= 'd') {
			try { // choose parameters from a string like "a 3:6 b 5:8 c -3:3 d 1:1"
				int i = parameterString.indexOf(p);       // find the index of parameter 'a'
				int j = parameterString.indexOf(':',i);   // find the index of the separator
				int k = parameterString.indexOf(" ",j);   // find the trailing white space
				if (k < 0) k = parameterString.length();  // or the end of the string
				int low = Integer.parseInt(parameterString.substring(i+2,j)); // extract the low limit 3
				int hi = Integer.parseInt(parameterString.substring(j+1,k));  // extract the high limit 6
				parameters[p-'a'] = low + rand.nextInt(hi-low+1);             // randomly select the parameter
				p++;                                      // repreat the process for the next parameter
			}
			catch (Exception e) {
				parameters[p-'a'] = 1;    // if exception is thrown, set parameter value to 1
				p++;                      // and try the next parameter
			}
		}  
	}
	
	int getNumericItemType() {
		if (requiredPrecision==0.0 && significantFigures==0) return FORMAT_EXACT;      // Q: rules/format  A: exact value match
		else if (requiredPrecision==0.0 && significantFigures!=0) return FORMAT_SIG_FIGS; // Q: show sig figs A: exact value match
		else if (requiredPrecision!=0.0 && significantFigures==0) return FORMAT_PRECISION; // Q: rules/format  A: value agrees to %
		else if (requiredPrecision!=0.0 && significantFigures!=0) return FORMAT_SIG_FIGS_PRECISION; // Q: show sig figs A: value agrees to %
		return FORMAT_EXACT; //default case
	}
	
	/**
	 * Parses a string containing mathematical expressions and variables.
	 * 
	 * This method:
	 * 1. Converts fractions (|numerator|denominator|) to HTML form
	 * 2. Converts text numbers (e.g., "forty-two") to digits
	 * 3. Substitutes parameters a,b,c,d with their current values
	 * 4. Evaluates mathematical expressions delimited by #
	 * 5. Formats output based on significant figures requirement
	 * 
	 * @param raw the raw string potentially containing math expressions and variables
	 * @return the parsed and formatted string, or empty string if null
	 */
	public String parseString(String raw) {
		int itemType = getNumericItemType();
		switch (itemType) {
			case FORMAT_EXACT: return parseString(raw,1);  // return with historical rules-based display
			case FORMAT_SIG_FIGS: return parseString(raw,2);  // output with sig figs
			case FORMAT_PRECISION: return parseString(raw,1);  // output with historical rules-based display
			case FORMAT_SIG_FIGS_PRECISION: return parseString(raw,2);  // output with sig figs
			default: return "";  // invalid item type
		}
	}
	
	public String parseString(String raw, int outputType) {  
		// this section uses a fully licensed version of the Jbc Math Parser
		// from bestcode.com (license purchased by C. Wight on Nov 18, 2007)
		if (raw==null) return null;
		
		raw = parseFractions(raw);  // converts a fraction like (|3|2|) to readable HTML form
		raw = parseNumber(raw);		// converts entire input like "forty-two" to "42"
		
		IMathParser parser = MathParserFactory.create();
		try {
			parser.setVariable("a",parameters[0]);
			parser.setVariable("b",parameters[1]);
			parser.setVariable("c",parameters[2]);
			parser.setVariable("d",parameters[3]);
		}
		catch (Exception e) { // parser parameters not set properly; bail on parsing
			return raw==null?"":raw;
		}

		if (raw == null) raw = "";
		String[] pieces = raw.split("#");
		StringBuilder buf = new StringBuilder();
		
		for (int i=0;i<pieces.length;i++) {
			try {
				parser.setExpression(pieces[i]);
				double value = parser.getValue();
				String fmt = "%." + significantFigures + "G";
				/*  There are three styles of parser output:
				 *  0 - raw output from the JbcParser unit
				 *  1 - ChemVantage historical rules-based output for integers, floats and exponential styles
				 *  2 - Output containing the specified number of significant digits
				 */
				switch (outputType) {
					case FORMAT_EXACT:
						buf.append(value);
						break;
					case FORMAT_SIG_FIGS:
						DecimalFormat df = new DecimalFormat();
						if ((Math.abs(value) < THRESHOLD_INTEGER) && (value - Math.floor(value) == 0)) {
							df.applyPattern(FORMAT_SMALL_INTEGER); // small integer output
						} else if ((Math.abs(value) < THRESHOLD_EXPONENTIAL_UPPER) && (Math.abs(value) > THRESHOLD_EXPONENTIAL_LOWER)) {
							df.applyPattern(FORMAT_DECIMAL); // use decimal number
						} else {
							df.applyPattern(FORMAT_SCIENTIFIC); // use scientific notation
						}
						buf.append(df.format(value)); 
						break;
					case FORMAT_PRECISION:
						buf.append(fmt.formatted(value));
						break;
					case FORMAT_SIG_FIGS_PRECISION:
						buf.append(fmt.formatted(value));
						break;
					default:
						buf.append(value);
				}
			} catch (Exception e) {
				buf.append(pieces[i]);  // expression could not be parsed; probably text - return unchanged
			}
		}
		return buf.toString();
	}
	
	public String print() {
		return print("","");
	}
	
	public String print(String showWork,String studentAnswer) {
		return print(showWork,studentAnswer,null);
	}
	
	public String print(String showWork,String studentAnswer,Integer attemptsRemaining) {
		StringBuilder buf = new StringBuilder();
		String placeholder = attemptsRemaining==null?"":(" (" + attemptsRemaining + " attempt" + (attemptsRemaining==1?"":"s") + " remaining)");
		char choice = 'a';
		List<Character> choice_keys = new ArrayList<Character>();
		Random rand = new Random();
		switch (type) {
		case TYPE_MULTIPLE_CHOICE:
			buf.append(prompt + "<br/>");
			for (int i=0; i<choices.size(); i++) choice_keys.add(Character.valueOf((char)('a'+i)));
			buf.append("<span style='" + STYLE_INSTRUCTION + "'>Select only the best answer:</span><br/>");
			while (choice_keys.size()>0) {
				choice = choice_keys.remove(scrambleChoices?rand.nextInt(choice_keys.size()):0);
				buf.append("<label><input type=radio name=" + this.id + " value=" + choice + (studentAnswer.indexOf(choice)>=0?" CHECKED /> ":" /> ") + choices.get(choice-'a') + "</label><br/>");
			}
			if (!placeholder.isEmpty()) buf.append("<span style='" + STYLE_PLACEHOLDER + "'>" + placeholder + "</span><br/>");
			break;
		case TYPE_TRUE_FALSE:
			buf.append(prompt);
			buf.append("<br/>");
			buf.append("<span style='" + STYLE_INSTRUCTION + "'>Select true or false:</span><br/>");
			buf.append("<label><input type=radio name=" + this.id + " value='true'" + (studentAnswer.equals("true")?" CHECKED />":" />") + " True</label><br/>");
			buf.append("<label><input type=radio name=" + this.id + " value='false'" + (studentAnswer.equals("false")?" CHECKED />":" />") + " False</label><br/>");
			if (!placeholder.isEmpty()) buf.append("<span style='" + STYLE_PLACEHOLDER + "'>" + placeholder + "</span><br/>");
			break;
		case TYPE_CHECKBOX:
			buf.append(prompt + "<br/>");
			for (int i=0; i<choices.size(); i++) choice_keys.add(Character.valueOf((char)('a'+i)));
			buf.append("<span style='" + STYLE_INSTRUCTION + "'>Select all of the correct answers:</span><br/>");
			while (choice_keys.size()>0) {
				choice = choice_keys.remove(scrambleChoices?rand.nextInt(choice_keys.size()):0);
				buf.append("<label><input type=checkbox name=" + this.id + " value=" + choice + (studentAnswer.indexOf(choice)>=0?" CHECKED /> ":" /> ") + choices.get(choice-'a') + "</label><br/>");
			}
			if (!placeholder.isEmpty()) buf.append("<span style='" + STYLE_PLACEHOLDER + "'>" + placeholder + "</span><br/>");
			break;
		case TYPE_FILL_IN_BLANK: 
			buf.append("<label for=" + this.id + ">" + prompt + "</label>");
			buf.append("<br/>");
			buf.append("<span style='" + STYLE_INSTRUCTION + "'>Enter the correct word or phrase:</span><br/>");
			buf.append("<input id=" + this.id + " type=text aria-label='student answer' name=" + this.id + " value='" + quot2html(studentAnswer) + "' placeholder='" + placeholder + "' />");
			buf.append("&nbsp;" + units + "<br/><br/>");
			break;
		case TYPE_NUMERIC:
			buf.append(parseString(prompt));
			buf.append("<br/>");
			buf.append("<div id=showWork" + this.id + " style='display:none'>"
					+ "<label>Show your work:<br/><TEXTAREA NAME=ShowWork" + this.id + " ROWS=5 COLS=50 WRAP=SOFT "
					+ "maxlength=500 placeholder='Show your work here' aria-label='show your work here'>" + (showWork==null?"":showWork) + "</TEXTAREA>"
					+ "</label><br/></div>");
			switch (getNumericItemType()) {
			case 0: buf.append("<span style='" + STYLE_INSTRUCTION + "'>Enter the exact value. <a role='button' href=# onclick=\"alert('Your answer must have exactly the correct value. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 1: buf.append("<span style='" + STYLE_INSTRUCTION + "'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 2: int sf = (int)Math.ceil(-Math.log10(requiredPrecision/100.))+1;
				buf.append("<span style='" + STYLE_INSTRUCTION + "'>Include at least " + sf + " significant figures in your answer. <a role='button' href=# onclick=\"alert('To be scored correct, your answer must agree with the correct answer to at least " + sf + " significant figures. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 3: buf.append("<span style='" + STYLE_INSTRUCTION + "'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			default:
			}
			buf.append("<label><input aria-label='student answer' size=25 type=text name=" + this.id + " id=answer" + this.id + " value='" + studentAnswer + "' placeholder='" + placeholder + "' onFocus=showWorkBox('" + this.id + "'); />");
			buf.append("&nbsp;" + parseString(units) + "</label><br/><br/>");
			break;        
		case TYPE_ESSAY:
			buf.append(prompt);
			buf.append("<br/>");
			buf.append("<span style='color:#990000;font-size:small;'>(800 characters max):</span><br/>");
			buf.append("<textarea id=" + this.id + " aria-label='enter your essay here' name=" + this.id 
					+ " rows=5 cols=60 wrap=soft placeholder='Enter your answer here' maxlength=800 >" + studentAnswer + "</textarea><br>");
			break;
		}
		return buf.toString();
	}

	String printAll() {
		// use this method to display an example of the question, correct answer and solution
		StringBuffer buf = new StringBuffer();
		char choice = 'a';
		List<Character> choice_keys = new ArrayList<Character>();
		for (int i=0; i<choices.size(); i++) choice_keys.add(Character.valueOf((char)('a'+i)));
		Random rand = new Random();
		switch (type) {
		case TYPE_MULTIPLE_CHOICE: // Multiple Choice
			buf.append(prompt + "<br/>");
			buf.append("<span style='" + STYLE_INSTRUCTION + "'>Select only the best answer:</span><br/>");
			buf.append("<UL" + (scrambleChoices?" style=color:red":"") + ">");
			while (choice_keys.size()>0) {
				choice = choice_keys.remove(scrambleChoices?rand.nextInt(choice_keys.size()):0);
				buf.append("<LI>" 
						+ (correctAnswer.indexOf(choice)>=0?"<B>":"<FONT COLOR=#888888>")
						+ quot2html(choices.get(choice-'a'))
						+ (correctAnswer.indexOf(choice)>=0?"</B>":"</FONT>")
						+ "</LI>");
			}
			buf.append("</UL>");
			break;
		case TYPE_TRUE_FALSE:
			buf.append(prompt + "<br/>");
			buf.append("<span style='" + STYLE_INSTRUCTION + "'>Select true or false:</span><UL>");
			buf.append("<LI>" 
					+ (correctAnswer.equals("true")?"<B>True</B>":"<FONT COLOR=#888888>True</FONT>") 
					+ "</LI>");
			buf.append("<LI>" 
					+ (correctAnswer.equals("false")?"<B>False</B>":"<FONT COLOR=#888888>False</FONT>") 
					+ "</LI>");
			buf.append("</UL>");
			break;
		case TYPE_CHECKBOX: 
			buf.append(prompt + "<br/>");
			buf.append("<span style='" + STYLE_INSTRUCTION + "'>Select all of the correct answers:</span>");
			buf.append("<UL" + (scrambleChoices?" style=color:red":"") + ">");
			while (choice_keys.size()>0) {
				choice = choice_keys.remove(scrambleChoices?rand.nextInt(choice_keys.size()):0);
				buf.append("<LI>"
						+ (correctAnswer.indexOf(choice)>=0?"<B>":"<FONT COLOR=#888888>")
						+ quot2html(choices.get(choice-'a'))
						+ (correctAnswer.indexOf(choice)>=0?"</B>":"</FONT>")
						+ "</LI>");
			}
			buf.append("</UL>");
			break;
		case TYPE_FILL_IN_BLANK:
			buf.append(prompt + "<br/>");
			buf.append("<span style='" + STYLE_INSTRUCTION + "'>Enter the correct word or phrase:</span><br/>");
			buf.append("<span style='border: 1px solid black'>"
					+ "<b>" + (this.hasACorrectAnswer()?quot2html(correctAnswer):"&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;") + "</b>"
					+ "</span>" + (this.strictSpelling?" <span style='font-size:0.5em'>(strict spelling)</span>":"") + "<br/><br/>");
			break;
		case TYPE_NUMERIC:
			buf.append(parseString(prompt) + "<br/>");
			switch (getNumericItemType()) {
			case 0: buf.append("<span style='color:#EE0000;font-size: small;'>Enter the exact value. <a role='button' href=# onclick=\"alert('Your answer must have exactly the correct value. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 1: buf.append("<span style='color:#EE0000;font-size: small;'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 2: int sf = (int)Math.ceil(-Math.log10(requiredPrecision/100.))+1;
				buf.append("<span style='color:#EE0000;font-size: small;'>Include at least " + sf + " significant figures in your answer. <a role='button' href=# onclick=\"alert('To be scored correct, your answer must agree with the correct answer to at least " + sf + " significant figures. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 3: buf.append("<span style='color:#EE0000;font-size: small;'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			default:
			}			
			buf.append("<span style='border: 1px solid black'>"
					+ "<b>" + (this.hasACorrectAnswer()?parseString(correctAnswer):"&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;") + "</b>"
					+ "</span>");
			buf.append("&nbsp;" + parseString(units) + "<br/><br/>");
			break;        
		case TYPE_ESSAY:
			buf.append(prompt);
			buf.append("<br/>");
			buf.append("<span style='color:#990000;font-size:small;'>(800 characters max):</span><br/>");
			buf.append("<textarea id=" + this.id + " name=" + this.id + " rows=5 cols=60 wrap=soft placeholder='Enter your answer here' "				
					+ "onKeyUp=document.getElementById('" + this.id + "').value=document.getElementById('" + this.id + "').value.substring(0,800);}>"
					+ "</textarea><br/><br/>");
			break;
		}
		return buf.toString();
	}

	String printAllToStudents(String studentAnswer) {
		return printAllToStudents(studentAnswer,true,true,"");
	}
	
	String printAllToStudents(String studentAnswer,boolean showDetails) {
		return printAllToStudents(studentAnswer,showDetails,true,"");
	}
	
	String printAllToStudents(String studentAnswer,boolean showDetails,boolean reportable) {
		return printAllToStudents(studentAnswer,showDetails,reportable,"");
	}
	
	String printAllToStudents(String studentAnswer,boolean showDetails,boolean reportable,String showWork) {
		// use this method to display an example of the question, correct answer and solution
		// this differs from printAll() because only the first of several 
		// correct fill-in-word answers is presented, and choices are not scrambled
		// showDetails enables display of Solution to numeric problems (default = true)
		StringBuilder buf = new StringBuilder("<a name=" + this.id + "></a>");
		char choice = 'a';
		switch (type) {
		case TYPE_MULTIPLE_CHOICE: // Multiple Choice
			buf.append(parseString(prompt) + "<br/>");
			buf.append("<span style='" + STYLE_INSTRUCTION + "'>Select only the best answer:</span><br/>");
			for (int i = 0; i < choices.size(); i++) {
				buf.append("&nbsp;" + choice + ". "
						+ (showDetails && correctAnswer.indexOf(choice)>=0?"<B>":"<FONT COLOR=#888888>")
						+ quot2html(choices.get(i))
						+ (showDetails && correctAnswer.indexOf(choice)>=0?"</B>":"</FONT>") + "<br/>");
				choice++;
			}
			break;
		case TYPE_TRUE_FALSE:
			buf.append(prompt + "<br/>");
			buf.append("<span style='" + STYLE_INSTRUCTION + "'>Select true or false:</span><UL>");
			buf.append("<LI>" 
					+ (showDetails && correctAnswer.equals("true")?"<B>True</B>":"<FONT COLOR=#888888>True</FONT>") 
					+ "</LI>");
			buf.append("<LI>" 
					+ (showDetails && correctAnswer.equals("false")?"<B>False</B>":"<FONT COLOR=#888888>False</FONT>")
					+ "</LI>");
			buf.append("</UL>");
			break;
		case TYPE_CHECKBOX: 
			buf.append(prompt + "<br/>");
			buf.append("<span style='" + STYLE_INSTRUCTION + "'>Select all of the correct answers:</span><br/>");
			for (int i = 0; i < choices.size(); i++) {
				buf.append("&nbsp;" + choice + ". "
						+ (showDetails && correctAnswer.indexOf(choice)>=0?"<B>":"<FONT COLOR=#888888>")
						+ quot2html(choices.get(i))
						+ (showDetails && correctAnswer.indexOf(choice)>=0?"</B>":"</FONT>") + "<br/>");
				choice++;
			}
			break;
		case TYPE_FILL_IN_BLANK: 
			buf.append(prompt + "<br/>");
			buf.append("<span style='" + STYLE_INSTRUCTION + "'>Enter the correct word or phrase:</span><br/>");
			String[] answers = correctAnswer.split(",");
			buf.append("<span style='border: 1px solid black'>"
					+ (showDetails?"<b>" + quot2html(answers[0]) + "</b>":"&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;")
					+ "</span>");
			break;
		case TYPE_NUMERIC:
			buf.append(parseString(prompt) + "<br/>");
			switch (getNumericItemType()) {
			case 0: buf.append("<span style='" + STYLE_INSTRUCTION + "'>Enter the exact value. <a role='button' href=# onclick=\"alert('Your answer must have exactly the correct value. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 1: buf.append("<span style='color:#EE0000;font-size: small;'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 2: int sf = (int)Math.ceil(-Math.log10(requiredPrecision/100.))+1;
				buf.append("<span style='color:#EE0000;font-size: small;'>Include at least " + sf + " significant figures in your answer. <a role='button' href=# onclick=\"alert('To be scored correct, your answer must agree with the correct answer to at least " + sf + " significant figures. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 3: buf.append("<span style='color:#EE0000;font-size: small;'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			default:
			}			
			buf.append("<span style='border: 1px solid black'>"
					+ (showDetails?"<b>" + parseString(correctAnswer) + "</b>":"&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;")
					+ "</span>");
			buf.append("&nbsp;" + (units==null?"":parseString(units)) + "<br/>");
			break;        
		case TYPE_ESSAY:
			buf.append(prompt);
			buf.append("<br/>");
			buf.append("<span style='color:#990000;font-size:small;'>(800 characters max):</span><br/>");
			break;
		}
		
		buf.append("<br/>");
		if (studentAnswer==null || studentAnswer.isEmpty()) buf.append("<b>No answer was submitted for this question item.</b><p></p>");
		if (type.equals(TYPE_ESSAY)) {
			buf.append("<b>The answer submitted was: </b><br/>" + studentAnswer + "<br/>");
		} else {
			buf.append("<b>The answer submitted was: " + studentAnswer + "</b>&nbsp;");
			if (this.isCorrect(studentAnswer)) buf.append("&nbsp;<img src='/images/checkmark.gif' alt='Check mark' align=bottom>");
			else if (this.agreesToRequiredPrecision(studentAnswer)) buf.append("<img src='/images/partCredit.png' alt='minus 1 sig figs' align=middle>"
					+ "<br/>Your answer must have exactly " + significantFigures + " significant digits.<br/>If your answer ends in a zero, then it must also have a decimal point to indicate which digits are significant.");
			else buf.append("<img src='/images/xmark.png' alt='X mark' align=middle>");
			buf.append("<br/><br/>");
		}
		
		if (reportable) {
			try {
				studentAnswer = URLEncoder.encode(studentAnswer,"UTF-8");  // to send with URL
			} catch (Exception e) {}
			buf.append("<div id='feedback" + this.id + "'>");
			buf.append("<FORM id='suggest" + this.id + "' >"
					+ "<INPUT role='button' aria-label='Report a problem with this question' TYPE=BUTTON VALUE='Report a problem with this question' "
					+ "onClick=\"javascript:getElementById('form" + this.id + "').style.display='';this.style.display='none'\" />"
					+ "<div id='form" + this.id + "' style='display: none'>");

			buf.append("<span style=color:red><br/>");
			switch (type) {
			case TYPE_MULTIPLE_CHOICE: buf.append("Reminder: The correct answer is shown in bold print above."); break; // MULTIPLE_CHOICE
			case TYPE_TRUE_FALSE: buf.append("Reminder: The correct answer is shown in bold print above."); break; // TRUE_FALSE
			case TYPE_CHECKBOX: buf.append("Reminder: The correct answers are shown in bold print above. You must select all of them."); break; // SELECT_MULTIPLE
			case TYPE_FILL_IN_BLANK: buf.append("Reminder: The correct answer will always form a complete, grammatically correct sentence."); break; // FILL_IN_WORD
			case TYPE_NUMERIC:
				switch (getNumericItemType()) {
				case 0: buf.append("Reminder: Your answer must have exactly the same value as the correct answer."); break;
				case 1: buf.append("Reminder: Your answer must have exactly the same value as the correct answer and must have " + significantFigures + " significant figures."); break;
				case 2: buf.append("Reminder: Your answer must be within " + requiredPrecision + "% of the correct answer."); break;
				case 3: buf.append("Reminder: Your answer must have " + significantFigures + " significant figures and be within " + requiredPrecision + "% of the correct answer."); break;
				default:
				}
			default:
			}		
			buf.append("</span><br/>");

			buf.append("Your Comment: <INPUT TYPE=TEXT SIZE=80 NAME=Notes /><br/>");
			buf.append("Your Email: <INPUT TYPE=TEXT SIZE=50 PLACEHOLDER=' optional, if you want a response' NAME=Email /><br/>");
			buf.append("<INPUT TYPE=BUTTON VALUE='Submit Feedback' "
					+ "onClick=\" return ajaxSubmit('/Feedback?UserRequest=ReportAProblem','" + this.id + "','" + Arrays.toString(this.parameters) + "','" + studentAnswer + "',encodeURIComponent(document.getElementById('suggest" + this.id + "').Notes.value),encodeURIComponent(document.getElementById('suggest" + this.id + "').Email.value)); return false;\" />"
					+ "</div></FORM><br/>");
			buf.append("</div>");
		}
		return buf.toString(); 
	}

	String printForSage() {
		StringBuffer buf = new StringBuffer();
		char choice = 'a';
		List<Character> choice_keys = new ArrayList<Character>();
		Random rand = new Random();
		switch (type) {
		case TYPE_MULTIPLE_CHOICE: // Multiple Choice
			buf.append(prompt + "\n");
			for (int i = 0; i < choices.size(); i++) {
			 choice_keys.add(Character.valueOf((char)('a'+i)));
			}
			buf.append("Select only the best answer:\n");
			while (choice_keys.size()>0) {
				choice = choice_keys.remove(scrambleChoices?rand.nextInt(choice_keys.size()):0);
				buf.append(choices.get(choice-'a') + "\n");
			}
			break;
		case TYPE_TRUE_FALSE: // True/False
			buf.append(prompt + "\n");
			buf.append("Select true or false:\n");
			buf.append("True\n");
			buf.append("False\n");
			break;
		case TYPE_CHECKBOX: // Select Multiple
			buf.append(prompt + "\n");
			for (int i = 0; i < choices.size(); i++) {
			 choice_keys.add(Character.valueOf((char)('a'+i)));
			}
			buf.append("Select all of the correct answers:\n");
			while (choice_keys.size()>0) {
				choice = choice_keys.remove(scrambleChoices?rand.nextInt(choice_keys.size()):0);
				buf.append(choices.get(choice-'a') + "\n");
			}
			break;
		case TYPE_FILL_IN_BLANK: // Fill-in-the-Word
			buf.append("Fill in the blank with the correct word or phrase:\n" 
					+ prompt + (units==null || units.isEmpty()?"":" _______________ " + units) + "\n");
			break;
		case TYPE_NUMERIC: // Numeric Answer
			buf.append(parseString(prompt) + "\n");
			switch (getNumericItemType()) {
			case 0: buf.append("Enter the exact value: "); break;
			case 1: buf.append("Enter the value with the appropriate number of significant figures:\n"); break;
			case 2: int sf = (int)Math.ceil(-Math.log10(requiredPrecision/100.))+1;
				buf.append("Include at least " + sf + " significant figures in your answer: \n"); break;
			case 3: buf.append("Enter the value with the appropriate number of significant figures \n"); break;
			default:
			}
			buf.append("____________" + parseString(units) + "\n");
			break;        
		}
		return buf.toString();	
	}
	
	String getExplanation() {
		StringBuffer buf = new StringBuffer();
		StringBuffer debug = new StringBuffer("Debug: ");
		try {
			BufferedReader reader = null;
			JsonObject api_request = new JsonObject();
			api_request.addProperty("model", Util.getGPTModel());
			  JsonObject prompt = new JsonObject();
			  prompt.addProperty("id", Util.getExplanationPromptId());
			    JsonObject variables = new JsonObject();
			    variables.addProperty("question_item", this.printForSage());
			    variables.addProperty("correct_answer", this.getCorrectAnswerForSage());
			  prompt.add("variables", variables);
			api_request.add("prompt", prompt);
			
			URL u = new URI(Util.getExplanationApiEndpoint()).toURL();
			HttpURLConnection uc = (HttpURLConnection) u.openConnection();
			uc.setRequestMethod("POST");
			uc.setDoInput(true);
			uc.setDoOutput(true);
			uc.setRequestProperty("Authorization", "Bearer " + Util.getOpenAIKey());
			uc.setRequestProperty("Content-Type", "application/json");
			uc.setRequestProperty("Accept", "application/json");
			OutputStream os = uc.getOutputStream();
			byte[] json_bytes = api_request.toString().getBytes("utf-8");
			os.write(json_bytes, 0, json_bytes.length);           
			os.close();
			
			int response_code = uc.getResponseCode();
			debug.append("HTTP Response Code: " + response_code);
			
			JsonObject api_response = null;
			if (response_code/100==2) {
				reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
				api_response = JsonParser.parseReader(reader).getAsJsonObject();
				debug.append(api_response.toString());
				reader.close();
			} else {
				reader = new BufferedReader(new InputStreamReader(uc.getErrorStream()));
				debug.append(JsonParser.parseReader(reader).getAsJsonObject().toString());
				reader.close();
			}
			
			// Find the output text buried in the response JSON:
			JsonArray output = api_response.get("output").getAsJsonArray();
			JsonObject message = null;
			JsonObject output_text = null;
			String explanation = null;
			for (JsonElement element0 : output) {
				message = element0.getAsJsonObject();
				if (message.has("content")) {
					JsonArray content = message.get("content").getAsJsonArray();
					for (JsonElement element1 : content) {
						output_text = element1.getAsJsonObject();
						if (output_text.has("text")) {
							explanation = output_text.get("text").getAsString();
							if (explanation==null) throw new Exception("Failed to read api_response.");
							break;
						}
					}
					break;
				}
			}
			debug.append("e");
			
			buf.append(explanation);
		} catch (Exception e) {
			buf.append("<br/>Sorry, an explanation is not available at this time. " + (e.getMessage()==null?e.toString():e.toString()) + "<p>" + debug.toString() + "<p>");
		}
		return buf.toString();
	}

public void addAttemptSave(boolean isCorrect) {
		if (nTotalAttempts==null) initializeCounters();
		nTotalAttempts++;
		if(isCorrect) nCorrectAnswers++;
		ofy().save().entity(this);
	}
	
	public void addAttemptsNoSave(int nTotal,int nCorrect) {
		if (nTotalAttempts==null) initializeCounters();
		this.nTotalAttempts += nTotal;
		this.nCorrectAnswers += nCorrect;
	}
	
	public String getSuccess() {
		if (nTotalAttempts==null) initializeCounters();
		return String.valueOf(nCorrectAnswers) + "/" + String.valueOf(nTotalAttempts) + "<br/>(" + getPctSuccess() + "%)";
	}
	
	public int getPctSuccess() {
		if (nTotalAttempts==null) initializeCounters();
		return nTotalAttempts==0?0:100*nCorrectAnswers/nTotalAttempts;
	}
	
	void initializeCounters() {
		nTotalAttempts = 0;
		nCorrectAnswers = 0;
	}
	
	String edit() {
		StringBuffer buf = new StringBuffer();
		try {
			String[] choiceNames = {"ChoiceAText","ChoiceBText","ChoiceCText","ChoiceDText","ChoiceEText"};
			char choice = 'a';
			switch (this.type) {
			case TYPE_MULTIPLE_CHOICE: 
				buf.append("Question Prompt:<br/><TEXTAREA name=Prompt rows=5 cols=50 wrap=soft>" 
						+ amp2html(prompt) + "</TEXTAREA><br/>");
				buf.append("<span style='" + STYLE_INSTRUCTION + "'>Select only the best choice:</span><br/>");
				for (int i=0;i<5;i++) { 
					if (i < choices.size()) {
						buf.append("<input type=radio name=CorrectAnswer value='" + choice + "'");
						if (correctAnswer.indexOf(choice) >= 0) buf.append(" CHECKED");
						buf.append("/><input size=30 name=" + choiceNames[i] + " value='"); 
						if (choices.size() > i) buf.append(quot2html(amp2html(choices.get(i))));
						buf.append("'/><br/>");
					}
					else buf.append("<input type=radio name=CorrectAnswer value=" + choice + " />"
							+ "<input size=30 name=" + choiceNames[i] + " /><br/>");
					choice++;
				}
				buf.append("<label>Check here to scramble the choices: <input type=checkbox name=ScrambleChoices value=true " + (this.scrambleChoices?"CHECKED":"") + " /></label><br/>");
				break;
			case TYPE_TRUE_FALSE:
				buf.append("Question Prompt:<br/><TEXTAREA name=Prompt rows=5 cols=50 wrap=soft>" 
						+ amp2html(prompt) + "</TEXTAREA><br/>");
				buf.append("<span style='" + STYLE_INSTRUCTION + "'>Select true or false:</span><br/>");
				buf.append("<input type=radio name=CorrectAnswer value='true'");
					if (correctAnswer.equals("true")) buf.append(" CHECKED");
				buf.append("/> True<br/>");
				buf.append("<input type=radio name=CorrectAnswer value='false'");
				if (correctAnswer.equals("false")) buf.append(" CHECKED");
				buf.append("/> False<br/>");
				break;
			case TYPE_CHECKBOX: 
				buf.append("Question Prompt:<br/><TEXTAREA name=Prompt rows=5 cols=50 wrap=soft>" 
						+ amp2html(prompt) + "</TEXTAREA><br/>");
				buf.append("<span style='" + STYLE_INSTRUCTION + "'>Select all of the correct answers:</span><br/>");
				for (int i=0;i<5;i++){
					if (i < choices.size()) {
						buf.append("<input type=checkbox name=CorrectAnswer value='" + choice + "'");
						if (correctAnswer.indexOf(choice) >= 0) buf.append(" CHECKED");
						buf.append("/><input size=30 name=" + choiceNames[i] + " value='"); 
						if (choices.size() > i) buf.append(quot2html(amp2html(choices.get(i))));
						buf.append("'/><br/>");
					}
					else buf.append("<input type=checkbox name=CorrectAnswer value=" + choice + " />"
							+ "<input size=30 name=" + choiceNames[i] + " /><br/>");
					choice++;
				}
				buf.append("<label>Check here to scramble the choices: <input type=checkbox name=ScrambleChoices value=true " + (this.scrambleChoices?"CHECKED":"") + " /></label><br/>");
				break;
			case TYPE_FILL_IN_BLANK:
				buf.append("Question Prompt:<br/><TEXTAREA name=Prompt rows=5 cols=50 wrap=soft>" 
						+ amp2html(prompt) + "</TEXTAREA><br/>");
				buf.append("<span style='" + STYLE_INSTRUCTION + "'>Enter the correct word or phrase.<br/>"
						+ "Multiple correct answers can be entered as a comma-separated list.</span><br/>");
				buf.append("<input type=text name=CorrectAnswer value=\"" 
						+ quot2html(amp2html(correctAnswer)) + "\"'/>&nbsp;&nbsp;");
				buf.append("<label><input type=checkbox name=StrictSpelling value=true " + (this.strictSpelling?"CHECKED":"") + " /> strict spelling</label><br/><br/>");
				break;
			case TYPE_NUMERIC:
				buf.append("Question Prompt:<br/><TEXTAREA name=Prompt rows=5 cols=60 wrap=soft>" 
						+ amp2html(prompt) + "</TEXTAREA><br/>");
				buf.append("<FONT SIZE=-2>Significant figures: <input size=5 name=SignificantFigures value='" + significantFigures + "'/> Required precision: <input size=5 name=RequiredPrecision value='" + requiredPrecision + "'/> (set to zero to require exact answer)</FONT><br/>");
				switch (getNumericItemType()) {
				case 0: buf.append("<span style='" + STYLE_INSTRUCTION + "'>Enter the exact value. <a role='button' href=# onclick=\"alert('Your answer must have exactly the correct value. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
				case 1: buf.append("<span style='" + STYLE_INSTRUCTION + "'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
				case 2: int sf = (int)Math.ceil(-Math.log10(requiredPrecision/100.))+1;
					buf.append("<span style='" + STYLE_INSTRUCTION + "'>Include at least " + sf + " significant figures in your answer. <a role='button' href=# onclick=\"alert('To be scored correct, your answer must agree with the correct answer to at least " + sf + " significant figures. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
				case 3: buf.append("<span style='" + STYLE_INSTRUCTION + "'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
				default:
				}			
				buf.append("Correct answer:");
				buf.append("<INPUT TYPE=TEXT NAME=CorrectAnswer VALUE='" + correctAnswer + "'/> ");
				buf.append(" Units:<INPUT TYPE=TEXT NAME=Units SIZE=8 VALUE='" 
						+ quot2html(amp2html(units)) + "'/><br/>");
				buf.append("Parameters:<input name=ParameterString value='" 
						+ parameterString + "'/><FONT SIZE=-2><a href=# onClick=\"javascript:document.getElementById('detail1').innerHTML="
						+ "'You may embed up to 4 parameters (a b c d) in a question using a parameter string like<br/>"
						+ "a 111:434 b 7:39<br/>"
						+ "This will randomly select integers for variables a and b from the specified ranges.<br/>"
						+ "Use these in math expressions with the pound sign delimeter (#) to create randomized data.<br/>"
						+ "Example: Compute the mass of sodium in #a# mL of aqueous #b/10# M NaCl solution.<br/>"
						+ "Correct answer: #22.9898*a/1000*b/10# g<p></p>"
						+ "You can also display fractions in vertical format using encoding like (|numerator|denominator|)<br/><br/>'\";>What's This?</a></FONT>");
				buf.append("<div id=detail1></div>");
				break;
			case TYPE_ESSAY: 
				buf.append("Question Prompt:<br/><TEXTAREA name=Prompt rows=5 cols=50 wrap=soft>" + amp2html(prompt) + "</TEXTAREA><br/>");
				buf.append("<span style='color:#990000;font-size:small;'>(800 characters max):</span><br/>");
				buf.append("<div style='border: solid 2px;width:300px;height:100px'></div>");
				buf.append("<br/>");
				break;
			}
		}
		catch (Exception e) {
			buf.append(e.toString());
		}
		return buf.toString();
	}

	boolean hasNoCorrectAnswer() {
		return this.correctAnswer == null || this.correctAnswer.isEmpty();
	}
	
	boolean hasACorrectAnswer() {
		return !hasNoCorrectAnswer();
	}
	
	boolean isCorrect(String studentAnswer){
		if (studentAnswer == null || studentAnswer.isEmpty() || hasNoCorrectAnswer()) return false;
		switch (type) {
		case TYPE_FILL_IN_BLANK:
			Collator compare = Collator.getInstance();
			compare.setStrength(Collator.PRIMARY);
			studentAnswer = studentAnswer.replaceAll("\\W", ""); // removes any character not a letter, digit or underscore
			String[] correctAnswers = correctAnswer.split(","); // break comma-separated list into array
			for (int i=0;i<correctAnswers.length;i++) {
				correctAnswers[i] = correctAnswers[i].replaceAll("\\W","");
				if (compare.equals(studentAnswer,correctAnswers[i])) return true;
				else if (!strictSpelling && closeEnough(studentAnswer.toLowerCase(),correctAnswers[i].toLowerCase())) return true;
			}
			return false;
		case TYPE_NUMERIC:
			return hasCorrectSigFigs(studentAnswer) && agreesToRequiredPrecision(studentAnswer);
		default:  // exact match to non-numeric answer (MULTIPLE_CHOICE, TRUE_FALSE, SELECT_MULTIPLE)
			return correctAnswer.equals(studentAnswer);
		}
	}
	
	boolean closeEnough(String studentAnswer,String correctAnswer) {
		if (correctAnswer.length() < 4) return false;  			// exact answer needed for 3-char answers
		int maxEditDistance = correctAnswer.length()<6?1:2;		// 1 error allowed for 4,5-char answers; otherwise 2 errors allowed

		if (Math.abs(correctAnswer.length()-studentAnswer.length())>maxEditDistance) return false;   // trivial estimate of min edit distance

		if (editDist(studentAnswer,correctAnswer,studentAnswer.length(),correctAnswer.length(),0,maxEditDistance) > maxEditDistance) return false;

		return true;
	}
	
	static int editDist(String str1, String str2, int m, int n, int d, int maxEditDistance) {
		/* 
		 * Modified Levenshtein algorithm for computing the edit distance between 2 strings of length m and n, and comparing it to a
		 * maxEditDistance, keeping in mind that insertions or deletions that increase the distance from the m=n diagonal incur extra cost.
		 */
		if (m == 0) return n; 	  
		if (n == 0) return m; 

		if (str1.charAt(m - 1) == str2.charAt(n - 1)) return editDist(str1, str2, m - 1, n - 1, d, maxEditDistance); 
		else if (d >= maxEditDistance) return d+1;
		else return 1 + min(d+1+Math.abs(n-1-m)>maxEditDistance?maxEditDistance:editDist(str1, str2, m, n-1, d+1, maxEditDistance),  // Insert 
							d+1+Math.abs(n-m+1)>maxEditDistance?maxEditDistance:editDist(str1, str2, m-1, n, d+1, maxEditDistance),  // Remove 
							d+1+Math.abs(n-m)>maxEditDistance?maxEditDistance:editDist(str1, str2, m-1, n-1, d+1, maxEditDistance)); // Replace 
	}

	static int min(int x, int y, int z) { /*This code is contributed by Rajat Mishra*/
		if (x <= y && x <= z) return x; 
		if (y <= x && y <= z) return y; 
		else return z; 
	} 

	boolean hasCorrectSigFigs(String studentAnswer) {
		if (significantFigures==0) return true;  // no sig figs required
		
		studentAnswer = studentAnswer.replaceAll(",", "").replaceAll("\\s", "");  // removes comma separators and whitespace from numbers
		
		int exponentPosition = studentAnswer.toUpperCase().indexOf("E");  		// turns "e" to "E"
		String mantissa = exponentPosition>=0?studentAnswer.substring(0,exponentPosition):studentAnswer;
		
		// check to see if the value has a trailing zero before the decimal place
		// this check has been disabled to forgive lack of a trailing decimal
		//if (mantissa.indexOf(".")==-1 && mantissa.endsWith("0")) return false;
		
		// strip leading (non-significant) zeros, decimals and signs
		while (mantissa.startsWith("0") || mantissa.startsWith(".") || mantissa.startsWith("-") || mantissa.startsWith("+")) mantissa = mantissa.substring(1);
		
		// remove embedded decimal point, if any
		mantissa = mantissa.replace(".","");
		
		// see if number of remaining digits matches this.significantFigures
		if (mantissa.length()==this.significantFigures) return true;
		
		return false;
	}
	
	boolean agreesToRequiredPrecision(String studentAnswer) {
		// This method is used for numeric questions to determine if the student's response agrees with the correct answer to within the required precision
		if (!"numeric".equals(type) || studentAnswer == null || studentAnswer.isEmpty() || hasNoCorrectAnswer()) return false;
		if (studentAnswer.length()<3 && (studentAnswer.endsWith("+") || studentAnswer.endsWith("-"))) {  // deal with oxidation state like 5+ or 3-
			char sign = studentAnswer.charAt(studentAnswer.length()-1);
			studentAnswer = sign + studentAnswer.substring(0,studentAnswer.length()-1);
		}
		try {
			studentAnswer = studentAnswer.replaceAll(",", "").replaceAll("\\s", "").toUpperCase();  // removes comma separators and whitespace from numbers, turns e to E
			double dStudentAnswer = Double.parseDouble(parseString(studentAnswer,0));
			double dCorrectAnswer = Double.parseDouble(parseString(correctAnswer));
			if (requiredPrecision==0.) return Double.compare(dStudentAnswer,dCorrectAnswer)==0.; // exact match required
			if (dCorrectAnswer==0.) return dStudentAnswer==0.;  // exact match is always required if the correct answer is zero; avoids divide-by-zero in next line
			else return (Math.abs((dStudentAnswer-dCorrectAnswer)/dCorrectAnswer)*100 <= requiredPrecision?true:false);  // checks for agreement to required precision
		} catch (Exception e) {
			return false;
		}
	}
	
	String parseNumber(String input) {  // converts input like "forty-two" to "42"
		if (input==null || input.isEmpty()) return input;
		String words = input.replaceAll("-", " ").toLowerCase().replaceAll(" and", " ");
		String[] splitParts = words.trim().split("\\s+");
		int n = 0;
		int m = 0;
		for (String s : splitParts) {
			switch  (s) {
			case "zero": n += 0; break;
			case "one": n += 1; break;
			case "two": n += 2; break;
			case "three": n += 3; break;
			case "four": n += 4; break;
			case "five": n += 5; break;
			case "six": n += 6; break;
			case "seven": n += 7; break;
			case "eight": n += 8; break;
			case "nine": n += 9; break;
			case "ten": n += 10; break;
			case "eleven": n += 11; break;
			case "twelve": n += 12; break;
			case "thirteen": n += 13; break;
			case "fourteen": n += 14; break;
			case "fifteen": n += 15; break;
			case "sixteen": n += 16; break;
			case "seventeen": n += 17; break;
			case "eighteen": n += 18; break;
			case "nineteen": n += 19; break;
			case "twenty": n += 20; break;
			case "thirty": n += 30; break;
			case "forty": n += 40; break;
			case "fifty": n += 50; break;
			case "sixty": n += 60; break;
			case "seventy": n += 70; break;
			case "eighty": n += 80; break;
			case "ninety": n += 90; break;
			case "hundred": n *= 100; break;
			case "thousand": 
				n *= 1000; 
				m += n; 
				n=0; 
				break;
			case "million": 
				n *= 1000000; 
				m += n; 
				n=0; 
				break;
			default: return input;  // a word was not recognized
			}
		}
		return Integer.toString(m+n);
	}
	
	public Question clone() {
		try {
			return (Question) super.clone();
		} catch (Exception e) {
			return null;
		}
	}
	
	// The following methods are from the original CharHider class to guard against
	// inadvertent mistakes in interpreting user input, especially in Question items.

	static String quot2html(String oldString) {
		if (oldString == null) return "";
		// recursive method replaces single quotes with &#39; for HTML pages
		int i = oldString.indexOf('\'',0);
		return i<0?oldString:quot2html(new StringBuilder(oldString).replace(i,i+1,"&#39;").toString(),i);
	}

	static String quot2html(String oldString,int fromIndex) {
		// recursive method replaces single quotes with &#39; for HTML pages
		int i = oldString.indexOf('\'',fromIndex);
		return i<0?oldString:quot2html(new StringBuilder(oldString).replace(i,i+1,"&#39;").toString(),i);
	}

	static String amp2html(String oldString) {
		if (oldString == null) return "";
		// recursive method replaces ampersands with &amp; for preloading Greek/special characters in text fields in HTML forms
		int i = oldString.indexOf('&',0);
		//		    return i<0?oldString:new StringBuilder(oldString).replace(i,i+1,"&amp;").toString();
		return i<0?oldString:amp2html(new StringBuilder(oldString).replace(i,i+1,"&amp;").toString(),i+1);
	}

	static String amp2html(String oldString,int fromIndex) {
		// recursive method replaces ampersands with &amp; for preloading Greek/special characters in text fields in HTML forms
		int i = oldString.indexOf('&',fromIndex);
		return i<0?oldString:amp2html(new StringBuilder(oldString).replace(i,i+1,"&amp;").toString(),i+1);
	}
	
	String parseFractions(String expression) {
		return parseFractions(expression,0);
	}
	
	String parseFractions(String expression, int startIndex) {
		// This method uses parentheses and the pipe character (|) to identify numerator and denominator of a fraction
		// to be displayed in a vertical format. The encoding is like (| numerator | denominator |)
		final String num = "<span style='display: inline-block;vertical-align: middle;font-size: smaller'><div style='text-align: center;border-bottom: 1px solid black;'>";
		final String pip = "</div><div style='text-align: center;'>";
		final String den = "</div></span>";
		
		int i = expression.indexOf("(|",startIndex);  	// marks the first start of a numerator
		if (i<0) return expression;						// quick return if no fractions found
		
		int i2 = expression.indexOf("(|",i+2);			// marks the second start of a numerator
		int k = expression.indexOf("|)",i+2);			// marks the end of a denominator
		if (i2>0 && i2<k) {								// second start is before first end; start recursion
			expression = parseFractions(expression,i2);
			k = expression.indexOf("|)",i+2);			// recalculate the end due to substitutions made
		}
		if (k<0) return expression; 					// there must be and end-of-fraction marker to proceed
		
		int j = expression.indexOf("|",i+2);			// marks separator between numerator and denominator
		
		// Replace the markers with CSS style tags:
		if (j>0 && j<k) {
			expression = expression.substring(0,i) + num + expression.substring(i+2,j) + pip + expression.substring(j+1,k) + den + expression.substring(k+2);
			k = k - 5 + num.length() + pip.length() + den.length();
		}
		
		// Test to see if there is another fraction at this level:
		return parseFractions(expression,k+1);
	}
	
	public String getCorrectAnswerForSage() { 
		// similar to getCorrectAnswer but expands MULTIPLE_CHOICE and SELECT_MULTIPLE answers
		switch (type) {
		case TYPE_MULTIPLE_CHOICE: 
		case TYPE_CHECKBOX: 
			char[] ch = correctAnswer.toCharArray();
			String ans = "";
			for (char c : ch) ans += choices.get(c - 'a') + "\n";
			ans = ans.substring(0,ans.length()-2); // trims the last newline character
			return ans;
		case TYPE_FILL_IN_BLANK: 
			String[] answers = correctAnswer.split(",");
			return answers[0];
		case TYPE_NUMERIC:
			return parseString(correctAnswer);
		default: return correctAnswer;
		}
	}
	
}
