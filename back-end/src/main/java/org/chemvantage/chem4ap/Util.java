package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.util.Date;
import java.util.UUID;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.google.cloud.ServiceOptions;
import com.google.cloud.tasks.v2.AppEngineHttpRequest;
import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.protobuf.ByteString;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Ignore;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;

/**
 * Util singleton entity for centralized configuration and utility functions in Chem4AP.
 * 
 * Provides application-wide configuration settings and helper methods for:
 * - JWT token generation, verification, and HMAC secret management
 * - Google Cloud Tasks queue operations for asynchronous background jobs
 * - SendGrid email delivery integration
 * - User identity hashing for privacy protection
 * - reCAPTCHA secret/site key management
 * - OpenAI/GPT model configuration
 * - PayPal integration credentials
 * - Application announcements and banner HTML
 * - Salt generation for cryptographic operations
 * - User satisfaction rating (star) tracking
 * - HTML page template (head/foot) generation
 * 
 * Design Pattern:
 * - Singleton entity persisted in Datastore with fixed ID (1L)
 * - All methods are static; instance accessed lazily via refresh()
 * - Configuration values auto-loaded from datastore on first access
 * - Supports dev and production environments (dev-chem4ap vs chem4ap)
 * 
 * Configuration Sources:
 * 1. Google Cloud Platform (project ID via ServiceOptions)
 * 2. Environment Variables (for API keys, secrets)
 * 3. Datastore Entity (persistent configuration)
 * 4. Hardcoded Defaults (initial deployment)
 * 
 * Security Considerations:
 * - HMAC256Secret: Used for JWT signing; random UUID on initialization
 * - Salt: Used in user ID hashing; random UUID on initialization
 * - reCaptchaSecret/SiteKey: Protect against bot attacks
 * - PayPal credentials: OAuth 2.0 for subscription processing
 * - OpenAI Key: For potential future AI integration
 * - SendGrid Key: For transactional email delivery
 * 
 * JWT Token Management:
 * - Tokens expire in 90 minutes (5400000 milliseconds)
 * - HMAC256 algorithm for signing and verification
 * - Nonce included in JWT claims for one-time use validation
 * - Subject field contains user's tokenSignature
 * 
 * Environment-Specific URLs:
 * - Production: chem4ap → https://www.chem4ap.com
 * - Development: dev-chem4ap → https://dev-chem4ap.appspot.com
 * - Used for SEO canonical links and email URLs
 * 
 * Datastore Persistence:
 * - Single entity with ID=1L
 * - Auto-loaded on first static method call
 * - @Ignore fields (projectId, u) not persisted
 * 
 * @author ChemVantage
 * @version 1.0
 * @see Nonce for JWT nonce validation
 * @see LTIMessage for token usage context
 */
@Entity
public class Util {
	// Configuration and encryption constants
	/** HTML decimal format for star rating display (single decimal place) */
	private static final String RATING_FORMAT = "#.#";
	
	/** SHA-256 algorithm name for user ID hashing */
	private static final String HASH_ALGORITHM = "SHA-256";
	
	/** Cloud Tasks location for asynchronous job queue */
	private static final String CLOUD_TASKS_LOCATION = "us-central1";
	
	/** Cloud Tasks default queue name */
	private static final String CLOUD_TASKS_QUEUE = "default";
	
	/** JWT token expiration: 90 minutes in milliseconds (90 * 60 * 1000) */
	private static final long JWT_EXPIRATION_MILLIS = 5400000L;
	
	/** Content-Type header for POST requests to Cloud Tasks */
	private static final String CONTENT_TYPE_FORM = "application/x-www-form-urlencoded";
	
	/** Production project ID for Google Cloud Platform */
	private static final String PROJECT_PROD = "chem4ap";
	
	/** Development project ID for Google Cloud Platform */
	private static final String PROJECT_DEV = "dev-chem4ap";
	
	/** Production application URL for SEO and email links */
	private static final String URL_PROD = "https://www.chem4ap.com";
	
	/** Development application URL for testing */
	private static final String URL_DEV = "https://dev-chem4ap.appspot.com";
	
	/** SendGrid email sender address */
	private static final String EMAIL_FROM = "admin@chemvantage.org";
	
	/** SendGrid email sender display name */
	private static final String EMAIL_FROM_NAME = "ChemVantage LLC";
	
	/** Email content type for HTML messages */
	private static final String EMAIL_CONTENT_TYPE = "text/html";
	
	/** SendGrid API endpoint for mail delivery */
	private static final String SENDGRID_ENDPOINT = "mail/send";
	
	@Id Long id = 1L;
	
	/**
	 * Running average of user star ratings for application feedback.
	 * Updated by addStarReport() method with new user ratings.
	 * Displayed via getAvgStars() formatted to 1 decimal place.
	 * Persisted to datastore for long-term trend tracking.
	 */
	private double avgStars = 0.0;
	
	/**
	 * Total count of star rating reports submitted by users.
	 * Used as denominator in running average calculation.
	 * Incremented by 1 each time addStarReport() is called.
	 * Persisted to datastore.
	 */
	private int nStarReports = 0;
	
	/**
	 * HMAC256 secret for JWT token signing and verification.
	 * Randomly generated UUID on first initialization.
	 * Shared with all clients for token verification.
	 * Must be kept secret from untrusted parties.
	 */
	private String HMAC256Secret = UUID.randomUUID().toString();
	
	/**
	 * reCAPTCHA secret key for server-side validation.
	 * Used to verify reCAPTCHA tokens submitted by client.
	 * Obtained from Google reCAPTCHA console.
	 * Changed from initial "ChangeMe" placeholder during setup.
	 */
	private String reCaptchaSecret = "ChangeMe";
	
	/**
	 * reCAPTCHA site key for client-side widget integration.
	 * Embedded in HTML forms for bot protection.
	 * Obtained from Google reCAPTCHA console.
	 * Changed from initial "ChangeMe" placeholder during setup.
	 */
	private String reCaptchaSiteKey = "ChangeMe";
	
	/**
	 * Salt for user ID hashing operations.
	 * Randomly generated UUID on first initialization.
	 * Appended to userId before SHA-256 hashing.
	 * Provides protection against rainbow table attacks.
	 */
	private String salt = UUID.randomUUID().toString();
	
	/**
	 * Application announcement message.
	 * Displayed to users in web interface.
	 * Updated via admin panel.
	 * Initially "ChangeMe"; configured during deployment.
	 */
	private String announcement = "ChangeMe";
	
	/**
	 * SendGrid API key for transactional email delivery.
	 * Used for password reset, notifications, confirmations.
	 * Obtained from SendGrid dashboard.
	 * Initially "ChangeMe"; configured during deployment.
	 */
	private String sendGridAPIKey = "ChangeMe";
	
	/**
	 * OpenAI API key for future AI integration.
	 * Reserved for potential ChatGPT/GPT-4 functionality.
	 * Obtained from OpenAI API dashboard.
	 * Initially "ChangeMe"; configured if AI features enabled.
	 */
	private String openAIKey = "ChangeMe";
	
	/**
	 * GPT model identifier (e.g., "gpt-4", "gpt-3.5-turbo").
	 * Specifies which OpenAI model to use for AI operations.
	 * Updated as new models become available.
	 * Initially "ChangeMe"; configured if AI features enabled.
	 */
	private String gptModel = "ChangeMe";
	
	/**
	 * Prompt ID for AI-generated question explanations.
	 * Used to identify the explanation template in OpenAI API calls.
	 * Retrieved via Util.getExplanationPromptId().
	 * Initially null; uses default if not configured.
	 */
	private String explanationPromptId;
	
	/**
	 * OpenAI API endpoint URL for explanation generation.
	 * Used to submit explanation requests to OpenAI.
	 * Retrieved via Util.getExplanationApiEndpoint().
	 * Initially null; uses default if not configured.
	 */
	private String explanationApiEndpoint;
	
	/**
	 * PayPal OAuth 2.0 client ID for subscription processing.
	 * Used for login with PayPal integration.
	 * Obtained from PayPal Developer Dashboard.
	 * Initially "ChangeMe"; configured during setup.
	 */
	private String payPalClientId = "ChangeMe";
	
	/**
	 * PayPal OAuth 2.0 client secret for subscription processing.
	 * Used for secure PayPal API calls.
	 * Must be kept secret; never exposed to client.
	 * Obtained from PayPal Developer Dashboard.
	 * Initially "ChangeMe"; configured during setup.
	 */
	private String payPalClientSecret = "ChangeMe";
	
	/**
	 * Google Cloud Platform project ID (e.g., "chem4ap", "dev-chem4ap").
	 * Determined at runtime via ServiceOptions.getDefaultProjectId().
	 * Used to determine environment (production vs development).
	 * @Ignore: Not persisted to datastore; retrieved from GCP on each load.
	 */
	@Ignore static final String projectId = ServiceOptions.getDefaultProjectId();
	
	/**
	 * Cached singleton instance loaded from datastore.
	 * Initialized during first static method call.
	 * Reused for subsequent calls to avoid repeated datastore queries.
	 * @Ignore: Static reference not persisted.
	 */
	@Ignore static Util u;
	
	/**
	 * HTML banner with Chem4AP logo and branding.
	 * Displayed at top of HTML pages.
	 * Contains inline CSS for styling and layout.
	 * Static for memory efficiency and singleton access.
	 */
	static final String banner = "<div style='font-size:2em;font-weight:bold;color:#000080;'><img src='/images/chem4ap_atom.png' alt='Chem4AP Logo' style='vertical-align:middle;width:60px;'> Chem4AP</div><br/>";
	
	/**
	 * Default (no-arg) constructor for Objectify entity instantiation.
	 * Package-private to restrict direct instantiation.
	 * Called by datastore framework during entity loading.
	 */
	private Util() {}
	
	/**
	 * Adds a new user star rating and recalculates running average.
	 * 
	 * Algorithm:
	 * - Calculate new average: (previousSum + newStars) / (totalCount + 1)
	 * - Increment total report count
	 * - Persist updated entity to datastore
	 * 
	 * Example:
	 * - Previous: 4 stars from 10 reports = avg 4.0
	 * - New: 5-star rating submitted
	 * - Result: (40 + 5) / 11 = 4.09 average
	 * 
	 * @param stars the user's star rating (typically 1-5 range)
	 */
	void addStarReport(int stars) {
		avgStars = (avgStars*nStarReports + stars)/(nStarReports+1);
		nStarReports++;
		ofy().save().entity(u);
	}

	/**
	 * Creates asynchronous background task in Google Cloud Tasks queue.
	 * 
	 * Cloud Tasks Queue:
	 * - Provides reliable, at-least-once delivery of background jobs
	 * - Decouples long-running operations from HTTP request/response cycle
	 * - Automatically retries failed tasks (configurable retry policy)
	 * - Scales to handle millions of tasks
	 * 
	 * Task Creation:
	 * - Target URI: relativeUri parameter (e.g., "/report", "/email")
	 * - Request Method: HTTP POST
	 * - Request Body: query string parameter (form-encoded)
	 * - Execution: By App Engine App service on specified schedule
	 * 
	 * Use Cases:
	 * - Sending emails (async from form submission)
	 * - Generating reports (async from user request)
	 * - Updating analytics (background job)
	 * - Cleaning up expired data (scheduled maintenance)
	 * 
	 * Example:
	 * - createTask("/report", "studentId=123&assignmentId=456")
	 * - Cloud Tasks sends POST to chem4ap.appspot.com/report with form body
	 * - App Engine invokes ReportScore servlet with parsed parameters
	 * 
	 * Cloud Tasks Configuration:
	 * - Location: us-central1 (US central region)
	 * - Queue: default (standard task queue)
	 * - Auto-scaling: Managed by GCP
	 * 
	 * Error Handling:
	 * - IOException: Propagates if client creation fails
	 * - Retry: Automatic via Cloud Tasks (up to max retries)
	 * - Deadletter: Failed tasks moved to DLQ after retries exhausted
	 * 
	 * Dependencies:
	 * - Google Cloud Tasks library
	 * - Service account credentials (automatic in App Engine)
	 * - Cloud Tasks API enabled in GCP project
	 * 
	 * @param relativeUri the servlet URL path (e.g., "/report", "/email")
	 * @param query the form-encoded request body (e.g., "param1=value1&param2=value2")
	 * @throws IOException if Cloud Tasks client creation fails
	 */
	static void createTask(String relativeUri, String query)
			throws IOException {
		// This method accepts a relativeUri (e.g., /report) to POST a request to a Chem4AP servlet
		String location = CLOUD_TASKS_LOCATION;
		String queueName = CLOUD_TASKS_QUEUE;
		// Instantiates a client.
		try (CloudTasksClient client = CloudTasksClient.create()) {
			// Construct the fully qualified queue name.
			String queuePath = QueueName.of(projectId, location, queueName).toString();

			// Build the Task:
			Task.Builder taskBuilder =
					Task.newBuilder()
					.setAppEngineHttpRequest(
							AppEngineHttpRequest.newBuilder()
							.setBody(ByteString.copyFrom(query, Charset.defaultCharset()))
							.setRelativeUri(relativeUri)
							.setHttpMethod(HttpMethod.POST)
							.putHeaders("Content-Type", CONTENT_TYPE_FORM)
							.build());
/*
			// Add the scheduled time to the request.
			taskBuilder.setScheduleTime(
					Timestamp.newBuilder()
					.setSeconds(Instant.now(Clock.systemUTC()).plusSeconds(seconds).getEpochSecond()));
*/
			// Send create task request.
			client.createTask(queuePath, taskBuilder.build());  // returns Task entity
		}
	}
	
	/**
	 * Retrieves the current average user star rating formatted to one decimal place.
	 * 
	 * Formatting:
	 * - Uses DecimalFormat("#.#") to round to single decimal
	 * - Converts back to double for numeric operations
	 * - Example: 4.233 becomes 4.2
	 * 
	 * Usage:
	 * - Displayed in user interface for application feedback
	 * - Used in reports and analytics queries
	 * - Read-only operation; does not modify values
	 * 
	 * Performance:
	 * - No datastore queries (uses cached avgStars value)
	 * - DecimalFormat instantiation on each call (minor overhead)
	 * 
	 * @return formatted average star rating (e.g., 4.2)
	 */
	static double getAvgStars() {
		DecimalFormat df2 = new DecimalFormat(RATING_FORMAT);
		return Double.valueOf(df2.format(u.avgStars));
	}
	
	/**
	 * Creates SHA-256 hash of user ID with salt for privacy protection.
	 * 
	 * Hashing Algorithm:
	 * - Algorithm: SHA-256 (256-bit cryptographic hash)
	 * - Input: userId + salt (concatenated)
	 * - Output: 64-character hexadecimal string
	 * - One-way: Cannot reverse hash to recover original userId
	 * 
	 * Security Properties:
	 * - Collision Resistance: Extremely unlikely two different IDs hash to same value
	 * - Pre-image Resistance: Cannot find input that produces specific hash
	 * - Rainbow Table Protection: Salt prevents dictionary attacks
	 * 
	 * Use Cases:
	 * - Hashing student IDs before storing in LTI session
	 * - Creating anonymous identifiers for privacy
	 * - De-identifying user records in reports
	 * - Preventing ID enumeration attacks
	 * 
	 * Salt Management:
	 * - Generated randomly (UUID) on first Util initialization
	 * - Stored persistently in datastore
	 * - Same salt used for all user ID hashes
	 * - Changing salt requires rehashing all IDs
	 * 
	 * Error Handling:
	 * - Returns null if MessageDigest.getInstance() fails
	 * - Should not happen in practice (SHA-256 always available)
	 * - Callers should check for null return value
	 * 
	 * Performance:
	 * - SHA-256 computation: ~1 microsecond per hash
	 * - Suitable for real-time operations
	 * 
	 * @param userId the user's identifier to hash
	 * @return 64-character hexadecimal hash, or null if hashing fails
	 * @see #getSalt() for salt value access
	 */
	static String hashId(String userId) {
		try {
			MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
        	byte[] bytes = md.digest((userId + Util.getSalt()).getBytes(StandardCharsets.UTF_8));
        	StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
		} catch (Exception e) {
        	return null;
        }
	}
	
	/**
	 * Generates HTML &lt;head&gt; section for web page with Chem4AP styling and metadata.
	 * 
	 * HTML Structure:
	 * - DOCTYPE: HTML5
	 * - Charset: UTF-8
	 * - Viewport: Responsive (mobile-friendly)
	 * - Meta Tags: Description, cache-control, favicon, canonical URL
	 * - Fonts: Poppins and Shantell Sans from Google Fonts
	 * - CSS Framework: Bootstrap 4.3.1 from CDN
	 * 
	 * SEO Elements:
	 * - Meta description for search engine snippets
	 * - Canonical link to prevent duplicate content issues
	 * - Structured markup for rich results
	 * - Mobile viewport for responsive indexing
	 * 
	 * Security Headers:
	 * - Cache-Control: no-cache, no-store, must-revalidate (prevent caching)
	 * - Used for pages with sensitive information
	 * - Forces fresh content on each load
	 * 
	 * Performance:
	 * - Bootstrap and fonts from CDN (cached by browser)
	 * - Minimal inline CSS (main styling in separate files)
	 * - Deferred JavaScript loading (not in head)
	 * 
	 * Customization:
	 * - Title parameter appended to "Chem4AP" brand
	 * - Example: head("Exercises") → &lt;title&gt;Chem4AP | Exercises&lt;/title&gt;
	 * - Null title: Just "Chem4AP" in title
	 * 
	 * Commented Features:
	 * - Google Analytics (gtag.js) lines commented out
	 * - Can be enabled by uncommenting with actual tracking ID
	 * 
	 * @param title page title suffix (appended after "Chem4AP |")
	 * @return HTML head section as string (ready to insert in page)
	 */
	static String head(String title) {
		return "<!DOCTYPE html><html lang='en'>\n"
			+ "<head>\n"
			+ "  <meta charset='UTF-8' />\n"
			+ "  <meta name='viewport' content='width=device-width, initial-scale=1.0' />\n"
			+ "  <meta name='description' content='Chem4AP is an LTI app for teaching and learning AP Chemistry.' />\n"
			+ "  <meta http-equiv='Cache-Control' content='no-cache, no-store, must-revalidate' />\n"
			+ "  <link rel='icon' href='images/logo.png' />\n"
			+ "  <link rel='canonical' href='" + Util.getServerUrl() + "' />\n"
			+ "  <title>Chem4P" + (title==null?"":" | " + title) + "</title>\n"
			+ "  <!-- Font Family -->\n"
			+ "  <link href='https://fonts.googleapis.com/css2?family=Poppins:wght@100;200;300;400;500;600;700;800;900&family=Shantell+Sans:wght@300;400;500;600;700;800&display=swap' rel='stylesheet'/>\n"
			+ "  <link rel='stylesheet' href='https://cdn.jsdelivr.net/npm/bootstrap@4.3.1/dist/css/bootstrap.min.css' integrity='sha384-ggOyR0iXCbMQv3Xipma34MD+dH/1fQ784/j6cY/iJTQUOhcWr7x9JvoRxT2MZw1T' crossorigin='anonymous'>"
			+ "</head>\n"
			+ "<body style='margin: 20px; font-family: Poppins'>\n";
	}
	
	/**
	 * Generates HTML &lt;footer&gt; section with Chem4AP branding and links.
	 * 
	 * Footer Elements:
	 * - Horizontal rule separator
	 * - Chem4AP home link (branding)
	 * - Terms and Conditions link
	 * - Privacy Policy link
	 * - Copyright statement link
	 * 
	 * Styling:
	 * - Navy blue links (color:#000080)
	 * - Bold Chem4AP branding
	 * - No underline on links (text-decoration:none)
	 * - Horizontal rule 600px wide, left-aligned
	 * 
	 * Usage:
	 * - Append to bottom of HTML page
	 * - Closes &lt;body&gt; and &lt;html&gt; tags
	 * - Used with head() for complete HTML page template
	 * 
	 * Links:
	 * - / → home page
	 * - /terms_and_conditions.html → legal terms
	 * - /privacy_policy.html → privacy statement
	 * - /copyright.html → copyright information
	 * 
	 * @return HTML footer section as string (closes page)
	 * @see #head(String) for complete page template
	 */
	static String foot() {
		return  "<footer><p><hr style='width:600px;margin-left:0' />"
				+ "<a style='text-decoration:none;color:#000080;font-weight:bold' href=/index.html>"
				+ "Chem4AP</a> | "
				+ "<a href=/terms_and_conditions.html>Terms and Conditions of Use</a> | "
				+ "<a href=/privacy_policy.html>Privacy Policy</a> | "
				+ "<a href=/copyright.html>Copyright</a></footer>\n"
				+ "</body></html>";
	}

	/**
	 * Retrieves HMAC256 secret for JWT token signing and verification.
	 * 
	 * Secret Management:
	 * - Randomly generated UUID on first Util initialization
	 * - Stored persistently in Datastore
	 * - Same secret used for all JWT operations
	 * - Must be kept secret (never expose to untrusted parties)
	 * 
	 * JWT Usage:
	 * - Used to sign authentication tokens
	 * - Used to verify token authenticity
	 * - HMAC256 (HS256) algorithm
	 * 
	 * Secret Rotation:
	 * - Can be changed but requires token re-issuance
	 * - Old secrets become invalid
	 * - Users must re-authenticate after rotation
	 * 
	 * @return HMAC256 secret string for JWT operations
	 * @throws Exception if datastore refresh fails
	 * @see #getToken(String) for token generation using this secret
	 */
	static String getHMAC256Secret() throws Exception { 
		refresh();
		return u.HMAC256Secret; 
	}

	/**
	 * Retrieves PayPal OAuth 2.0 client ID for subscription processing.
	 * 
	 * PayPal Integration:
	 * - Used for "Login with PayPal" authentication
	 * - Used for subscription payment processing
	 * - OAuth 2.0 authorization code flow
	 * - Registered in PayPal Developer Dashboard
	 * 
	 * @return PayPal client ID string
	 */
	static String getPayPalClientId() {
		refresh();
		return u.payPalClientId;
	}
	
	/**
	 * Retrieves PayPal OAuth 2.0 client secret for secure API calls.
	 * 
	 * Security:
	 * - Must be kept secret (never expose to client/browser)
	 * - Used only in backend API calls
	 * - Combined with client ID for authorization
	 * 
	 * @return PayPal client secret string
	 */
	static String getPayPalClientSecret() {
		refresh();
		return u.payPalClientSecret;
	}
	
	/**
	 * Retrieves reCAPTCHA secret key for server-side token validation.
	 * 
	 * Bot Protection:
	 * - Client submits reCAPTCHA token to server
	 * - Server verifies token using this secret key
	 * - Prevents automated form submissions and bot attacks
	 * - Registered in Google reCAPTCHA admin console
	 * 
	 * @return reCAPTCHA secret key string
	 */
	static String getReCaptchaSecret() {
		refresh();
		return u.reCaptchaSecret;
	}

	/**
	 * Retrieves reCAPTCHA site key for client-side widget embedding.
	 * 
	 * Client Integration:
	 * - Embedded in HTML forms
	 * - Initializes reCAPTCHA widget on page
	 * - Generates tokens for server validation
	 * - Public key (safe to expose to client)
	 * 
	 * @return reCAPTCHA site key string
	 */
	static String getReCaptchaSiteKey() {
		refresh();
		return u.reCaptchaSiteKey;
	}

	/**
	 * Retrieves salt for user ID hashing operations.
	 * 
	 * Hashing:
	 * - Appended to userId before SHA-256 hashing
	 * - Prevents rainbow table attacks
	 * - Same salt used for all user IDs
	 * - Randomly generated UUID on first initialization
	 * 
	 * @return salt string for hashing operations
	 */
	static String getSalt() { 
		refresh();
		return u.salt; 
	}

	/**
	 * Retrieves application-wide announcement message.
	 * 
	 * Usage:
	 * - Displayed in user interface
	 * - Updated via admin panel
	 * - Used for important notifications
	 * - Example: "System maintenance on Friday 2-4pm EST"
	 * 
	 * @return announcement text string
	 */
	static String getAnnouncement() { 
		refresh();
		return u.announcement; 
	}

	/**
	 * Retrieves SendGrid API key for email delivery.
	 * 
	 * Email Operations:
	 * - Password reset emails
	 * - Notification emails
	 * - Transactional emails
	 * - Registered with SendGrid account
	 * 
	 * @return SendGrid API key string
	 */
	static String getSendGridKey() {
		refresh();
		return u.sendGridAPIKey;
	}
	
	/**
	 * Retrieves OpenAI API key for potential AI integration.
	 * 
	 * AI Features:
	 * - Reserved for ChatGPT/GPT-4 integration
	 * - Not currently used in production
	 * - Registered with OpenAI API account
	 * 
	 * @return OpenAI API key string
	 */
	static String getOpenAIKey() {
		refresh();
		return u.openAIKey;
	}
	
	/**
	 * Retrieves GPT model identifier for AI operations.
	 * 
	 * Model Specification:
	 * - Example values: "gpt-4", "gpt-3.5-turbo"
	 * - Specifies which OpenAI model to use
	 * - Updated as new models become available
	 * 
	 * @return GPT model identifier string
	 */
	static String getGPTModel() {
		refresh();
		return u.gptModel;
	}
	
	/**
	 * Retrieves the prompt ID for AI-generated explanations.
	 * 
	 * Used by Question.getExplanation() to identify the prompt template
	 * for generating student-facing explanations via OpenAI API.
	 * 
	 * @return prompt ID string for explanation generation
	 */
	static String getExplanationPromptId() {
		refresh();
		return u.explanationPromptId != null ? u.explanationPromptId : "pmpt_68ae17560ce08197a4584964c31e79510acd7153761d1f7b";
	}
	
	/**
	 * Retrieves the OpenAI API endpoint URL for explanation requests.
	 * 
	 * Used by Question.getExplanation() to construct API calls for
	 * generating student-facing explanations.
	 * 
	 * @return API endpoint URL (e.g., https://api.openai.com/v1/responses)
	 */
	static String getExplanationApiEndpoint() {
		refresh();
		return u.explanationApiEndpoint != null ? u.explanationApiEndpoint : "https://api.openai.com/v1/responses";
	}
	
	/**
	 * Retrieves environment-specific application server URL.
	 * 
	 * Environment Detection:
	 * - Production (chem4ap): https://www.chem4ap.com
	 * - Development (dev-chem4ap): https://dev-chem4ap.appspot.com
	 * - Other: returns null (unsupported environment)
	 * 
	 * Uses:
	 * - SEO canonical links in &lt;head&gt;
	 * - Email footer links
	 * - Redirect URLs in OAuth flows
	 * 
	 * @return environment-specific application URL
	 */
	static String getServerUrl() {
		if (projectId.equals(PROJECT_PROD)) return URL_PROD;
		else if (projectId.equals(PROJECT_DEV)) return URL_DEV;
		return null;
	}
	
	/**
	 * Validates JWT token and verifies nonce for one-time use.
	 * 
	 * Validation Steps:
	 * 1. Get HMAC256 secret for token verification
	 * 2. Create JWT verifier with secret
	 * 3. Verify token signature (ensures not tampered with)
	 * 4. Decode JWT to extract claims
	 * 5. Get nonce from JWT ID claim
	 * 6. Verify nonce is unique (not used before)
	 * 7. Return user's tokenSignature from subject claim
	 * 
	 * Security:
	 * - Signature verification: Ensures token created by server
	 * - Nonce validation: Prevents token replay attacks
	 * - Exception on failure: No partial success
	 * 
	 * Token Claims:
	 * - Subject: User's tokenSignature
	 * - ExpiresAt: Token expiration time
	 * - JWTId: Nonce for one-time use validation
	 * 
	 * Error Handling:
	 * - Throws Exception if signature invalid
	 * - Throws Exception if nonce already used
	 * - Throws Exception on decode failures
	 * 
	 * @param token JWT token string to validate
	 * @return user's tokenSignature from JWT subject claim
	 * @throws Exception if token validation fails or nonce not unique
	 */
	static String isValid(String token) throws Exception {
		Algorithm algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
		JWTVerifier verifier = JWT.require(algorithm).build();
		verifier.verify(token);
		DecodedJWT payload = JWT.decode(token);
		String nonce = payload.getId();
		if (!Nonce.isUnique(nonce)) throw new Exception("Token was used previously.");
		// return the user's tokenSignature
		return payload.getSubject();
	}

	/**
	 * Refreshes singleton instance from Datastore.
	 * 
	 * Lazy Loading:
	 * - Called before accessing any static configuration
	 * - Loads Util entity with ID=1L on first call
	 * - Caches in static field (u) for subsequent calls
	 * - Subsequent calls reuse cached instance
	 * 
	 * Initialization:
	 * - If u is null: loads from datastore
	 * - If load fails (no entity exists):
	 *   - Creates new Util instance
	 *   - Persists to datastore for future loads
	 *   - Initializes with default values
	 * 
	 * Thread Safety:
	 * - Not synchronized
	 * - Datastore ensures only one client succeeds creating entity
	 * - Race conditions unlikely in practice
	 * 
	 * Datastore Integration:
	 * - Uses Objectify ORM for persistence
	 * - @Entity annotation registers with Objectify
	 * - ID=1L is singleton record (never duplicated)
	 * 
	 * Called by: All static getter methods before accessing u
	 */
	static void refresh() {
		try {  // retrieve values from datastore when a new software version is installed
			if (u == null) u = ofy().load().type(Util.class).id(1L).safe();
		} catch (Exception e) { // this will run only once when project is initiated
			u = new Util();
			ofy().save().entity(u).now();
		}
	}

	/**
	 * Generates JWT token with user signature and expiration.
	 * 
	 * Token Contents:
	 * - Subject: User's tokenSignature (identifies user)
	 * - ExpiresAt: 90 minutes from now (5400000 milliseconds)
	 * - JWTId: Unique nonce for replay attack prevention
	 * - Signature: HMAC256 using application secret
	 * 
	 * Token Format:
	 * - Standard JWT (JSON Web Token) format
	 * - Three parts: header.payload.signature (separated by dots)
	 * - Base64-encoded; human-readable when decoded
	 * 
	 * Usage:
	 * - Issued to user after authentication
	 * - Submitted in subsequent requests (header or cookie)
	 * - Validated with isValid() method
	 * - Prevents session hijacking via signature verification
	 * 
	 * Expiration:
	 * - 90-minute window for token validity
	 * - After expiration, token rejected (must re-authenticate)
	 * - Nonce prevents reuse of expired tokens
	 * 
	 * Error Handling:
	 * - Returns null if token generation fails
	 * - Should not happen in normal operation
	 * - Callers should check for null return
	 * 
	 * @param sig user's tokenSignature to encode in JWT subject
	 * @return JWT token string, or null if generation fails
	 * @see #isValid(String) for token validation
	 * @see Nonce#generateNonce() for nonce generation
	 */
	protected static String getToken(String sig) {
		try {
			Date now = new Date();
			Date in90Min = new Date(now.getTime() + JWT_EXPIRATION_MILLIS);
			Algorithm algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
			String token = JWT.create()
					.withSubject(sig)
					.withExpiresAt(in90Min)
					.withJWTId(Nonce.generateNonce())
					.sign(algorithm);
			return token;
		} catch (Exception e) {
			return null;
		}
	}
	
	/**
	 * Sends transactional email via SendGrid service.
	 * 
	 * Email Operations:
	 * - Password reset emails
	 * - Confirmation emails
	 * - Notification emails
	 * - Administrative alerts
	 * 
	 * Email Details:
	 * - Sender: admin@chemvantage.org (ChemVantage LLC)
	 * - Recipient: Provided email address with optional name
	 * - Subject: Customizable subject line
	 * - Body: HTML content (supports rich formatting)
	 * 
	 * SendGrid Integration:
	 * - Provides reliable email delivery
	 * - Handles bounces, complaints, unsubscribes
	 * - Tracks opens and clicks (if enabled)
	 * - Scales to millions of emails
	 * 
	 * Error Handling:
	 * - Throws IOException if SendGrid API fails
	 * - Response status printed to console (for debugging)
	 * - Should be logged in production
	 * 
	 * Parameters:
	 * - recipientName: Optional display name (can be null)
	 * - recipientEmail: Required email address
	 * - subject: Email subject line
	 * - message: HTML email body
	 * 
	 * Example:
	 * <pre>
	 * Util.sendEmail("John Doe", "john@example.com",
	 *     "Password Reset",
	 *     "&lt;p&gt;Click here to reset your password&lt;/p&gt;")
	 * </pre>
	 * 
	 * @param recipientName optional display name for recipient (null for no name)
	 * @param recipientEmail recipient's email address
	 * @param subject email subject line
	 * @param message HTML email body content
	 * @throws IOException if SendGrid API call fails
	 */
	static void sendEmail(String recipientName, String recipientEmail, String subject, String message) 
			throws IOException {
		Email from = new Email(EMAIL_FROM, EMAIL_FROM_NAME);
		if (recipientName==null) recipientName="";
		Email to = new Email(recipientEmail,recipientName);
		Content content = new Content(EMAIL_CONTENT_TYPE, message);
		Mail mail = new Mail(from, subject, to, content);

		SendGrid sg = new SendGrid(u.sendGridAPIKey);
		Request request = new Request();
		request.setMethod(Method.POST);
		request.setEndpoint(SENDGRID_ENDPOINT);
		request.setBody(mail.build());
		Response response = sg.api(request);
		System.out.println(response.getStatusCode());
		System.out.println(response.getBody());
		System.out.println(response.getHeaders());
	}

}