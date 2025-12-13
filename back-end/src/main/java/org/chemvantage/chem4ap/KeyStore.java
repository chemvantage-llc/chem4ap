package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * KeyStore servlet providing JWKS (JSON Web Key Set) endpoint for LTI 1.3 integration.
 * Generates, stores, and serves RSA public keys used for JWT signature verification.
 * Routes:
 * GET /jwks - Returns JWKS JSON document
 * GET /jwks?kid=<keyId> - Returns specific JWK
 * GET /jwks?kid=<keyId>&fmt=X509 - Returns RSA public key in X.509 PEM format
 * POST /jwks - Same as GET (for compatibility)
 */
@WebServlet(urlPatterns = {"/jwks", "/jwks/"})
public class KeyStore extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final int RSA_MODULUS_BYTES_WITH_SIGN = 257;  // RSA-2048 modulus + sign byte
	private static final int RSA_MODULUS_BYTES = 256;             // RSA-2048 modulus without sign byte
      
	private static String jwks = null;
	private static Map<String,RSAKeyPair> rsaKeys = new HashMap<String,RSAKeyPair>();
	
	/**
	 * Handles GET requests to retrieve JWKS or specific public keys.
	 * No authentication required (JWKS endpoint must be publicly accessible per LTI spec).
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.setContentType("application/json; charset=UTF-8");
		PrintWriter out = response.getWriter();
		
		try {
			if (jwks == null) buildJwks();
			
			String kid = request.getParameter("kid");
			String fmt = request.getParameter("fmt");
			
			// Validate parameters
			if (kid != null && kid.isEmpty()) kid = null;
			if (fmt != null && fmt.isEmpty()) fmt = null;
			
			if (fmt != null && kid != null) {
				// Return X.509 format if requested
				if ("X509".equals(fmt)) {
					response.setContentType("text/plain; charset=UTF-8");
					out.println(getRSAPublicKeyX509(kid));
				} else {
					out.println("Error: Unsupported format");
				}
			} else if (kid != null) {
				// Return specific JWK
				if ("public".equals(kid)) kid = getAKeyId(null); // get a random valid key_id
				JsonObject jwk = getJwk(kid);
				if (jwk == null) {
					response.sendError(404, "Key not found: " + kid);
				} else {
					out.println(jwk.toString());
				}
			} else {
				// Return full JWKS
				out.println(jwks);
			}
		} catch (Exception e) {
			response.sendError(500, "Error retrieving keys: " + e.getMessage());
		}
	}

	/**
	 * Handles POST requests (delegates to doGet for compatibility).
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.setContentType("application/json; charset=UTF-8");
		doGet(request, response);
	}

	protected static String getjwks() {
		if (jwks == null) return buildJwks();
		else return jwks;
	}

	/**
	 * Retrieves a JWK (JSON Web Key) representation of the RSA public key.
	 * Handles RSA-2048 modulus encoding with proper sign byte stripping.
	 *
	 * @param rsa_key_id the key identifier
	 * @return JsonObject containing kty, kid, n, e, alg, use properties, or null if key not found
	 */
	protected static JsonObject getJwk(String rsa_key_id) {
		if (rsa_key_id == null || rsa_key_id.isEmpty()) {
			return null;
		}
		try {
			RSAPublicKey pk = getRSAPublicKey(rsa_key_id);
			if (pk == null) return null;
			
			JsonObject jwk = new JsonObject();
			jwk.addProperty("kty", pk.getAlgorithm());
			jwk.addProperty("kid", rsa_key_id);
			
			// Handle RSA-2048 modulus: may have extra sign byte that needs stripping
			byte[] mod1 = pk.getModulus().toByteArray();
			if (mod1.length == RSA_MODULUS_BYTES_WITH_SIGN && mod1[0] == 0) {
				// Modulus has extra sign byte, strip it
				byte[] mod2 = new byte[RSA_MODULUS_BYTES];
				System.arraycopy(mod1, 1, mod2, 0, RSA_MODULUS_BYTES);
				jwk.addProperty("n", Base64.getUrlEncoder().encodeToString(mod2));
			} else {
				jwk.addProperty("n", Base64.getUrlEncoder().encodeToString(mod1));
			}
			
			jwk.addProperty("e", Base64.getUrlEncoder().encodeToString(pk.getPublicExponent().toByteArray()));
			jwk.addProperty("alg", "RS256");
			jwk.addProperty("use", "sig");
			return jwk;
		} catch (Exception e) {
			return null;
		}	
	}
	
	/**
	 * Builds the complete JWKS (JSON Web Key Set) document containing all public keys.
	 * Caches result in static variable for subsequent requests.
	 *
	 * @return JSON string representing the JWKS document
	 */
	protected static String buildJwks() {
		if (rsaKeys.isEmpty()) createNewKeyMap();
		
		JsonArray keys = new JsonArray();
		for (String kid : rsaKeys.keySet()) {
			JsonObject jwk = getJwk(kid);
			if (jwk != null) keys.add(jwk);
		}
		JsonObject json = new JsonObject();
		json.add("keys", keys);
		jwks = json.toString();
		return jwks;
	}
	
	/**
	 * Loads all RSA key pairs from datastore into memory cache.
	 * If no keys exist, creates a new one.
	 */
	protected static void createNewKeyMap() {
		List<RSAKeyPair> keypairs = ofy().load().type(RSAKeyPair.class).list();
		if (keypairs == null || keypairs.isEmpty()) {
			makeNewRSAKey();
		} else {
			for (RSAKeyPair kp: keypairs) {
				if (kp != null && kp.kid != null) {
					rsaKeys.put(kp.kid, kp);
				}
			}
		}
	}
	
	/**
	 * Generates a new RSA-2048 key pair and persists it to the datastore.
	 */
	protected static void makeNewRSAKey() {
		RSAKeyPair kp = new RSAKeyPair();
		rsaKeys.put(kp.kid, kp);
	    ofy().save().entity(kp);
	}
	
	/**
	 * Retrieves the RSA public key for the specified key identifier.
	 *
	 * @param kid the key identifier
	 * @return RSAPublicKey instance, or null if not found
	 */
	protected static RSAPublicKey getRSAPublicKey(String kid) {
		if (kid == null || kid.isEmpty()) {
			return null;
		}
		try {
			RSAKeyPair kp = ofy().load().type(RSAKeyPair.class).id(kid).safe();
			if (kp == null) return null;
			return kp.getRSAPublicKey();
		} catch (Exception e) {
			return null;
		}
	}
	
	/**
	 * Retrieves the RSA private key for the specified key identifier.
	 * Private keys should only be accessed by internal JWT signing operations.
	 *
	 * @param kid the key identifier
	 * @return RSAPrivateKey instance, or null if not found
	 */
	protected static RSAPrivateKey getRSAPrivateKey(String kid) {
		if (kid == null || kid.isEmpty()) {
			return null;
		}
		try {
			RSAKeyPair kp = ofy().load().type(RSAKeyPair.class).id(kid).safe();
			if (kp == null) return null;
			return kp.getRSAPrivateKey();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Retrieves a key identifier for the specified LMS platform.
	 * Canvas and other shared LMS platforms get the same key for consistency.
	 * Independent installations get a random key from available keys.
	 *
	 * @param lms the LMS platform name (e.g., "canvas"), or null for random
	 * @return key identifier, or null if no keys available
	 */
	protected static String getAKeyId(String lms) {
		if (rsaKeys.isEmpty()) createNewKeyMap();
		if (rsaKeys.isEmpty()) return null; // No keys available
		
		List<RSAKeyPair> keys = new ArrayList<RSAKeyPair>(rsaKeys.values());
		if (keys.isEmpty()) return null;
		
		if ("canvas".equals(lms)) {
			return keys.get(0).kid;
		} else {
			// Random key for independent installations
			int n = new Random().nextInt(keys.size());
			return keys.get(n).kid;
		}
	}
	
	/**
	 * Converts an RSA public key to X.509 PEM (Privacy Enhanced Mail) format.
	 * This format is human-readable and can be easily shared and imported.
	 *
	 * @param rsa_key_id the key identifier (or "public" for random key)
	 * @return PEM-formatted X.509 public key string, or error message if not found
	 */
	protected static String getRSAPublicKeyX509(String rsa_key_id) {
		if (rsa_key_id == null || rsa_key_id.isEmpty()) {
			return "Error: Key ID required";
		}
		
		if ("public".equals(rsa_key_id)) {
			rsa_key_id = getAKeyId(null); // get a random valid key_id
			if (rsa_key_id == null) {
				return "Error: No keys available";
			}
		}
		
		StringBuilder key = new StringBuilder()
			.append("-----BEGIN PUBLIC KEY-----\n");
		
		try {
			RSAPublicKey pubkey = getRSAPublicKey(rsa_key_id);
			if (pubkey == null) {
				return "Error: Key not found";
			}
			
			String pub = Base64.getEncoder().encodeToString(pubkey.getEncoded());
			for (int n = 0; n < pub.length(); n += 64) {
				int endIndex = Math.min(n + 64, pub.length());
				key.append(pub.substring(n, endIndex)).append("\n");
			}
			key.append("-----END PUBLIC KEY-----<p>");
			return key.toString();
		} catch (Exception e) {
			return "Error: Key conversion failed - " + e.getMessage();
		}
	}

}
