/*  Chem4AP - A Java web application for online learning
*   Copyright (C) 2025 ChemVantage LLC
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
import java.text.DateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Random;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Checkout servlet for managing Chem4AP subscription purchases via PayPal.
 * 
 * This servlet handles the complete subscription purchase workflow:
 * - Display checkout page with voucher redemption and payment options
 * - Validate vouchers and activate subscriptions
 * - Create PayPal orders for subscription purchases
 * - Capture PayPal payments and activate premium access
 * 
 * Subscription pricing uses a discount schedule:
 * - 2 months: $4 USD (2 * $2)
 * - 5 months: $8 USD (4 * $2 - 20% discount)
 * - 12 months: $16 USD (8 * $2 - 33% discount)
 * 
 * Integration with PayPal REST APIs for:
 * - OAuth 2.0 authentication (generating access tokens)
 * - Order creation (v2/checkout/orders)
 * - Payment capture (v2/checkout/orders/{id}/capture)
 * 
 * Uses PayPal SDK for client-side button rendering and checkout flow.
 * 
 * HTTP Routes:
 *   GET  /checkout                              Display checkout page (requires sig parameter)
 *   POST /checkout?UserRequest=RedeemVoucher   Validate and redeem subscription voucher
 *   POST /checkout?UserRequest=CreateOrder     Create new PayPal order for subscription
 *   POST /checkout?UserRequest=CompleteOrder   Capture PayPal payment and activate subscription
 * 
 * Security: All routes require valid user authentication via "sig" parameter.
 *           Prevents purchase pages for users who already have active premium subscriptions.
 *           Validates voucher ownership to prevent fraud.
 * 
 * @author ChemVantage
 * @version 2.0
 * @see PremiumUser
 * @see PayPalOrder
 * @see Voucher
 * @see User
 */
@WebServlet("/checkout")
public class Checkout extends HttpServlet {

	@Serial
	private static final long serialVersionUID = 137L;
	private JsonObject auth_json = new JsonObject();
	
	/**
	 * Returns servlet information.
	 * 
	 * @return Description of servlet purpose
	 */
	public String getServletInfo() {
		return "This servlet is used by students to purchase Chem4AP subscriptions.";
	}

	/**
	 * Handles HTTP GET requests for checkout page display.
	 * 
	 * Displays the main checkout page with subscription options (voucher or PayPal).
	 * If user already has active premium subscription, redirects to assignment instead.
	 * Deletes invalid/malformed user sessions from datastore.
	 * 
	 * @param request The HTTP request containing "sig" parameter for user authentication
	 * @param response The HTTP response object
	 * @throws ServletException if a servlet error occurs
	 * @throws IOException if an I/O error occurs while writing response
	 */
	@Override
	public void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.setContentType("text/html; charset=UTF-8");
		PrintWriter out = response.getWriter();
		User user = User.getUser(request.getParameter("sig"));
		try {
			if (user.isPremium()) {  // do not allow the user to use this page
				Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
				if (a != null) response.sendRedirect("/" + a.assignmentType.toLowerCase() + "?sig=" + user.getTokenSignature());
				else out.println("Your subscription is active.");
				return;
			}
			String platform_deployment_id = user.platformId.equals(Util.getServerUrl())?user.platformId:ofy().load().type(Deployment.class).id(request.getParameter("d")).now().platform_deployment_id;
			out.println(checkoutPage(user, platform_deployment_id));	
		} catch (Exception e) {
			ofy().delete().entity(user).now();
			out.println(Util.head("Logout") + "<h1>You have been logged out</h1>" + Util.foot());
		}
	}

	/**
	 * Handles HTTP POST requests for checkout operations.
	 * 
	 * Routes requests based on UserRequest parameter:
	 * - RedeemVoucher: Validate and activate a subscription voucher for user
	 * - CreateOrder: Create a new PayPal order for subscription purchase
	 * - CompleteOrder: Capture PayPal payment and activate premium access
	 * 
	 * All requests require valid "sig" parameter for user authentication.
	 * Returns 401 Unauthorized for authentication failures.
	 * Returns 400 Bad Request for invalid UserRequest values.
	 * 
	 * @param request The HTTP request containing "sig" and "UserRequest" parameters,
	 *        plus request-specific parameters (voucher_code, nmonths, order_id)
	 * @param response The HTTP response object
	 * @throws ServletException if a servlet error occurs
	 * @throws IOException if an I/O error occurs while reading request or writing response
	 */
	@Override
	public void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.setContentType("application/json; charset=UTF-8");
		PrintWriter out = response.getWriter();
		User user = null;
		JsonObject res = new JsonObject();
		
		try {
			user = User.getUser(request.getParameter("sig"));
			if (user == null) throw new Exception("You must be logged in through your class LMS to see this page.");
			
			if (user.isPremium()) {  // do not allow the user to use this page
				Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
				if (a != null) response.sendRedirect("/" + a.assignmentType.toLowerCase() + "?t=" + Util.getToken(user.getTokenSignature()));
				else out.println("Your subscription is active.");
				return;
			}
			
			String userRequest = request.getParameter("UserRequest");
			switch (userRequest) {
			case "RedeemVoucher":
				response.setContentType("text/html; charset=UTF-8");
				out.println(redeemVoucher(user, request));
				break;
			case "CreateOrder":
				String order_id = createOrder(user, request);
				res.addProperty("id", order_id);
				out.println(res.toString());
				break;
			case "CompleteOrder":
				out.println(completeOrder(user, request).toString());
				break;
			default:
				response.sendError(400);  // Bad request
			}
		} catch (Exception e) {
			res.addProperty("error", e.getMessage());
			response.sendError(401, res.toString());
		}
	}

	/**
	 * Builds the HTML checkout page with subscription options.
	 * 
	 * Displays form with:
	 * - Terms and conditions acknowledgment checkboxes
	 * - Voucher code entry field
	 * - Subscription duration dropdown (2, 5, or 12 months)
	 * - PayPal payment button container (populated by client-side JavaScript)
	 * 
	 * Passes user and deployment data to client-side checkout.js via hidden inputs.
	 * Includes PayPal SDK script and references to client-side checkout logic.
	 * 
	 * Shows expiration date if user has expired subscription (from PremiumUser record).
	 * Otherwise shows generic subscription heading.
	 * 
	 * @param user The authenticated user
	 * @param platform_deployment_id The LTI deployment ID for LMS integration
	 * @return Complete HTML page string with form and scripts
	 */
	static String checkoutPage(User user, String platform_deployment_id) {
		StringBuilder buf = new StringBuilder();
		
		Date now = new Date();
		DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.FULL);
		Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
		
		buf.append(Util.head("Chem4AP Subscription") + Util.banner);
		
		// Store the PayPal client_id and user's sig value and platform_deployment_id in DOM elements so they can be accessed by javascript
		buf.append("<input type='hidden' id='client_id' value='" + Util.getPayPalClientId() + "' />\n");
		buf.append("<input type='hidden' id='sig' value='" + user.getTokenSignature() + "' />\n");
		buf.append("<input type='hidden' id='assignment_type' value='" + a.assignmentType.toLowerCase() + "' />\n");
		buf.append("<input type='hidden' id='platform_deployment_id' value='" + platform_deployment_id + "' />\n");
		buf.append("<input type='hidden' id='price' value='2' />\n");

		PremiumUser u = ofy().load().type(PremiumUser.class).id(user.getHashedId()).now();		
		String title = (u != null && u.exp.before(now))?"Your Chem4AP subscription expired on " + df.format(u.exp):"Individual Chem4AP Subscription";

		buf.append("<h1>" + title + "</h1>\n" +
				"A subscription is required to access Chem4AP Units 1-9. First, please indicate your agreement with the two statements below by checking the boxes.<br/><br/>" +
				"<label><input type='checkbox' id='terms' onChange='showSelectPaymentMethod()'> " +
				"I understand and agree to the <a href=/terms.html target=_blank>Chem4AP Terms and Conditions of Use</a>.</label> <br/>" +
				"<label><input type='checkbox' id='norefunds' onChange='showSelectPaymentMethod()'> " +
				"I understand that all Chem4AP subscription fees are non-refundable.</label> <br/><br/>");
		
		buf.append("\n<div id='select_payment_method' style='display:none'>\n");

		buf.append("If you have a subscription voucher, please enter the code here: " +
				"<form method=post action=/checkout>" +
				"<input type='hidden' name='UserRequest' value='RedeemVoucher' />" +
				"<input type='hidden' name='sig' value='" + user.getTokenSignature() + "' />" +
				"<input type='hidden' name='d' value='" + platform_deployment_id + "' />" +
				"<input type='text' name='VoucherCode' size='10' />&nbsp;" +
				"<input type=submit class='btn btn-primary' value='Redeem' /><br/>" +
				"</form>");
				
		buf.append("\n<hr>Otherwise, please select the desired number of months you wish to purchase:<br/>" +
				"<div style='align: center'>" +
				"<select id='nmonths'>" +
				"<option value='2'>2 months - $" + (2*2) + " USD</option>" +
				"<option value='5'>5 months - $" + (4*2) + " USD</option>" +
				"<option value='12' selected>12 months - $" + (8*2) + " USD</option>" +
				"</select>&nbsp;" +
				"<button class='btn btn-primary' onclick='startCheckout();'>Checkout</button>" +
				"</div>");		
		buf.append("</div>");  // end of 'select_payment_method' div
		
		// Create a div for displaying the PayPal payment buttons (initially hidden)
		buf.append("<div id='payment_div' style='display: none'>")
				.append("Please select your method of payment:<br/><br/>")
				.append("<div id='paypal-button-container'></div>");
		buf.append("</div>");  // end of payment div
		
		buf.append("\n<script src='https://www.paypal.com/sdk/js?client-id=" + Util.getPayPalClientId() + "&enable-funding=venmo&disable-funding=paylater'></script>");
		buf.append("\n<script src='/js/checkout.js?r=" + new Random().nextInt() + "'></script>");
		buf.append(Util.foot());
		
		return buf.toString();
	}
	
	/**
	 * Redeems a subscription voucher for the user.
	 * 
	 * Validates the voucher code by loading from datastore, activating it (marking as used),
	 * and creating a PremiumUser record with subscription months. Prevents duplicate redemptions
	 * by the same user (via Voucher.activate() check).
	 * 
	 * @param user The authenticated user redeeming the voucher
	 * @param request The HTTP request containing "voucher_code" parameter
	 * @return The expiration date of the newly activated subscription
	 * @throws Exception if voucher code is invalid, already redeemed, or cannot be activated
	 * @see Voucher#activate()
	 * @see PremiumUser
	 */
	String redeemVoucher(User user, HttpServletRequest request) throws Exception {
		PremiumUser pu = null;
		StringBuilder buf = new StringBuilder();
		buf.append(Util.head("Chem4AP Subscription") + Util.banner);
		try {
			String voucherCode = request.getParameter("VoucherCode");
			if (voucherCode == null || voucherCode.isEmpty()) throw new Exception("Sorry, the voucher code was missing or invalid.");
			voucherCode = voucherCode.toUpperCase();
			Voucher v = ofy().load().type(Voucher.class).id(voucherCode).now();
			if (v == null) throw new Exception("Sorry, the voucher code was missing or invalid.");
				if (!v.activate()) { // check for duplicate submission by same user
				pu = ofy().load().type(PremiumUser.class).id(user.hashedId).now();
				if (pu==null ||!pu.order_id.equals(v.code)) throw new Exception("Sorry, the voucher code was missing or invalid.");
			}

			// code is valid, so create a new PremiumUser
			if (pu == null) {
				Deployment deployment = ofy().load().type(Deployment.class).id(request.getParameter("d")).safe();
				pu = new PremiumUser(user.getHashedId(), v.months, v.paid, deployment.getOrganization(), v.code); // constructor automatically saves new entity
			}

			buf.append("<h1>Voucher Redeemed Successfully</h1>\n");
			buf.append("<p>Your Chem4AP subscription has been activated and will expire on <strong>" + pu.exp.toString() + "</strong>.</p>\n");
			Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
			buf.append("<a class='btn btn-primary' href='/" + a.assignmentType.toLowerCase() + "/index.html?t=" + Util.getToken(user.getTokenSignature()) + "'>Proceed to Chem4AP</a>\n");
		} catch (Exception e) {
			ofy().delete().entity(user).now();  // delete invalid user session
			buf.append("<h1>Voucher Redemption Failed</h1>\n");
			buf.append("<p>" + e.getMessage() + "</p>\n");
			buf.append("<p>Launch this assignment again in your LMS to retry voucher redemption.</p>\n");
		}
		buf.append(Util.foot());
		return buf.toString();

	}
	
	/**
	 * Helper method to check if HTTP response indicates success (2xx status code).
	 * 
	 * @param conn The HTTP URL connection with response status code
	 * @return True if response code is 2xx (200-299), false otherwise
	 * @throws IOException if error occurs reading response code
	 */
	private boolean isSuccessResponse(HttpURLConnection conn) throws IOException {
		return conn.getResponseCode() / 100 == 2;
	}
	
	/**
	 * Generates an OAuth 2.0 access token for PayPal REST APIs using client credentials flow.
	 * 
	 * Caches the token in instance variable auth_json and reuses it until expiration.
	 * Automatically refreshes token 5 seconds before expiry (TOKEN_CACHE_GRACE_MS) to avoid
	 * timing issues with token expiration.
	 * 
	 * Makes HTTP POST request to PayPal OAuth endpoint with Basic authentication
	 * (client_id:client_secret encoded in Base64).
	 * 
	 * @return Valid access token string ready for use in Bearer authentication
	 * @throws Exception if OAuth request fails or response cannot be parsed
	 * @see https://developer.paypal.com/api/rest/authentication/
	 */
	String generateAccessToken() throws Exception {
		Date now = new Date();
		
		try {  // First, check if there is a cached non-expired auth token
			if (!auth_json.has("access_token") || !auth_json.has("exp")) {
				throw new Exception("Cache miss");
			}
			Date exp = new Date(auth_json.get("exp").getAsLong());
			if (exp.after(now)) {
				return auth_json.get("access_token").getAsString();
			}
			throw new Exception("Token expired");
		} catch (Exception e) {  // retrieve a new auth token from PayPal
			
			String auth = Base64.getEncoder().encodeToString((Util.getPayPalClientId()+":"+Util.getPayPalClientSecret()).getBytes());

			String baseUrl = Util.projectId.equals("dev-chem4ap")?"https://api-m.sandbox.paypal.com":"https://api-m.paypal.com";
			String body = "grant_type=client_credentials";

			URL u = new URI(baseUrl + "/v1/oauth2/token").toURL();
			HttpURLConnection uc = (HttpURLConnection) u.openConnection();
			uc.setDoOutput(true);
			uc.setDoInput(true);
			uc.setRequestMethod("POST");
			uc.setRequestProperty("Authorization", "Basic " + auth);
			uc.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			uc.setRequestProperty("Accept", "application/json; charset=UTF-8");
			uc.setRequestProperty("charset", "utf-8");
			uc.setUseCaches(false);
			uc.setReadTimeout(15000);
			// send the message
			try (DataOutputStream wr = new DataOutputStream(uc.getOutputStream())) {
				wr.writeBytes(body);
			}

			BufferedReader reader = null;
			try {
				if (isSuccessResponse(uc)) {
					reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));				
					auth_json = JsonParser.parseReader(reader).getAsJsonObject();
					reader.close();

					// Cache the auth_json for future use
					int expires_in = auth_json.get("expires_in").getAsInt();  // seconds from now
					Long exp = new Date(new Date().getTime() + expires_in*1000 - 5000).getTime();
					auth_json.addProperty("exp", exp);
					return auth_json.get("access_token").getAsString();
				} else {
					reader = new BufferedReader(new InputStreamReader(uc.getErrorStream()));				
					JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
					reader.close();
					Util.sendEmail("ChemVantage LLC", "admin@chemvantage.org", "PayPal AuthToken Error", json.toString());
					throw new Exception("PayPal authentication failed: " + json.toString());
				}
			} finally {
				if (reader != null) reader.close();
				uc.disconnect();
			}
		}
	}
	
	/**
	 * Creates a PayPal order for a subscription purchase.
	 * 
	 * Constructs a PayPal order JSON with:
	 * - Intent: CAPTURE (immediate payment capture)
	 * - Currency: USD
	 * - Amount: Calculated with progressive discount (2-month: 0%, 5-month: 20%, 12-month: 33%)
	 * - Description: Indicates subscription duration in months
	 * - Payment source: PayPal with no shipping preference
	 * 
	 * Makes HTTP POST request to PayPal v2/checkout/orders endpoint with OAuth bearer token.
	 * Stores PayPalOrder record in datastore for tracking and completion.
	 * Uses unique request_id to prevent duplicate order creation.
	 * 
	 * @param user The authenticated user creating the order
	 * @param request The HTTP request containing "nmonths" parameter
	 * @return The PayPal order ID from the response (used for payment capture)
	 * @throws Exception if order creation fails or response cannot be parsed
	 * @see #completeOrder(User, HttpServletRequest)
	 * @see PayPalOrder
	 */
	String createOrder(User user, HttpServletRequest request) throws Exception {	
		int nMonths = Integer.parseInt(request.getParameter("nmonths"));
		int value = 2 * (nMonths - nMonths/3);
		
		String platform_deployment_id = request.getParameter("d");
		String request_id = UUID.randomUUID().toString();
		
		JsonObject order_data = new JsonObject();
		order_data.addProperty("intent", "CAPTURE");
		  JsonArray purchaseUnits = new JsonArray();
		    JsonObject subscription = new JsonObject();
		    subscription.addProperty("description", nMonths + " - month Chem4AP subscription");
		      JsonObject amount = new JsonObject();
		      amount.addProperty("currency_code", "USD");
		      amount.addProperty("value", (2 * (nMonths - nMonths/3)) + ".00");  // calculated discount schedule
		    subscription.add("amount", amount);
		 purchaseUnits.add(subscription);
		order_data.add("purchase_units", purchaseUnits);
		  JsonObject paymentSource = JsonParser.parseString("{'paypal':{'experience_context':{'shipping_preference':'NO_SHIPPING'}}}").getAsJsonObject();
		order_data.add("payment_source", paymentSource);
		String baseUrl = Util.projectId.equals("dev-chem4ap")?"https://api-m.sandbox.paypal.com":"https://api-m.paypal.com";
		
		URL u = new URI(baseUrl + "/v2/checkout/orders").toURL();
		HttpURLConnection uc = (HttpURLConnection) u.openConnection();
		uc.setRequestMethod("POST");
		uc.setRequestProperty("Authorization", "Bearer " + generateAccessToken());
		uc.setRequestProperty("PayPal-Request-Id", request_id);
		uc.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
		uc.setDoOutput(true);
		
		try (OutputStreamWriter writer = new OutputStreamWriter(uc.getOutputStream())) {
			writer.write(order_data.toString());
			writer.flush();
		}

		BufferedReader reader = null;
		try {
			if (isSuccessResponse(uc)) {
				reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));				
				String order_id = JsonParser.parseReader(reader).getAsJsonObject().get("id").getAsString();
				reader.close();
				ofy().save().entity(new PayPalOrder(order_id, new Date(), order_data.toString(), nMonths, value, user, platform_deployment_id, request_id));
				return order_id;
			} else {
				reader = new BufferedReader(new InputStreamReader(uc.getErrorStream()));				
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				reader.close();
				Util.sendEmail("ChemVantage LLC", "admin@chemvantage.org", "PayPal OrderId Error", json.toString());
				throw new Exception("Failed to create PayPal order: " + json.toString());
			}
		} finally {
			if (reader != null) reader.close();
			uc.disconnect();
		}
	}
	
	/**
	 * Completes a PayPal order by capturing the authorized payment.
	 * 
	 * Makes HTTP POST request to PayPal v2/checkout/orders/{id}/capture endpoint.
	 * On successful capture:
	 * - Updates PayPalOrder status to "COMPLETED"
	 * - Creates PremiumUser record with subscription expiration date
	 * - Adds expiration date to JSON response
	 * 
	 * @param user The authenticated user completing payment
	 * @param request The HTTP request containing "order_id" parameter
	 * @return JsonObject with PayPal response including status and subscription expiration
	 * @throws Exception if payment capture fails or order cannot be found
	 * @see PayPalOrder
	 * @see PremiumUser
	 */
	JsonObject completeOrder(User user, HttpServletRequest request) throws Exception {
		String order_id = request.getParameter("order_id");
		PayPalOrder order = ofy().load().type(PayPalOrder.class).id(order_id).safe();
		String organization = "Chem4AP";
		try {
			Deployment deployment = ofy().load().type(Deployment.class).id(order.platform_deployment_id).safe();
			organization = deployment.organization;
		} catch (Exception e) {}
		String baseUrl = Util.projectId.equals("dev-chem4ap")?"https://api-m.sandbox.paypal.com":"https://api-m.paypal.com";
		
		URL u = new URI(baseUrl + "/v2/checkout/orders" + "/" + order_id + "/capture").toURL();
		HttpURLConnection uc = (HttpURLConnection) u.openConnection();
		uc.setRequestMethod("POST");
		uc.setRequestProperty("Authorization", "Bearer " + generateAccessToken());
		uc.setRequestProperty("PayPal-Request-Id", order.request_id);
		uc.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
		
		BufferedReader reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));				
		JsonObject resp = JsonParser.parseReader(reader).getAsJsonObject();
		reader.close();
		
		order.status = resp.get("status").getAsString();  // update order status
		if (order.status.equals("COMPLETED")) {  // create new PremiumUser
			PremiumUser pu = new PremiumUser(user.getHashedId(), order.nMonths, order.value, organization, order.id);
			resp.addProperty("expires", pu.exp.toString());
			resp.addProperty("token", Util.getToken(user.getTokenSignature()));
		}
		ofy().save().entity(order);
		
		return resp;
	}
	
}

/**
 * Domain model for a PayPal order persisted with Objectify.
 * 
 * Tracks order details, pricing, and payment status for Chem4AP subscriptions.
 * Stores the original order JSON request data for auditing and debugging.
 * Created when subscription payment is initiated, updated when payment is captured.
 * 
 * @author ChemVantage
 * @version 2.0
 * @see Checkout#createOrder(User, HttpServletRequest)
 * @see Checkout#completeOrder(User, HttpServletRequest)
 */
@Entity
class PayPalOrder {
	@Id String id;    // this is a PayPal-generated value for the path of API calls
	@Index Date created;
	String request_id;  // this is a Chem4AP-generated value for the request headers
	String order_data;  // original JSON order request for auditing
	int nMonths;  // subscription duration in months
	int value;  // price in USD cents (or dollars for amounts >= 100)
	String hashedId;  // hashed user ID for linking to subscriber
	String platform_deployment_id;  // LTI deployment ID for organization context
	String status = "CREATED";  // PayPal order status (CREATED, APPROVED, COMPLETED)
			
	/**
	 * Default no-argument constructor required by Objectify persistence framework.
	 */
	public PayPalOrder() {}
	
	/**
	 * Constructs a new PayPal order record for order creation.
	 * 
	 * Stores all order details needed to track the payment through capture completion.
	 * The order_data JSON is retained for debugging and compliance purposes.
	 * 
	 * @param id PayPal-generated order ID
	 * @param created Order creation timestamp
	 * @param order_data Original JSON order request sent to PayPal
	 * @param nMonths Subscription duration in months
	 * @param value Amount charged in USD cents (or whole dollars)
	 * @param user The user purchasing the subscription
	 * @param platform_deployment_id LTI deployment ID from request
	 * @param request_id Unique request ID for idempotency
	 */
	public PayPalOrder(String id, Date created, String order_data, int nMonths, int value, User user, String platform_deployment_id, String request_id) {
		this.id = id;
		this.created = created;
		this.order_data = order_data;
		this.nMonths = nMonths;
		this.value = value;
		this.hashedId = user.hashedId;
		this.platform_deployment_id = platform_deployment_id;
		this.request_id = request_id;
	}
}