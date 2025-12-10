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

@Entity
public class Util {
	@Id Long id = 1L;
	private double avgStars = 0.0;
	private int nStarReports = 0;
	private String HMAC256Secret = UUID.randomUUID().toString();
	private String reCaptchaSecret = "ChangeMe";
	private String reCaptchaSiteKey = "ChangeMe";
	private String salt = UUID.randomUUID().toString();
	private String announcement = "ChangeMe";
	private String sendGridAPIKey = "ChangeMe";
	private String openAIKey = "ChangeMe";
	private String gptModel = "ChangeMe";
	private String payPalClientId = "ChangeMe";
	private String payPalClientSecret = "ChangeMe";
	
	@Ignore static final String projectId = ServiceOptions.getDefaultProjectId();
	
	@Ignore static Util u;
	
	
	private Util() {}
	
	static String banner = "<div style='font-size:2em;font-weight:bold;color:#000080;'><img src='/images/chem4ap_atom.png' alt='Chem4AP Logo' style='vertical-align:middle;width:60px;'> Chem4AP</div><br/>";

	void addStarReport(int stars) {
		avgStars = (avgStars*nStarReports + stars)/(nStarReports+1);
		nStarReports++;
		ofy().save().entity(u);
	}

	static void createTask(String relativeUri, String query)
			throws IOException {
		// This method accepts a relativeUri (e.g., /report) to POST a request to a Chem4AP servlet
		String location = "us-central1";
		String queueName = "default";
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
							.putHeaders("Content-Type", "application/x-www-form-urlencoded")
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
	
	static double getAvgStars() {
		DecimalFormat df2 = new DecimalFormat("#.#");
		return Double.valueOf(df2.format(u.avgStars));
	}
	
	static String hashId(String userId) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
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
				//+ "  <!-- Google tag (gtag.js) --> "
				//+ "  <script async src=\"https://www.googletagmanager.com/gtag/js?id=AW-942360432\"></script> "
				//+ "  <script> window.dataLayer = window.dataLayer || []; function gtag(){dataLayer.push(arguments);} gtag('js', new Date()); gtag('config', 'AW-942360432'); </script>"
				+ "</head>\n"
				+ "<body style='margin: 20px; font-family: Poppins'>\n";
	}
	
	static String foot() {
		return  "<footer><p><hr style='width:600px;margin-left:0' />"
				+ "<a style='text-decoration:none;color:#000080;font-weight:bold' href=/index.html>"
				+ "Chem4AP</a> | "
				+ "<a href=/terms_and_conditions.html>Terms and Conditions of Use</a> | "
				+ "<a href=/privacy_policy.html>Privacy Policy</a> | "
				+ "<a href=/copyright.html>Copyright</a></footer>\n"
				+ "</body></html>";
	}

	static String getHMAC256Secret() throws Exception { 
		refresh();
		return u.HMAC256Secret; 
	}

	static String getPayPalClientId() {
		refresh();
		return u.payPalClientId;
	}
	
	static String getPayPalClientSecret() {
		refresh();
		return u.payPalClientSecret;
	}
	
	static String getReCaptchaSecret() {
		refresh();
		return u.reCaptchaSecret;
	}

	static String getReCaptchaSiteKey() {
		refresh();
		return u.reCaptchaSiteKey;
	}

	static String getSalt() { 
		refresh();
		return u.salt; 
	}

	static String getAnnouncement() { 
		refresh();
		return u.announcement; 
	}

	static String getSendGridKey() {
		refresh();
		return u.sendGridAPIKey;
	}
	
	static String getOpenAIKey() {
		refresh();
		return u.openAIKey;
	}
	
	static String getGPTModel() {
		refresh();
		return u.gptModel;
	}
	
	static String getServerUrl() {
		if (projectId.equals("chem4ap")) return "https://www.chem4ap.com";
		else if (projectId.equals("dev-chem4ap")) return "https://dev-chem4ap.appspot.com";
		return null;
	}
	
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

	static void refresh() {
		try {  // retrieve values from datastore when a new software version is installed
			if (u == null) u = ofy().load().type(Util.class).id(1L).safe();
		} catch (Exception e) { // this will run only once when project is initiated
			u = new Util();
			ofy().save().entity(u).now();
		}
	}

	protected static String getToken(String sig) {
		try {
			Date now = new Date();
			Date in90Min = new Date(now.getTime() + 5400000L);
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
	
	static void sendEmail(String recipientName, String recipientEmail, String subject, String message) 
			throws IOException {
		Email from = new Email("admin@chemvantage.org","ChemVantage LLC");
		if (recipientName==null) recipientName="";
		Email to = new Email(recipientEmail,recipientName);
		Content content = new Content("text/html", message);
		Mail mail = new Mail(from, subject, to, content);

		SendGrid sg = new SendGrid(u.sendGridAPIKey);
		Request request = new Request();
		request.setMethod(Method.POST);
		request.setEndpoint("mail/send");
		request.setBody(mail.build());
		Response response = sg.api(request);
		System.out.println(response.getStatusCode());
		System.out.println(response.getBody());
		System.out.println(response.getHeaders());
	}

}