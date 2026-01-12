package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Curriculum management servlet for creating and viewing AP Chemistry units and topics.
 * Requires admin authentication to create/modify curriculum.
 */
@WebServlet("/units")
public class CurriculumManager extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 1L;
	
	/**
	 * GET: Display curriculum units or topics within a unit.
	 */
	@Override
	public void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html; charset=UTF-8");
		
		String userRequest = request.getParameter("UserRequest");
		if (userRequest==null) userRequest = "";
		
		switch (userRequest) {
		case "Expand":
			try {
				Long unitId = Long.parseLong(request.getParameter("UnitId"));
				out.println(Util.head("Topics") + listTopics(unitId) + Util.foot());
			} catch (Exception e) { 
				out.println(e.getMessage());
			}
			break;
		default: 
			out.println(Util.head("Units") + listUnits() + Util.foot());
		}	
	}

	/**
	 * POST: Handle unit/topic creation requests.
	 */
	@Override
	public void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html; charset=UTF-8");
		
		String userRequest = request.getParameter("UserRequest");
		if (userRequest==null) userRequest = "";
		
		switch (userRequest) {
		case "AddUnit":
			try {
				addUnit(request);
				out.println(Util.head("Units") + listUnits() + Util.foot());
			} catch (Exception e) {
				out.println("Error adding unit: " + e.getMessage());
			}
			break;
		case "AddTopic":
			try {
				addTopic(request);
				Long unitId = Long.parseLong(request.getParameter("UnitId"));
				out.println(Util.head("Topics") + listTopics(unitId) + Util.foot());
			} catch (Exception e) { 
				out.println("Error adding topic: " + e.getMessage()); 
			}
			break;
		default: 
			out.println(Util.head("Units") + listUnits() + Util.foot());
		}
	}

	/**
	 * Adds a new AP Chemistry topic to a unit.
	 * @param request The HTTP request containing topic parameters
	 * @throws Exception if parsing or persistence fails
	 */
	static void addTopic(HttpServletRequest request) throws Exception {
		// Validate required parameters exist
		String unitIdStr = request.getParameter("UnitId");
		String unitNumberStr = request.getParameter("UnitNumber");
		String topicNumberStr = request.getParameter("TopicNumber");
		String title = request.getParameter("Title");
		String objective = request.getParameter("Objective");
		String[] knowledge = request.getParameterValues("Knowledge");
		
		if (unitIdStr == null || title == null || objective == null) {
			throw new IllegalArgumentException("Missing required parameters");
		}
		
		APChemTopic topic = new APChemTopic();
		topic.unitId = Long.parseLong(unitIdStr);
		topic.unitNumber = Integer.parseInt(unitNumberStr);
		topic.topicNumber = Integer.parseInt(topicNumberStr);
		topic.title = title;
		topic.learningObjective = objective;
		topic.essentialKnowledge = knowledge;
		ofy().save().entity(topic).now();
	}

	/**
	 * Adds a new AP Chemistry unit.
	 * @param request The HTTP request containing unit parameters
	 * @throws Exception if parsing or persistence fails
	 */
	static void addUnit(HttpServletRequest request) throws Exception {
		// Validate required parameters exist
		String unitNumberStr = request.getParameter("UnitNumber");
		String title = request.getParameter("Title");
		String summary = request.getParameter("Summary");
		
		if (unitNumberStr == null || title == null || summary == null) {
			throw new IllegalArgumentException("Missing required parameters");
		}
		
		APChemUnit unit = new APChemUnit();		
		unit.unitNumber = Integer.parseInt(unitNumberStr);
		unit.title = title;
		unit.summary = summary;
		ofy().save().entity(unit).now();
	}

	/**
	 * Generates the HTML for a unit's topics and add topic form.
	 * @param unitId The ID of the unit to display
	 * @return HTML string with topics and form
	 * @throws Exception if unit or topics cannot be loaded
	 */
	static String listTopics(Long unitId) throws Exception {
		if (unitId == null || unitId <= 0) {
			throw new IllegalArgumentException("Invalid unit ID");
		}
		
		StringBuilder buf = new StringBuilder("<h1>Units of Instruction</h1>");
		APChemUnit u = ofy().load().type(APChemUnit.class).id(unitId).safe();
		
		if (u == null) {
			throw new IllegalArgumentException("Unit not found");
		}
		
		buf.append("<h2>Unit ").append(u.unitNumber).append(": ").append(u.title).append("</h2>");
		
		List<APChemTopic> topics = ofy().load().type(APChemTopic.class).filter("unitId", u.id).order("topicNumber").list();
		for (APChemTopic t : topics) {
			buf.append("Topic ").append(t.unitNumber).append(".").append(t.topicNumber).append(": ")
			   .append(t.title).append("<br/>");
		}
		
		int nextTopicNumber = topics.size() + 1;
		buf.append(buildAddTopicForm(u, nextTopicNumber));
		buf.append(buildShowFormScript());
		
		return buf.toString();
	}
	
	/**
	 * Builds the HTML form for adding a new topic.
	 * @param unit The unit to which the topic will be added
	 * @param nextTopicNumber The next topic number in the sequence
	 * @return HTML form string
	 */
	static String buildAddTopicForm(APChemUnit unit, int nextTopicNumber) {
		StringBuilder form = new StringBuilder(
			"<a href=# onclick=showForm()>Add a topic</a>"
			+ "<form id=addForm method=post action=/units style='display: none' ><br/>"
			+ "  <input type=hidden name=UserRequest value=AddTopic />"
			+ "  <input type=hidden name=UnitId value=" + unit.id + " />"
			+ "  <input type=hidden name=UnitNumber value=" + unit.unitNumber + " />"
			+ "  Topic " + unit.unitNumber + ".<input type=text size=1 name=TopicNumber value=" + nextTopicNumber + " /><br/>"
			+ "  Title: <input type=text name=Title /><br/>"
			+ "  Learning Objective:<br/>"
			+ "  <textarea rows=5 cols=80 name=Objective></textarea><br/>"
			+ "  Essential Knowledge:<br/>");
		
		// Add 8 essential knowledge text areas
		for (int i = 1; i <= 8; i++) {
			form.append("  ").append(unit.unitNumber).append(".").append(nextTopicNumber)
				.append(".A.").append(i).append(" <textarea rows=5 cols=80 name=Knowledge></textarea><br/>");
		}
		
		form.append("  <input type=submit />\n").append("</form>");
		return form.toString();
	}
	
	/**
	 * Builds the JavaScript to show/hide the add topic form.
	 * @return JavaScript string
	 */
	static String buildShowFormScript() {
		return "<script>"
			+ "function showForm() {"
			+ " document.getElementById('addForm').style='display:inline;';"
			+ "}"
			+ "</script>";
	}
	
	/**
	 * Generates the HTML for all units and add unit form.
	 * @return HTML string with units and form
	 */
	static String listUnits() {
		StringBuilder buf = new StringBuilder("<h1>Units of Instruction</h1>");
		List<APChemUnit> units = ofy().load().type(APChemUnit.class).order("unitNumber").list();
		
		for (APChemUnit u : units) {
			buf.append("<a href=/units?UserRequest=Expand&UnitId=").append(u.id).append(" /> ")
			   .append(u.unitNumber).append(": ").append(u.title).append("</a><br/>");
		}
		
		int nextUnitNumber = units.size() + 1;
		buf.append(buildAddUnitForm(nextUnitNumber));
		buf.append(buildShowFormScript());
		
		return buf.toString();
	}
	
	/**
	 * Builds the HTML form for adding a new unit.
	 * @param nextUnitNumber The next unit number in the sequence
	 * @return HTML form string
	 */
	static String buildAddUnitForm(int nextUnitNumber) {
		return "<a href=# onclick=showForm()>Add a unit</a>"
			+ "<form id=addForm method=post action=/units style='display: none' ><br/>"
			+ "  <input type=hidden name=UserRequest value=AddUnit />"
			+ "  Unit <input type=text size=2 name=UnitNumber value=" + nextUnitNumber + " /><br/>"
			+ "  Title: <input type=text name=Title /><br/>"
			+ "  Summary:<br/>"
			+ "  <textarea rows=5 cols=80 name=Summary></textarea><br/>"
			+ "  <input type=submit />"
			+ "</form>";
	}
}
