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

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
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

	private static final long serialVersionUID = 137L;
	
	// Subscription pricing constants
	/** Price per month in USD for subscription calculations */
	private static final int PRICE_USD = 2;
	/** Subscription option: 2 months */
	private static final int MONTHS_2 = 2;
	/** Subscription option: 5 months */
	private static final int MONTHS_5 = 5;
	/** Subscription option: 12 months */
	private static final int MONTHS_12 = 12;
	
	// OAuth and API configuration constants
	/** Grace period (milliseconds) before token expiration to refresh token */
	private static final int TOKEN_CACHE_GRACE_MS = 5000;
	/** HTTP connection timeout in milliseconds for PayPal API calls */
	private static final int HTTP_TIMEOUT_MS = 15000;
	
	// HTTP header and content type constants
	/** HTML content type with UTF-8 encoding */
	private static final String CONTENT_TYPE_HTML = "text/html; charset=UTF-8";
	/** JSON content type with UTF-8 encoding */
	private static final String CONTENT_TYPE_JSON = "application/json; charset=UTF-8";
	/** Form URL-encoded content type */
	private static final String CONTENT_TYPE_FORM = "application/x-www-form-urlencoded";
	
	// Request parameter names
	/** Request parameter name for user session signature */
	private static final String PARAM_SIG = "sig";
	/** Request parameter name for user request type (RedeemVoucher, CreateOrder, CompleteOrder) */
	private static final String PARAM_USER_REQUEST = "UserRequest";
	/** Request parameter name for deployment ID */
	private static final String PARAM_D = "d";
	/** Request parameter name for voucher code */
	private static final String PARAM_VOUCHER_CODE = "voucher_code";
	/** Request parameter name for number of months to purchase */
	private static final String PARAM_NMONTHS = "nmonths";
	/** Request parameter name for PayPal order ID */
	private static final String PARAM_ORDER_ID = "order_id";
	
	// UserRequest parameter values
	/** UserRequest parameter value for voucher redemption */
	private static final String USER_REQUEST_REDEEM_VOUCHER = "RedeemVoucher";
	/** UserRequest parameter value for creating new PayPal order */
	private static final String USER_REQUEST_CREATE_ORDER = "CreateOrder";
	/** UserRequest parameter value for completing PayPal payment capture */
	private static final String USER_REQUEST_COMPLETE_ORDER = "CompleteOrder";
	
	// HTML input and element constants
	/** HTML input type for hidden fields */
	private static final String INPUT_TYPE_HIDDEN = "hidden";
	/** HTML input type for text fields */
	private static final String INPUT_TYPE_TEXT = "text";
	/** HTML input type for checkbox */
	private static final String INPUT_TYPE_CHECKBOX = "checkbox";
	/** HTML input attribute for input size */
	private static final String INPUT_SIZE_VOUCHER = "10";
	/** CSS class for primary button styling */
	private static final String CSS_CLASS_BTN_PRIMARY = "btn btn-primary";
	
	// Page and section constants
	/** Page title for checkout page */
	private static final String PAGE_TITLE = "Chem4AP Subscription";
	/** Page title for logout page */
	private static final String PAGE_TITLE_LOGOUT = "Logout";
	/** Main heading prefix for subscription status */
	private static final String HEADING_SUBSCRIPTION_EXPIRED = "Your Chem4AP subscription expired on ";
	/** Main heading for active users */
	private static final String HEADING_SUBSCRIPTION = "Individual Chem4AP Subscription";
	/** Introductory text explaining subscription requirement */
	private static final String INTRO_TEXT = "A subscription is required to access Chem4AP Units 1-9. First, please indicate your agreement with the two statements below by checking the boxes.";
	/** Label text for terms and conditions checkbox */
	private static final String LABEL_TERMS = "I understand and agree to the ";
	/** Link text for terms and conditions */
	private static final String LINK_TERMS = "Chem4AP Terms and Conditions of Use";
	/** Label text for non-refundable fees checkbox */
	private static final String LABEL_NOREFUNDS = "I understand that all Chem4AP subscription fees are non-refundable.";
	/** Instruction text for voucher code entry */
	private static final String INSTRUCTION_VOUCHER = "If you have a subscription voucher, please enter the code here: ";
	/** Button text for voucher redemption */
	private static final String BTN_REDEEM = "Redeem";
	/** Separator text before alternative payment options */
	private static final String SEPARATOR_OR = "Otherwise, please select the desired number of months you wish to purchase:";
	/** Instruction text for payment method selection */
	private static final String INSTRUCTION_PAYMENT = "Please select your method of payment:";
	/** Button text for checkout initiation */
	private static final String BTN_CHECKOUT = "Checkout";
	/** Link text for proceeding to app */
	private static final String LINK_PROCEED = "Proceed to Chem4AP";
	
	// HTML element IDs and CSS selectors
	/** HTML element ID for client ID hidden input */
	private static final String HTML_ID_CLIENT_ID = "client_id";
	/** HTML element ID for sig hidden input */
	private static final String HTML_ID_SIG = "sig";
	/** HTML element ID for deployment ID hidden input */
	private static final String HTML_ID_PLATFORM_ID = "platform_deployment_id";
	/** HTML element ID for price hidden input */
	private static final String HTML_ID_PRICE = "price";
	/** HTML element ID for terms checkbox */
	private static final String HTML_ID_TERMS = "terms";
	/** HTML element ID for no-refunds checkbox */
	private static final String HTML_ID_NOREFUNDS = "norefunds";
	/** HTML element ID for voucher code input */
	private static final String HTML_ID_VOUCHER_CODE = "voucher_code";
	/** HTML element ID for months select dropdown */
	private static final String HTML_ID_NMONTHS = "nmonths";
	/** HTML element ID for payment method selection div */
	private static final String HTML_ID_SELECT_PAYMENT = "select_payment_method";
	/** HTML element ID for payment buttons container div */
	private static final String HTML_ID_PAYMENT_DIV = "payment_div";
	/** HTML element ID for proceed button div */
	private static final String HTML_ID_PROCEED = "proceed";
	/** HTML element ID for PayPal button container */
	private static final String HTML_ID_PAYPAL_BUTTON = "paypal-button-container";
	
	// JavaScript constants
	/** JavaScript function name to show/hide payment method selection */
	private static final String JS_FUNC_SHOW_PAYMENT = "showSelectPaymentMethod()";
	/** JavaScript function name to redeem voucher */
	private static final String JS_FUNC_REDEEM = "redeemVoucher";
	/** JavaScript function name to start checkout process */
	private static final String JS_FUNC_START_CHECKOUT = "startCheckout()";
	/** Script source URL for PayPal SDK */
	private static final String SCRIPT_PAYPAL_SDK = "https://www.paypal.com/sdk/js";
	/** URL path for client-side checkout JavaScript */
	private static final String PATH_CHECKOUT_JS = "/js/checkout.js";
	
	// PayPal API constants
	/** PayPal OAuth endpoint path */
	private static final String PAYPAL_PATH_OAUTH = "/v1/oauth2/token";
	/** PayPal orders endpoint path */
	private static final String PAYPAL_PATH_ORDERS = "/v2/checkout/orders";
	/** PayPal capture endpoint path suffix */
	private static final String PAYPAL_PATH_CAPTURE = "/capture";
	/** Base URL for PayPal API in production environment */
	private static final String PAYPAL_BASE_URL_PROD = "https://api-m.paypal.com";
	/** Base URL for PayPal API in sandbox/development environment */
	private static final String PAYPAL_BASE_URL_SANDBOX = "https://api-m.sandbox.paypal.com";
	/** Development project ID for detecting sandbox mode */
	private static final String PROJECT_ID_DEV = "dev-chem4ap";
	/** OAuth grant type for client credentials flow */
	private static final String OAUTH_GRANT_TYPE = "grant_type=client_credentials";
	/** HTTP Basic authentication prefix */
	private static final String AUTH_BASIC_PREFIX = "Basic ";
	/** HTTP Bearer token prefix */
	private static final String AUTH_BEARER_PREFIX = "Bearer ";
	/** PayPal request ID header name */
	private static final String HEADER_PAYPAL_REQUEST_ID = "PayPal-Request-Id";
	
	// PayPal order and payment constants
	/** PayPal order intent for immediate payment capture */
	private static final String PAYPAL_INTENT_CAPTURE = "CAPTURE";
	/** Currency code for USD */
	private static final String CURRENCY_USD = "USD";
	/** PayPal shipping preference for no shipping */
	private static final String SHIPPING_PREFERENCE = "NO_SHIPPING";
	/** PayPal order status indicating completion */
	private static final String PAYPAL_STATUS_COMPLETED = "COMPLETED";
	/** PayPal order status indicating creation (initial state) */
	private static final String PAYPAL_STATUS_CREATED = "CREATED";
	
	// JSON field names
	/** JSON field name for access token */
	private static final String JSON_ACCESS_TOKEN = "access_token";
	/** JSON field name for token expiration time (seconds) */
	private static final String JSON_EXPIRES_IN = "expires_in";
	/** JSON field name for order ID in PayPal response */
	private static final String JSON_ID = "id";
	/** JSON field name for order status */
	private static final String JSON_STATUS = "status";
	/** JSON field name for response error */
	private static final String JSON_ERROR = "error";
	/** JSON field name for order expiration date in response */
	private static final String JSON_EXP = "exp";
	/** JSON field name for PayPal intent */
	private static final String JSON_INTENT = "intent";
	/** JSON field name for purchase units array */
	private static final String JSON_PURCHASE_UNITS = "purchase_units";
	/** JSON field name for payment source */
	private static final String JSON_PAYMENT_SOURCE = "payment_source";
	/** JSON field name for order amount */
	private static final String JSON_AMOUNT = "amount";
	/** JSON field name for currency code */
	private static final String JSON_CURRENCY_CODE = "currency_code";
	/** JSON field name for amount value */
	private static final String JSON_VALUE = "value";
	/** JSON field name for order description */
	private static final String JSON_DESCRIPTION = "description";
	/** JSON field name for expires timestamp in response */
	private static final String JSON_EXPIRES = "expires";
	
	// Error and message constants
	/** Error message for missing or invalid voucher */
	private static final String ERROR_INVALID_VOUCHER = "Sorry, the voucher code was missing or invalid.";
	/** Error message for previously redeemed voucher */
	private static final String ERROR_DUPLICATE_VOUCHER = "This voucher code was redeemed previously by another user.";
	/** Error message for missing login */
	private static final String ERROR_NOT_LOGGED_IN = "You must be logged in through your class LMS to see this page.";
	/** Error message for active premium user */
	private static final String MESSAGE_SUBSCRIPTION_ACTIVE = "Your subscription is active.";
	/** Error message for failed PayPal authentication */
	private static final String ERROR_PAYPAL_AUTH = "PayPal authentication failed: ";
	/** Error message for failed order creation */
	private static final String ERROR_ORDER_CREATION = "Failed to create PayPal order: ";
	/** Error email subject for PayPal auth failures */
	private static final String EMAIL_SUBJECT_AUTH = "PayPal AuthToken Error";
	/** Error email subject for PayPal order failures */
	private static final String EMAIL_SUBJECT_ORDER = "PayPal OrderId Error";
	/** Email recipient for error notifications */
	private static final String EMAIL_ADMIN = "admin@chemvantage.org";
	/** Email sender for error notifications */
	private static final String EMAIL_FROM = "ChemVantage LLC";
	
	// Numeric and calculation constants
	/** Divisor for determining HTTP response success (2xx codes) */
	private static final int HTTP_SUCCESS_DIVISOR = 100;
	/** Minimum HTTP status code for success responses */
	private static final int HTTP_SUCCESS_CODE = 2;
	/** Conversion factor from seconds to milliseconds */
	private static final int MILLIS_PER_SECOND = 1000;
	/** Discount calculation for 2-month option (no discount) */
	private static final int DISCOUNT_2_MONTH = 0;
	/** Discount calculation for 5-month option (20% discount = 1/5) */
	private static final int DISCOUNT_5_MONTH = 3;
	/** Discount calculation for 12-month option (33% discount = 1/3) */
	private static final int DISCOUNT_12_MONTH = 3;
	/** Placeholder for currency formatting */
	private static final String CURRENCY_FORMAT = ".00";
	
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
		response.setContentType(CONTENT_TYPE_HTML);
		PrintWriter out = response.getWriter();
		User user = User.getUser(request.getParameter(PARAM_SIG));
		try {
			if (user.isPremium()) {  // do not allow the user to use this page
				Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
				if (a != null) response.sendRedirect("/" + a.assignmentType.toLowerCase() + "?sig=" + user.getTokenSignature());
				else out.println(MESSAGE_SUBSCRIPTION_ACTIVE);
				return;
			}
			String platform_deployment_id = user.platformId.equals(Util.getServerUrl())?user.platformId:ofy().load().type(Deployment.class).id(request.getParameter(PARAM_D)).now().platform_deployment_id;
			out.println(checkoutPage(user, platform_deployment_id));	
		} catch (Exception e) {
			ofy().delete().entity(user).now();
			out.println(Util.head(PAGE_TITLE_LOGOUT) + "<h1>You have been logged out</h1>" + Util.foot());
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
		response.setContentType(CONTENT_TYPE_JSON);
		PrintWriter out = response.getWriter();
		User user = null;
		JsonObject res = new JsonObject();
		
		try {
			user = User.getUser(request.getParameter(PARAM_SIG));
			if (user == null) throw new Exception(ERROR_NOT_LOGGED_IN);
			
			if (user.isPremium()) {  // do not allow the user to use this page
				Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
				if (a != null) response.sendRedirect("/" + a.assignmentType.toLowerCase() + "?t=" + Util.getToken(user.getTokenSignature()));
				else out.println(MESSAGE_SUBSCRIPTION_ACTIVE);
				return;
			}
			
			String userRequest = request.getParameter(PARAM_USER_REQUEST);
			switch (userRequest) {
			case USER_REQUEST_REDEEM_VOUCHER:
				Date exp = redeemVoucher(user, request);
				res.addProperty(JSON_EXP, exp.toString());
				out.println(res.toString());
				break;
			case USER_REQUEST_CREATE_ORDER:
				String order_id = createOrder(user, request);
				res.addProperty(JSON_ID, order_id);
				out.println(res.toString());
				break;
			case USER_REQUEST_COMPLETE_ORDER:
				out.println(completeOrder(user, request).toString());
				break;
			default:
				response.sendError(400);  // Bad request
			}
		} catch (Exception e) {
			res.addProperty(JSON_ERROR, e.getMessage());
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
		
		buf.append(Util.head(PAGE_TITLE) + Util.banner);
		
		// Store the PayPal client_id and user's sig value and platform_deployment_id in DOM elements so they can be accessed by javascript
		buf.append("<input type=").append(INPUT_TYPE_HIDDEN).append(" id=").append(HTML_ID_CLIENT_ID).append(" value='").append(Util.getPayPalClientId()).append("' />");
		buf.append("<input type=").append(INPUT_TYPE_HIDDEN).append(" id=").append(HTML_ID_SIG).append(" value=").append(user.getTokenSignature()).append(" />");
		buf.append("<input type=").append(INPUT_TYPE_HIDDEN).append(" id=").append(HTML_ID_PLATFORM_ID).append(" value=").append(platform_deployment_id).append(" />");
		buf.append("<input type=").append(INPUT_TYPE_HIDDEN).append(" id=").append(HTML_ID_PRICE).append(" value=").append(PRICE_USD).append(" />");

		PremiumUser u = ofy().load().type(PremiumUser.class).id(user.getHashedId()).now();		
		String title = (u != null && u.exp.before(now))?HEADING_SUBSCRIPTION_EXPIRED + df.format(u.exp):HEADING_SUBSCRIPTION;

		buf.append("<h1>").append(title).append("</h1>\n")
				.append(INTRO_TEXT)
				.append("<label><input type=").append(INPUT_TYPE_CHECKBOX).append(" id=").append(HTML_ID_TERMS)
				.append(" onChange=").append(JS_FUNC_SHOW_PAYMENT).append("> ").append(LABEL_TERMS)
				.append("<a href=/terms.html target=_blank>").append(LINK_TERMS).append("</a>.</label> <br/>")
				.append("<label><input type=").append(INPUT_TYPE_CHECKBOX).append(" id=").append(HTML_ID_NOREFUNDS)
				.append(" onChange=").append(JS_FUNC_SHOW_PAYMENT).append("> ").append(LABEL_NOREFUNDS).append("</label> <br/><br/>");
		
		buf.append("<div id=").append(HTML_ID_SELECT_PAYMENT).append(" style='display:none'>\n");

		buf.append(INSTRUCTION_VOUCHER)
				.append("<input id=").append(HTML_ID_VOUCHER_CODE).append(" type=").append(INPUT_TYPE_TEXT).append(" size=").append(INPUT_SIZE_VOUCHER).append(" />&nbsp;")
				.append("<button class='").append(CSS_CLASS_BTN_PRIMARY).append("' onclick=").append(JS_FUNC_REDEEM).append("('").append(user.getTokenSignature()).append("','").append(platform_deployment_id).append("')>&nbsp;").append(BTN_REDEEM).append("</button><br/>");
		
		buf.append("<hr>").append(SEPARATOR_OR).append("<br/>")
				.append("<div style='align: center'>")
				.append("<select id=").append(HTML_ID_NMONTHS).append(">")
				.append("<option value=").append(MONTHS_2).append(">").append(MONTHS_2).append(" months - $").append(MONTHS_2*PRICE_USD).append(" USD</option>")
				.append("<option value=").append(MONTHS_5).append(">").append(MONTHS_5).append(" months - $").append(4*PRICE_USD).append(" USD</option>")
				.append("<option value=").append(MONTHS_12).append(" selected>").append(MONTHS_12).append(" months - $").append(8*PRICE_USD).append(" USD</option>")
				.append("</select>&nbsp;")
				.append("<button class='").append(CSS_CLASS_BTN_PRIMARY).append("' onclick=").append(JS_FUNC_START_CHECKOUT).append(";>").append(BTN_CHECKOUT).append("</button>")
				.append("</div>");		
		buf.append("</div>");  // end of 'select_payment_method' div
		
		// Create a div for displaying the PayPal payment buttons (initially hidden)
		buf.append("<div id=").append(HTML_ID_PAYMENT_DIV).append(" style='display: none'>")
				.append(INSTRUCTION_PAYMENT).append("<br/><br/>")
				.append("<div id='").append(HTML_ID_PAYPAL_BUTTON).append("'></div>");
		buf.append("</div>");  // end of payment div
		
		buf.append("<div id=").append(HTML_ID_PROCEED).append(" style='display: none'><br/><br/>")
				.append("<a class='").append(CSS_CLASS_BTN_PRIMARY).append("' href='").append("/").append(a.assignmentType.toLowerCase()).append("?t=").append(Util.getToken(user.getTokenSignature())).append("'>").append(LINK_PROCEED).append("</a><br/><br/>")
				.append("</div>");
		
		buf.append("<script src='").append(SCRIPT_PAYPAL_SDK).append("?client-id=").append(Util.getPayPalClientId()).append("&enable-funding=venmo&disable-funding=paylater'></script>");
		buf.append("<script src='").append(PATH_CHECKOUT_JS).append("?r=").append(new Random().nextInt()).append("'></script>");
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
	Date redeemVoucher(User user, HttpServletRequest request) throws Exception {
		PremiumUser pu = null;
		
		String voucherCode = request.getParameter(PARAM_VOUCHER_CODE);
		if (voucherCode == null || voucherCode.isEmpty()) throw new Exception(ERROR_INVALID_VOUCHER);
		voucherCode = voucherCode.toUpperCase();
		Voucher v = ofy().load().type(Voucher.class).id(voucherCode).now();
		if (v == null) throw new Exception(ERROR_INVALID_VOUCHER);
		if (!v.activate()) { // check for duplicate submission by same user
			pu = ofy().load().type(PremiumUser.class).id(user.hashedId).safe();
			if (pu.order_id.equals(v.code)) return pu.exp;
			else throw new Exception(ERROR_DUPLICATE_VOUCHER);
		}

		// code is valid, so create a new PremiumUser
		Deployment deployment = ofy().load().type(Deployment.class).id(request.getParameter(PARAM_D)).safe();
		if (pu == null) pu = new PremiumUser(user.getHashedId(), v.months, v.paid, deployment.getOrganization(), v.code); // constructor automatically saves new entity
		return pu.exp;
	}
	
	/**
	 * Helper method to check if HTTP response indicates success (2xx status code).
	 * 
	 * @param conn The HTTP URL connection with response status code
	 * @return True if response code is 2xx (200-299), false otherwise
	 * @throws IOException if error occurs reading response code
	 */
	private boolean isSuccessResponse(HttpURLConnection conn) throws IOException {
		return conn.getResponseCode() / HTTP_SUCCESS_DIVISOR == HTTP_SUCCESS_CODE;
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
			if (!auth_json.has(JSON_ACCESS_TOKEN) || !auth_json.has(JSON_EXP)) {
				throw new Exception("Cache miss");
			}
			Date exp = new Date(auth_json.get(JSON_EXP).getAsLong());
			if (exp.after(now)) {
				return auth_json.get(JSON_ACCESS_TOKEN).getAsString();
			}
			throw new Exception("Token expired");
		} catch (Exception e) {  // retrieve a new auth token from PayPal
			
			String auth = Base64.getEncoder().encodeToString((Util.getPayPalClientId()+":"+Util.getPayPalClientSecret()).getBytes());

			String baseUrl = Util.projectId.equals(PROJECT_ID_DEV)?PAYPAL_BASE_URL_SANDBOX:PAYPAL_BASE_URL_PROD;
			String body = OAUTH_GRANT_TYPE;

			URL u = new URI(baseUrl + PAYPAL_PATH_OAUTH).toURL();
			HttpURLConnection uc = (HttpURLConnection) u.openConnection();
			uc.setDoOutput(true);
			uc.setDoInput(true);
			uc.setRequestMethod("POST");
			uc.setRequestProperty("Authorization", AUTH_BASIC_PREFIX + auth);
			uc.setRequestProperty("Content-Type", CONTENT_TYPE_FORM);
			uc.setRequestProperty("Accept", CONTENT_TYPE_JSON);
			uc.setRequestProperty("charset", "utf-8");
			uc.setUseCaches(false);
			uc.setReadTimeout(HTTP_TIMEOUT_MS);
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
					int expires_in = auth_json.get(JSON_EXPIRES_IN).getAsInt();  // seconds from now
					Long exp = new Date(new Date().getTime() + expires_in*MILLIS_PER_SECOND - TOKEN_CACHE_GRACE_MS).getTime();
					auth_json.addProperty(JSON_EXP, exp);
					return auth_json.get(JSON_ACCESS_TOKEN).getAsString();
				} else {
					reader = new BufferedReader(new InputStreamReader(uc.getErrorStream()));				
					JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
					reader.close();
					Util.sendEmail(EMAIL_FROM, EMAIL_ADMIN, EMAIL_SUBJECT_AUTH, json.toString());
					throw new Exception(ERROR_PAYPAL_AUTH + json.toString());
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
		int nMonths = Integer.parseInt(request.getParameter(PARAM_NMONTHS));
		int value = PRICE_USD * (nMonths - nMonths/DISCOUNT_12_MONTH);
		
		String platform_deployment_id = request.getParameter(PARAM_D);
		String request_id = UUID.randomUUID().toString();
		
		JsonObject order_data = new JsonObject();
		order_data.addProperty(JSON_INTENT, PAYPAL_INTENT_CAPTURE);
		  JsonArray purchaseUnits = new JsonArray();
		    JsonObject subscription = new JsonObject();
		    subscription.addProperty(JSON_DESCRIPTION, nMonths + " - month Chem4AP subscription");
		      JsonObject amount = new JsonObject();
		      amount.addProperty(JSON_CURRENCY_CODE, CURRENCY_USD);
		      amount.addProperty(JSON_VALUE, (PRICE_USD * (nMonths - nMonths/DISCOUNT_12_MONTH)) + CURRENCY_FORMAT);  // calculated discount schedule
		    subscription.add(JSON_AMOUNT, amount);
		 purchaseUnits.add(subscription);
		order_data.add(JSON_PURCHASE_UNITS, purchaseUnits);
		  JsonObject paymentSource = JsonParser.parseString("{'paypal':{'experience_context':{'shipping_preference':'NO_SHIPPING'}}}").getAsJsonObject();
		order_data.add(JSON_PAYMENT_SOURCE, paymentSource);
		String baseUrl = Util.projectId.equals(PROJECT_ID_DEV)?PAYPAL_BASE_URL_SANDBOX:PAYPAL_BASE_URL_PROD;
		
		URL u = new URI(baseUrl + PAYPAL_PATH_ORDERS).toURL();
		HttpURLConnection uc = (HttpURLConnection) u.openConnection();
		uc.setRequestMethod("POST");
		uc.setRequestProperty("Authorization", AUTH_BEARER_PREFIX + generateAccessToken());
		uc.setRequestProperty(HEADER_PAYPAL_REQUEST_ID, request_id);
		uc.setRequestProperty("Content-Type", CONTENT_TYPE_JSON);
		uc.setDoOutput(true);
		
		try (OutputStreamWriter writer = new OutputStreamWriter(uc.getOutputStream())) {
			writer.write(order_data.toString());
			writer.flush();
		}

		BufferedReader reader = null;
		try {
			if (isSuccessResponse(uc)) {
				reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));				
				String order_id = JsonParser.parseReader(reader).getAsJsonObject().get(JSON_ID).getAsString();
				reader.close();
				ofy().save().entity(new PayPalOrder(order_id, new Date(), order_data.toString(), nMonths, value, user, platform_deployment_id, request_id));
				return order_id;
			} else {
				reader = new BufferedReader(new InputStreamReader(uc.getErrorStream()));				
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				reader.close();
				Util.sendEmail(EMAIL_FROM, EMAIL_ADMIN, EMAIL_SUBJECT_ORDER, json.toString());
				throw new Exception(ERROR_ORDER_CREATION + json.toString());
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
		String order_id = request.getParameter(PARAM_ORDER_ID);
		PayPalOrder order = ofy().load().type(PayPalOrder.class).id(order_id).safe();
		String organization = "Chem4AP";
		try {
			Deployment deployment = ofy().load().type(Deployment.class).id(order.platform_deployment_id).safe();
			organization = deployment.organization;
		} catch (Exception e) {}
		String baseUrl = Util.projectId.equals(PROJECT_ID_DEV)?PAYPAL_BASE_URL_SANDBOX:PAYPAL_BASE_URL_PROD;
		
		URL u = new URI(baseUrl + PAYPAL_PATH_ORDERS + "/" + order_id + PAYPAL_PATH_CAPTURE).toURL();
		HttpURLConnection uc = (HttpURLConnection) u.openConnection();
		uc.setRequestMethod("POST");
		uc.setRequestProperty("Authorization", AUTH_BEARER_PREFIX + generateAccessToken());
		uc.setRequestProperty(HEADER_PAYPAL_REQUEST_ID, order.request_id);
		uc.setRequestProperty("Content-Type", CONTENT_TYPE_JSON);
		
		BufferedReader reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));				
		JsonObject resp = JsonParser.parseReader(reader).getAsJsonObject();
		reader.close();
		
		order.status = resp.get(JSON_STATUS).getAsString();  // update order status
		if (order.status.equals(PAYPAL_STATUS_COMPLETED)) {  // create new PremiumUser
			PremiumUser pu = new PremiumUser(user.getHashedId(), order.nMonths, order.value, organization, order.id);
			resp.addProperty(JSON_EXPIRES, pu.exp.toString());
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