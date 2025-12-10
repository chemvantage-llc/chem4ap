package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/units")
public class CurriculumManager extends HttpServlet {
private static final long serialVersionUID = 1L;
	
public void doGet(HttpServletRequest request, HttpServletResponse response)
		throws ServletException, IOException {
	
	PrintWriter out = response.getWriter();
	response.setContentType("text/html");
	
	String userRequest = request.getParameter("UserRequest");
	if (userRequest==null) userRequest = "";
	
	switch (userRequest) {
	case "Expand":
		try {
			Long unitId = Long.parseLong(request.getParameter("UnitId"));
			out.println(Util.head("Topics") + listTopics(unitId) + Util.foot());
			break;
		} catch (Exception e) { out.println(e.getMessage());}
	default: out.println(Util.head("Units") + listUnits() + Util.foot());
	}	
}

public void doPost(HttpServletRequest request, HttpServletResponse response)
		throws ServletException, IOException {
	
	PrintWriter out = response.getWriter();
	response.setContentType("text/html");
	
	String userRequest = request.getParameter("UserRequest");
	if (userRequest==null) userRequest = "";
	
	switch (userRequest) {
	case "AddUnit":
		addUnit(request);
		break;
	case "AddTopic":
		try {
			addTopic(request);
			Long unitId = Long.parseLong(request.getParameter("UnitId"));
			out.println(Util.head("Topics") + listTopics(unitId) + Util.foot());
		} catch (Exception e) { out.println(e.getMessage()); }
		break;
	default: out.println(Util.head("Units") + listUnits() + Util.foot());
	}
}

static void addTopic(HttpServletRequest request) throws Exception {
		APChemTopic topic = new APChemTopic();
		topic.unitId = Long.parseLong(request.getParameter("UnitId"));
		topic.unitNumber = Integer.parseInt(request.getParameter("UnitNumber"));
		topic.topicNumber = Integer.parseInt(request.getParameter("TopicNumber"));
		topic.title = request.getParameter("Title");
		topic.learningObjective = request.getParameter("Objective");
		topic.essentialKnowledge = request.getParameterValues("Knowledge");
		ofy().save().entity(topic).now();
}

static void addUnit(HttpServletRequest request) {
	try {
		APChemUnit unit = new APChemUnit();		
		unit.unitNumber = Integer.parseInt(request.getParameter("UnitNumber"));
		unit.title = request.getParameter("Title");
		unit.summary = request.getParameter("Summary");
		ofy().save().entity(unit).now();
	} catch (Exception e) {}
}

	static String listTopics(Long unitId) throws Exception {
		StringBuffer buf = new StringBuffer("<h1>Units of Instruction</h1>");
		APChemUnit u = ofy().load().type(APChemUnit.class).id(unitId).safe();
		buf.append("<h2>Unit " + u.unitNumber + ": " + u.title + "</h2>");
		List<APChemTopic> topics = ofy().load().type(APChemTopic.class).filter("unitId",u.id).order("topicNumber").list();
		for (APChemTopic t : topics) {
			buf.append("Topic " + t.unitNumber + "." + t.topicNumber + ": " + t.title + "<br/>");
		}
		int nextTopicNumber = topics.size()+1;
		buf.append("<a href=# onclick=showForm()>Add a topic</a>"
				+ "<form id=addForm method=post action=/units style='display: none' >:<br/>"
				+ "  <input type=hidden name=UserRequest value=AddTopic />"
				+ "  <input type=hidden name=UnitId value=" + u.id + " />"
				+ "  <input type=hidden name=UnitNumber value=" + u.unitNumber + " />"
				+ "  Topic " + u.unitNumber + ".<input type=text size=1 name=TopicNumber value=" + nextTopicNumber + " /><br/>"
				+ "  Title: <input type=text name=Title /><br/>"
				+ "  Learning Objective:<br/>"
				+ "  <textarea rows=5 cols=80 name=Objective></textarea><br/>"
				+ "  Essential Knowledge:<br/>"
				+    u.unitNumber + "." + nextTopicNumber + ".A.1 <textarea rows=5 cols=80 name=Knowledge></textarea><br/>"
				+    u.unitNumber + "." + nextTopicNumber + ".A.2 <textarea rows=5 cols=80 name=Knowledge></textarea><br/>"
				+    u.unitNumber + "." + nextTopicNumber + ".A.3 <textarea rows=5 cols=80 name=Knowledge></textarea><br/>"
				+    u.unitNumber + "." + nextTopicNumber + ".A.4 <textarea rows=5 cols=80 name=Knowledge></textarea><br/>"
				+    u.unitNumber + "." + nextTopicNumber + ".A.5 <textarea rows=5 cols=80 name=Knowledge></textarea><br/>"
				+    u.unitNumber + "." + nextTopicNumber + ".A.6 <textarea rows=5 cols=80 name=Knowledge></textarea><br/>"
				+    u.unitNumber + "." + nextTopicNumber + ".A.7 <textarea rows=5 cols=80 name=Knowledge></textarea><br/>"
				+    u.unitNumber + "." + nextTopicNumber + ".A.8 <textarea rows=5 cols=80 name=Knowledge></textarea><br/>"
				+ "  <input type=submit />"
				+ "</form>");
		
		buf.append("<script>"
				+ "function showForm() {"
				+ " document.getElementById('addForm').style='display:inline;';"
				+ "}"
				+ "</script>");
		return buf.toString();
	}
	
	static String listUnits() {
		StringBuffer buf = new StringBuffer("<h1>Units of Instruction</h1>");
		List<APChemUnit> units = ofy().load().type(APChemUnit.class).order("unitNumber").list();
		for (APChemUnit u : units) {
			buf.append("<a href=/units?UserRequest=Expand&UnitId=" + u.id + " /> " + u.unitNumber + ": " + u.title + "</a><br/>");
		}
		buf.append("<a href=# onclick=showForm()>Add a unit</a>"
				+ "<form id=addForm method=post action=/units style='display: none' >:<br/>"
				+ "  <input type=hidden name=UserRequest value=AddUnit />"
				+ "  Unit <input type=text size=2 name=UnitNumber value=" + (units.size()+1) + " /><br/>"
				+ "  Title: <input type=text name=Title /><br/>"
				+ "  Summary:<br/>"
				+ "  <textarea rows=5 cols=80 name=Summary></textarea><br/>"
				+ "  <input type=submit />"
				+ "</form>");
		
		buf.append("<script>"
				+ "function showForm() {"
				+ " document.getElementById('addForm').style='display:inline;';"
				+ "}"
				+ "</script>");
		return buf.toString();
	}
}
