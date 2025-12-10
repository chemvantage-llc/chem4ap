package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet(urlPatterns = {"/lti"})
public class LTIRequest extends HttpServlet {

	private static final long serialVersionUID = 137L;
	
	@Override
	public void doPost(HttpServletRequest request, HttpServletResponse response) 
			throws IOException {
		// This servlet receives and processes valid LtiLauchRequests and LtiDeepLinkingRequests
		
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		
		try {
			String id_token = request.getParameter("id_token");
			if (id_token==null) throw new Exception("id_token was missing");  
			
			String iss = "https://" + request.getServerName();
			JsonObject claims = null;
			try {
				claims = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(JWT.decode(id_token).getPayload()))).getAsJsonObject();
			} catch (Exception e) {
				throw new Exception("id_token was not a valid JWT.");
			}
			Deployment d = validateClaims(claims);
			validateTokenSignature(id_token,d.well_known_jwks_url);			
			
			User user = null;
			String sig = request.getParameter("sig");
			if (sig != null) {  // instructor returning with chosen assignment
				user = User.getUser(sig);
				if (user==null) throw new Exception("Invalid or expired User entity.");
			} else {  // new DeepLinking request
				String state = request.getParameter("state");
				if (state==null) throw new Exception("state parameter was missing"); 
				validateStateToken(iss, state); // ensures proper OIDC authorization flow completed							
				user = validateUserClaims(claims);
				//user.setToken();
			}
			
			// request is for ResourceLink or DeepLinking?
			JsonElement message_type = claims.get("https://purl.imsglobal.org/spec/lti/claim/message_type");
			
			switch (message_type.getAsString()) {
			case "LtiDeepLinkingRequest":
				if (!user.isInstructor()) throw new Exception("You must be logged as an instructor for this.");
				String userRequest = request.getParameter("UserRequest");
				if (userRequest==null) userRequest = "";
				switch (userRequest) {
				case "Select assignment":
					JsonElement deep_linking_settings = claims.get("https://purl.imsglobal.org/spec/lti-dl/claim/deep_linking_settings");
					if (deep_linking_settings == null) throw new Exception("Required settings claim was not found.");
					String assignmentType = request.getParameter("AssignmentType");
					Long unitId = Long.parseLong(request.getParameter("UnitId"));
					out.println(deepLinkingResponseMessage(user,claims,iss,assignmentType,unitId));
					break;
				default:
					user.setToken();
					out.println(contentPickerForm(user,request,claims));
				}
				break;
			case "LtiResourceLinkRequest":
				Assignment a = getMyAssignment(user,claims,d,request);
				if (a==null) throw new Exception("Assignnent is null");
				user.setAssignment(a.id);
				if (user.isInstructor()) {
					out.println(Exercises.instructorPage(user,a));
				} else {
					response.sendRedirect(Util.getServerUrl() + "/exercises/index.html?t=" + Util.getToken(user.getTokenSignature()));
				}
				break;
			default: throw new Exception("The LTI message_type claim " + message_type.getAsString() + " is not supported.");
			}						
		} catch (Exception e) {
			out.println("Chem4AP Launch Failed: " + e.getMessage()==null?e.toString():e.getMessage());
			//response.sendError(401, "Chem4AP Launch Failed: " + e.getMessage()==null?e.toString():e.getMessage());
		}
	}
	
	static String contentPickerForm(User user, HttpServletRequest request,JsonObject claims) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head("Chem4AP Assignment Setup"));
		try {
		JsonObject settings = claims.get("https://purl.imsglobal.org/spec/lti-dl/claim/deep_linking_settings").getAsJsonObject();
		boolean acceptsLtiResourceLink = settings.get("accept_types").getAsJsonArray().contains(new JsonPrimitive("ltiResourceLink"));
		if (!acceptsLtiResourceLink) throw new Exception("Deep Link request failed because platform does not accept new LtiResourceLinks.");
		
		buf.append(Util.banner);

		buf.append("<form name=AssignmentForm action=/lti method=POST>");
		buf.append("<input type=hidden name=id_token value='" + request.getParameter("id_token") + "' />");
		buf.append("<input type=hidden name=sig value='" + user.getTokenSignature() + "' />");
		buf.append("<input type=hidden name=UserRequest value='Select assignment' />");
		
		String assignmentType = request.getParameter("AssignmentType");
		if (assignmentType==null) assignmentType="";
		
		buf.append("<table><tr><td width=25%>"
				+ "<label><input type=radio name=AssignmentType required value='Exercises' checked /><b>Exercises</b></label><br/>"
				+ "<label><input type=radio name=AssignmentType required value='Homework (coming soon)' /><b>Homework</b></label>"
				+ "</td><td>"
				+ "<span id=exDescription>This is an adaptive, formative assignment that will help your students to prepare "
				+ "for the AP Chemistry exam. Select one of the AP Chemistry units below. When you launch the assignment, you "
				+ "will be able to customize the individual topics covered in the assignment."
				+ "</span>"
				+ "</td></tr></table>");
		List<APChemUnit> units = null;
		units = ofy().load().type(APChemUnit.class).order("unitNumber").list();
		buf.append("<br/><select name=UnitId required><option value=''>Select a unit</option>");
		for (APChemUnit u : units) {
			buf.append("<option value=" + u.id + ">" + u.title + "</option>");
		}
		buf.append("</select>");
		buf.append("<input type=submit value='Create this assignment' />"
				+ "</form>");

		buf.append(Util.foot());
		} catch (Exception e) {
			buf.append(e.toString() + " " + e.getMessage());
		}
		return buf.toString();
	}
	
	private String deepLinkingResponseMessage(User user, JsonObject claims, String iss, String assignmentType, Long unitId) throws Exception {
		StringBuffer buf = new StringBuffer();
		StringBuffer debug = new StringBuffer();
		String deep_link_return_url = null;
		try {
			String platform_id = claims.get("iss").getAsString();
			String deployment_id = claims.get("https://purl.imsglobal.org/spec/lti/claim/deployment_id").getAsString();
			JsonObject settings = claims.get("https://purl.imsglobal.org/spec/lti-dl/claim/deep_linking_settings").getAsJsonObject();		
			deep_link_return_url = settings.get("deep_link_return_url").getAsString();
			String data = settings.get("data")==null?"":settings.get("data").getAsString();
			
			Date now = new Date();
			Date exp = new Date(now.getTime() + 5400000L); // 90 minutes from now
			Deployment d = Deployment.getInstance(platform_id + "/" + deployment_id);
			APChemUnit unit = ofy().load().type(APChemUnit.class).id(unitId).safe();
			String title = assignmentType + " - " + unit.title;
			
			Assignment a = new Assignment(assignmentType,title,unitId,d.platform_deployment_id);
			ofy().save().entity(a).now();
			
			String launchUrl = iss + "/lti";
			String iconUrl = iss + (iss.lastIndexOf("/")==iss.length()?"":"/") + "images/chem4ap_atom.png";
			String client_id = d.client_id;
			String subject = claims.get("sub").getAsString();
			String nonce = Nonce.generateNonce();
			
			Encoder enc = Base64.getUrlEncoder().withoutPadding();
			
			// Create a JSON head for the JWT to send as DeepLinkingResponse
			JsonObject head = new JsonObject();
			head.addProperty("typ", "JWT");
			head.addProperty("alg", "RS256");
			head.addProperty("kid", d.rsa_key_id);
			byte[] hdr = enc.encode(head.toString().getBytes("UTF-8"));
			
			// Create a JSON payload for the JWT to send as DeepLinkingResponse:
			JsonObject payload = new JsonObject();
			payload.addProperty("iss",client_id);
			payload.addProperty("sub",subject);
			payload.addProperty("aud",platform_id);
			payload.addProperty("nonce", nonce);
			payload.addProperty("exp", exp.getTime()/1000);
			payload.addProperty("iat", now.getTime()/1000);
			payload.addProperty("https://purl.imsglobal.org/spec/lti/claim/message_type", "LtiDeepLinkingResponse");
			payload.addProperty("https://purl.imsglobal.org/spec/lti/claim/version", "1.3.0");
			payload.addProperty("https://purl.imsglobal.org/spec/lti/claim/deployment_id", deployment_id);
			if (!data.isEmpty()) payload.addProperty("https://purl.imsglobal.org/spec/lti-dl/claim/data", data);
	
			//Add the user-selected assignments to the payload as an array of content_items:
			JsonArray content_items = new JsonArray();
	
			JsonObject item = new JsonObject();
			item.addProperty("type", "ltiResourceLink");
	
			int maxScore = 10;
			item.addProperty("title", title);
	
			JsonObject lineitem = new JsonObject();
			lineitem.addProperty("scoreMaximum", maxScore);
			lineitem.addProperty("label", title);
			lineitem.addProperty("resourceId",String.valueOf(a.id));
	
			JsonObject submissionReview = new JsonObject();
			submissionReview.add("reviewableStatus", new JsonArray());
			lineitem.add("submissionReview", submissionReview);
	
			item.add("lineItem", lineitem);
	
			// adding the assignmentId as a custom parameter should work for every LMS but is required for canvas
			JsonObject custom = new JsonObject();
			custom.addProperty("resourceId",String.valueOf(a.id));
			custom.addProperty("resource_link_id_history","$ResourceLink.id.history");
			item.add("custom", custom);
	
			if (d.lms_type.equals("canvas")) launchUrl += "?resourceId=" + a.id;
			item.addProperty("url", launchUrl);
	
			JsonObject icon = new JsonObject();
			icon.addProperty("url", iconUrl);
			icon.addProperty("width", 75);
			icon.addProperty("height", 70);
			item.add("icon", icon);
	
			content_items.add(item);
	
			payload.add("https://purl.imsglobal.org/spec/lti-dl/claim/content_items", content_items);
			
			byte[] pld = enc.encode(payload.toString().getBytes("UTF-8"));
			
			// Join the head and payload together with a period separator:
			String jwt = String.format("%s.%s",new String(hdr),new String(pld));
			
			// Add a signature item to complete the JWT:
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(KeyStore.getRSAPrivateKey(d.rsa_key_id),new SecureRandom());
			signature.update(jwt.getBytes("UTF-8"));
			String sig = new String(enc.encode(signature.sign()));
			jwt = String.format("%s.%s", jwt, sig);
			
			// Create a form to be auto-submitted to the platform by the user_agent browser
			buf.append("<form id=selections method=POST action='" + deep_link_return_url + "'>"
					+ Util.banner + "<h1>Success!</h1>"
					+ "<input type=hidden name=JWT value='" + jwt + "' />"
					+ "<input type=submit value='Continue' />"
					+ "</form>"
					+ "<script>"
					+ "document.getElementById('selections').submit();"
					+ "</script>");
		} catch (Exception e) {
			buf.append(e.getMessage()==null?e.toString():e.getMessage() + "Debug: " + debug.toString());
		}
		return buf.toString();
	}

	private Assignment getMyAssignment(User user,JsonObject claims,Deployment d,HttpServletRequest request) throws Exception {
		try {
			Assignment myAssignment = null;
	
			// Process information for LTI Assignment and Grade Services (AGS)
			String scope = "";
			String lti_ags_lineitem_url = null;
			String lti_ags_lineitems_url = null;
			try {  
				JsonObject lti_ags_claims = claims.get("https://purl.imsglobal.org/spec/lti-ags/claim/endpoint").getAsJsonObject();
	
				// get the list of AGS capabilities allowed by the platform
				JsonArray scope_claims = lti_ags_claims.get("scope")==null?new JsonArray():lti_ags_claims.get("scope").getAsJsonArray();
				Iterator<JsonElement> scopes_iterator = scope_claims.iterator();
				while (scopes_iterator.hasNext()) scope += scopes_iterator.next().getAsString() + (scopes_iterator.hasNext()?" ":"");
				lti_ags_lineitems_url = (lti_ags_claims.get("lineitems")==null?null:lti_ags_claims.get("lineitems").getAsString());
				lti_ags_lineitem_url = (lti_ags_claims.get("lineitem")==null?null:lti_ags_claims.get("lineitem").getAsString());
			} catch (Exception e) {				
			}
	
			// Process information for LTI Advantage Names and Roles Provisioning (NRPS)
			String lti_nrps_context_memberships_url = null;
			try { 
				JsonObject lti_nrps_claims = claims.get("https://purl.imsglobal.org/spec/lti-nrps/claim/namesroleservice").getAsJsonObject();
				if (lti_nrps_claims != null) scope += (scope.length()>0?" ":"") + "https://purl.imsglobal.org/spec/lti-nrps/scope/contextmembership.readonly";
				lti_nrps_context_memberships_url = lti_nrps_claims.get("context_memberships_url").getAsString();
			} catch (Exception e) {
			}
	
			String resourceId = null;  // this is a String representation of the assignmentId that is set during the DeepLinking flow
			JsonObject lineitem = null;  // this is the platform's full description of a grade book column for the assignment
			String resourceLinkId = null;  // this is a required element that identifies a unique resource link in the platform
	
			try {
				resourceLinkId = claims.get("https://purl.imsglobal.org/spec/lti/claim/resource_link").getAsJsonObject().get("id").getAsString();
			} catch (Exception e) {
				throw new Exception("Missing or badly formed resource link ID");
			}	
	
			if (lti_ags_lineitem_url != null) {  // this is the default, most common way of retrieving the Assignment
				myAssignment = ofy().load().type(Assignment.class).filter("lti_ags_lineitem_url",lti_ags_lineitem_url).first().now();
			}
	
			if (myAssignment==null) {  // this may be the first launch of a deeplinking item; check for the resourceId in custom parameters, launch parameters or lineitem
				try {
					JsonObject custom = claims.get("https://purl.imsglobal.org/spec/lti/claim/custom").getAsJsonObject();
					resourceId = custom.get("resourceId")==null?custom.get("resourceid").getAsString():custom.get("resourceId").getAsString();  // schoology changes "resopurceId" to lowercase 
				} catch (Exception e2) {}
				
				if (resourceId != null) {  // try to get the lineitem from the platform					
					try {
						switch (d.lms_type) {
						case "canvas": // older canvas assignments may have this in the launch URL query
							resourceId = request.getParameter("resourceId");
							break;
						default:  // all other lms platforms may have this in the lineitem from DeepLinking
							if (lineitem==null) lineitem = LTIMessage.getLineItem(d, lti_ags_lineitem_url);
							resourceId = lineitem.get("resourceId").getAsString();					
						}
					} catch (Exception e) {}					
				}
				
				if (resourceId != null) {  // found an ancestor Assignment but could be parent, grandparent, etc
					try {
						myAssignment = ofy().load().type(Assignment.class).id(Long.parseLong(resourceId)).safe();
						if (myAssignment.lti_ags_lineitem_url != null) {  // current launch is for a copy (descendant) assignment
							myAssignment.id = ofy().factory().allocateId(Assignment.class).getId();  // forces a new copy Assignment entity to be saved
							myAssignment.created = new Date();  // with a new created Date
						}
					} catch (Exception e) {}
				}
			}
	
			if (myAssignment == null) {  // retrieve the assignment by its resourceLinkId value - this will be the case for ungraded assignments
				myAssignment = ofy().load().type(Assignment.class).filter("domain",d.platform_deployment_id).filter("resourceLinkId",resourceLinkId).first().now();
			}
	
			// After all that, if the assignment still cannot be found, create a new (incomplete) one. This will be updated after the ResourcePicker
			if (myAssignment == null) {
				myAssignment = new Assignment();
				myAssignment.platform_deployment_id = d.platform_deployment_id;
				myAssignment.resourceLinkId = resourceLinkId;
				myAssignment.lti_nrps_context_memberships_url = lti_nrps_context_memberships_url;
			}
	
			// Update the Assignment parameters:
			try {
				Assignment original_a = myAssignment.clone(); // make a copy to compare with for updating later
				myAssignment.resourceLinkId = resourceLinkId;		
				if (lti_ags_lineitems_url != null) myAssignment.lti_ags_lineitems_url = lti_ags_lineitems_url;
				if (lti_ags_lineitem_url != null) myAssignment.lti_ags_lineitem_url = lti_ags_lineitem_url;
				if (lti_nrps_context_memberships_url != null) myAssignment.lti_nrps_context_memberships_url = lti_nrps_context_memberships_url;
				myAssignment.valid = new Date();
	
				// If required, save the updated Assignment entity now so its id will be accessible
				if (myAssignment.id==null || !myAssignment.equivalentTo(original_a)) {
					ofy().save().entity(myAssignment).now();
				}
			} catch (Exception e) {
				throw new Exception("Assignment could not be updated during LTI launch sequence. " + e.getMessage()==null?e.toString():e.getMessage());
			}
	
			return myAssignment;
		} catch (Exception e) {
			throw new Exception("Failed to get assignment: " + e.getMessage()==null?e.toString():e.getMessage());
		}
	}

	private Deployment validateClaims(JsonObject claims) throws Exception {
		try {
			// get the platform_id and deployment_id to load the correct Deployment d
			String platform_id = claims.get("iss").getAsString();
			if (platform_id.endsWith("/")) platform_id = platform_id.substring(0,platform_id.length()-1);
	
			// verify LTI version 1.3.0
			JsonElement lti_version = claims.get("https://purl.imsglobal.org/spec/lti/claim/version");
			if (lti_version == null) throw new Exception("LTI version claim was missing.");    
			if (!"1.3.0".equals(lti_version.getAsString())) throw new Exception("Incorrect LTI version claim");
	
			JsonElement userId_claim = claims.get("sub");
			if (userId_claim == null) throw new Exception("The id_token was missing required subject claim");
			
			JsonElement deployment_id = claims.get("https://purl.imsglobal.org/spec/lti/claim/deployment_id");
			if (deployment_id == null) throw new Exception("The deployment_id claim was not found in the id_token payload.");
			String platformDeploymentId = platform_id + "/" + deployment_id.getAsString();
	
			Deployment d = Deployment.getInstance(platformDeploymentId);
	
			if (d==null) throw new Exception("The deployment was not found in the Chem4AP database. You "
					+ "can register your LMS with us at https://www.chem4ap.com/lti/registration");
			if (d.expires != null && d.expires.before(new Date())) d.status = "blocked";
			if ("blocked".equals(d.status)) throw new Exception("Sorry, we were unable to launch Chem4AP from this "
					+ "account. Please contact admin@chemvantage.org for assistance to reactivate the account. Thank you.");
			if (d.status == null) d.status = "pending";
	
			// validate the id_token audience:
			JsonElement aud = claims.get("aud");
			if (aud.isJsonArray()) {
				JsonArray audience = aud.getAsJsonArray();
				for (JsonElement a : audience) {
					if (a.getAsString().equals(d.client_id)) return d;  // audience is OK
				}
			} else {  // aud is a String
				if (aud.getAsString().equals(d.client_id)) return d;  // audience is OK)
			}
			throw new Exception("The id_token client_id claim is not authorized in Chem4AP.");
		} catch (Exception e) {
			throw new Exception("ID token could not be validated: " + e.getMessage()==null?e.toString():e.getMessage());
		}
	}

	private void validateStateToken(String iss,String state) throws Exception {
		/* This method ensures that the state token required by LTI v1.3 standards is a
		 * valid token issued by the tool provider (Chem4AP) as part of the LTI
		 * launch request sequence. Otherwise throws a JWTVerificationException.
		 */

		Algorithm algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
		JWTVerifier verifier = JWT.require(algorithm).withIssuer(iss).build();
		verifier.verify(state);
	    String nonce = JWT.decode(state).getClaim("nonce").asString();
	    if (!Nonce.isUnique(nonce)) throw new Exception("Nonce was used previously.");
	    
	    // verify the correct redirect_uri
	    JsonObject state_json = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(JWT.decode(state).getPayload()))).getAsJsonObject();
	    if (!state_json.get("redirect_uri").getAsString().contains(iss)) throw new Exception("Invalid redirect_uri.");
	}

	private void validateTokenSignature(String id_token,String url) throws Exception {
		// validate the id_token signature:
		try {
			// retrieve the public Java Web Key from the platform to verify the signature
			if (url==null) throw new Exception("The deployment does not have a valid JWKS URL.");
			URL jwks_url = new URL(url);
			JwkProvider provider = new UrlJwkProvider(jwks_url);
			DecodedJWT decoded_token = JWT.decode(id_token);
			String key_id = decoded_token.getKeyId();
			if (key_id == null || key_id.isEmpty()) throw new Exception("No JWK id found.");
			Jwk jwk = provider.get(key_id); //throws Exception when not found or can't get one
			RSAPublicKey public_key = (RSAPublicKey)jwk.getPublicKey();
			// verify the JWT signature
			Algorithm algorithm = Algorithm.RSA256(public_key,null);
			if (!"RS256".contentEquals(decoded_token.getAlgorithm())) throw new Exception("JWT algorithm must be RS256");
			JWT.require(algorithm).build().verify(decoded_token);  // throws JWTVerificationException if not valid
		} catch (Exception e) {
			throw new Exception("Failed to validate id_token signature: " + e.getMessage()==null?e.toString():e.getMessage());
		}
	}

	private User validateUserClaims(JsonObject claims) throws Exception {
		// Process User information:
		try {
			String userId = null;
			try {
				userId = claims.get("sub").getAsString();
			} catch (Exception e) {
				userId = "";  // allows for anonymous user
			}
			User user = new User(claims.get("iss").getAsString(), userId);

			JsonElement roles_claim = claims.get("https://purl.imsglobal.org/spec/lti/claim/roles");
			if (roles_claim == null || !roles_claim.isJsonArray()) throw new Exception("Required roles claim is missing from the id_token");
			JsonArray roles = roles_claim.getAsJsonArray();
			Iterator<JsonElement> roles_iterator = roles.iterator();
			while(roles_iterator.hasNext()){
				String role = roles_iterator.next().getAsString().toLowerCase();
				user.setIsTeachingAssistant(role.contains("teachingassistant"));
				user.setIsInstructor(role.contains("instructor"));
				user.setIsAdministrator(role.contains("administrator"));
			}
			return user;
		} catch (Exception e) {
			throw new Exception("User claims could not be validated: " + e.getMessage()==null?e.toString():e.getMessage());
		}
	}
}