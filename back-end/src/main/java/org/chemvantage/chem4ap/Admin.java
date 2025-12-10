package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/admin")
public class Admin extends HttpServlet {

	private static final long serialVersionUID = 1L;
	
	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		String userId = "admin";
		User user = new User("https://"+request.getServerName(), userId);
		user.setIsChemVantageAdmin(true);
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		String userRequest = request.getParameter("UserRequest");
		if (userRequest==null) userRequest = "";

		try {
			out.println(adminForm(user,request));
		}
		catch (Exception e) {
			out.println(e.getMessage());
		}
	}
	
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		String userId = "admin";
		User user = new User("https://"+request.getServerName(), userId);
		user.setIsChemVantageAdmin(true);
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		String userRequest = request.getParameter("UserRequest");
		if (userRequest==null) userRequest = "";

		switch (userRequest) {
		case "CreateVouchers":
			try {
				createVouchers(request);
			} catch (Exception e) {
				out.println(e.getMessage());
			}
		default: 
		}
		
		try {
			out.println(adminForm(user,request));
		}
		catch (Exception e) {
			out.println(e.getMessage());
		}
	}
	
	String adminForm(User user,HttpServletRequest request) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head("Admin"));
		String userRequest =  request.getParameter("UserRequest");
		if (userRequest==null) userRequest = "";
		
		buf.append("<h1>Administration</h1>");
		
		// Feedback Reports
		
		List<UserReport> reports = ofy().load().type(UserReport.class).list();
		int nReports = reports.size();
		if (nReports>0) {
			buf.append("<h2>User Feedback</h2>"
				+ "There are <a href='/feedback?UserRequest=ViewFeedback&sig=" + user.getTokenSignature() + "'>" + nReports + " new user reports.</a>");
		}
		
		// Subscription vouchers
		
		Date sinceWhen = new Date(0l);
		List<Voucher> vouchers = null;
		
		switch (userRequest) {
		case "CreateVouchers":
			sinceWhen = new Date(new Date().getTime()-5000L);
		case "ViewVoucherCodes":
			String org = request.getParameter("Org");
			vouchers = ofy().load().type(Voucher.class).filter("activated =",null).filter("org",org).filter("purchased >",sinceWhen).list();
			buf.append("<h2>" + (sinceWhen.equals(new Date(0))?"Unclaimed":"New") + " Subscription Voucher Codes:</h2><b>" + org + "</b><br/>");
			DateFormat df = new SimpleDateFormat("dd-MMM-yyyy");
			buf.append("<table><tr style='text-align:center'><th>Code</th><th>Months</th><th>Purchased</th><th>Amt Paid</th></th>");
			for (Voucher v : vouchers) {
				buf.append("<tr>"
						+ "<td style='text-align:center'>" + v.code + "</td>"
						+ "<td style='text-align:center'>" + v.months + "</td>"
						+ "<td style='text-align:center'>" + df.format(v.purchased) + "</td>"
						+ "<td style='text-align:center'>$" + v.paid + "</td>"
						+ "</tr>");
			}
			buf.append("</table><br/>");
			break;
		case "ViewVouchers":
			buf.append("<h2>Unclaimed Subscription Vouchers</h2>");
			vouchers = ofy().load().type(Voucher.class).filter("activated =",null).list();
			Map<String,Integer> vMap = new HashMap<String,Integer>();
			for (Voucher v : vouchers) vMap.put(v.org, vMap.get(v.org)==null?1:vMap.get(v.org)+1);
			buf.append("<table><tr><th>Organization</th><th>Vouchers</th></th>");
			for (Entry<String,Integer> e : vMap.entrySet()) {
				buf.append("<tr><td><a href='/admin?UserRequest=ViewVoucherCodes&Org=" + e.getKey() + "'>" + e.getKey() + "</a></td><td style='text-align:center'>" + e.getValue() + "</td></tr>");
			}
			buf.append("</table><br/>");
			break;
		default:
			buf.append("<h2>Subscription Vouchers</h2>");
			Integer nVouchers = ofy().load().type(Voucher.class).filter("activated =",null).count();
			buf.append("There are " + nVouchers + " <a href='/admin?UserRequest=ViewVouchers'>unclaimed vouchers</a>.<br/>");
			buf.append("<form method=post>");
			buf.append("<input type=hidden name=UserRequest value='CreateVouchers' />");
			buf.append("Create <input type=text size=3 name=NVouchers value=1 /> new "
					+ "<input type=text size=2 name=NMonths value=12>-month vouchers for "
					+ "<input type=text name=Org placeholder=organization required /> at "
					+ "$<input type=text size=2 name=Price value=0 /> each. "
					+ "<input type=submit value='Create' />");
			buf.append("</form><br/><br/>");
		}
		
		return buf.toString() + Util.foot();
	}
	
	void createVouchers(HttpServletRequest request) {
		int n = Integer.parseInt(request.getParameter("NVouchers"));
		String org = request.getParameter("Org");
		int price = Integer.parseInt(request.getParameter("Price"));
		int nMonths = Integer.parseInt(request.getParameter("NMonths"));
		if (org == null) return;
		List<Voucher> newVouchers = new ArrayList<Voucher>();
		for (int i=0; i<n; i++) newVouchers.add(new Voucher(org,price,nMonths));
		ofy().save().entities(newVouchers).now();
	}
}
