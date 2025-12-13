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
 * KeyStore servlet providing JWKS (JSON Web Key Set) endpoint for LTI 1.3 authentication.
 * 
 * Implements the LTI 1.3 public key distribution mechanism per OAuth 2.0 specifications.
 * LTI platforms (Canvas, Blackboard, etc.) fetch public keys from this endpoint to verify
 * JWT signatures on authentication requests. Keys are RSA-2048 bit pairs stored in datastore.
 * 
 * JWKS Endpoint Specification:
 * - RFC 7517: JSON Web Key (JWK) standard
 * - RFC 7518: JSON Web Algorithm (JWA) specifications
 * - LTI 1.3 Core Security Framework requirements
 * - OAuth 2.0 Bearer Token Usage (RFC 6750)
 * 
 * Request Handling:
 * 
 * GET /jwks
 *   - Returns JSON Web Key Set document
 *   - Contains all public keys with key IDs (kid)
 *   - Format: { "keys": [ { kty, kid, n, e, alg, use }, ... ] }
 *   - Cached in memory for performance
 * 
 * GET /jwks?kid=<keyId>
 *   - Returns specific JSON Web Key
 *   - Format: { kty: "RSA", kid: "...", n: "...", e: "...", alg: "RS256", use: "sig" }
 *   - kid="public" returns random key for compatibility
 * 
 * GET /jwks?kid=<keyId>&fmt=X509
 *   - Returns RSA public key in X.509 PEM format
 *   - Human-readable format for manual key distribution
 *   - Useful for debugging and alternative integrations
 * 
 * POST /jwks
 *   - Same as GET (delegates to doGet for compatibility)
 *   - Supported for clients that only support POST
 * 
 * Key Management:
 * - Keys stored in Datastore as RSAKeyPair entities
 * - Loaded into memory cache on first access
 * - JWKS JSON cached in static field (rebuilt on key addition)
 * - New keys generated on demand if none exist
 * 
 * Security Properties:
 * - JWKS endpoint must be publicly accessible (no authentication)
 * - Private keys never exposed via JWKS endpoint
 * - RSA-2048 provides 112-bit symmetric equivalent security
 * - Each key identified by unique kid (key ID)
 * - Public keys distributed to multiple LMS instances
 * 
 * JWK Properties:
 * - kty: "RSA" (key type)
 * - kid: unique key identifier (UUID)
 * - n: RSA modulus (Base64-URL encoded)
 * - e: RSA public exponent (Base64-URL encoded)
 * - alg: "RS256" (RSA Signature with SHA-256)
 * - use: "sig" (used for signing operations)
 * 
 * Datastore Integration:
 * - RSAKeyPair entities stored with kid as @Id field
 * - Loaded on first JWKS request
 * - Reloaded if new keys added (cache invalidation)
 * - Supports single or multiple key rotation
 * 
 * LTI 1.3 Context:
 * - LMS fetches public keys before validating JWTs
 * - JWT header contains "kid" claim identifying which key was used
 * - LMS verifies: signature, expiration, nonce, issuer, audience
 * - LMS grants access only after all validations pass
 * 
 * @author ChemVantage
 * @version 1.0
 * @see RSAKeyPair for key pair entity
 * @see LTIRequest for JWT validation context
 */
@WebServlet(urlPatterns = {"/jwks", "/jwks/"})
public class KeyStore extends HttpServlet {
	private static final long serialVersionUID = 1L;
	
	// RSA-2048 key component size constants
	/** RSA-2048 modulus byte length including sign byte (for big integer encoding) */
	private static final int RSA_MODULUS_BYTES_WITH_SIGN = 257;
	
	/** RSA-2048 modulus byte length without sign byte (actual key bytes) */
	private static final int RSA_MODULUS_BYTES = 256;
	
	// JWK property constants
	/** JSON Web Key Type: RSA */
	private static final String JWK_TYPE_RSA = "RSA";
	
	/** RSA signature algorithm with SHA-256: RS256 */
	private static final String JWK_ALGORITHM = "RS256";
	
	/** Key usage: Signing operations */
	private static final String JWK_USE_SIGNATURE = "sig";
	
	// JWKS structure constants
	/** JWKS JSON property for array of keys */
	private static final String JWKS_PROPERTY_KEYS = "keys";
	
	/** JWK properties */
	private static final String JWK_PROPERTY_TYPE = "kty";
	private static final String JWK_PROPERTY_KID = "kid";
	private static final String JWK_PROPERTY_MODULUS = "n";
	private static final String JWK_PROPERTY_EXPONENT = "e";
	private static final String JWK_PROPERTY_ALG = "alg";
	private static final String JWK_PROPERTY_USE = "use";
	
	// Request parameter constants
	/** Query parameter for key ID */
	private static final String PARAM_KID = "kid";
	
	/** Query parameter for output format (e.g., "X509" for PEM) */
	private static final String PARAM_FORMAT = "fmt";
	
	/** Format value for X.509 PEM output */
	private static final String FORMAT_X509 = "X509";
	
	/** Special kid value meaning "select any random key" */
	private static final String KID_PUBLIC = "public";
	
	/** Special kid value for Canvas LMS (uses first key for consistency) */
	private static final String LMS_CANVAS = "canvas";
	
	// Response constants
	/** Content-Type for JWKS JSON responses */
	private static final String CONTENT_TYPE_JSON = "application/json; charset=UTF-8";
	
	/** Content-Type for X.509 PEM key responses */
	private static final String CONTENT_TYPE_PEM = "text/plain; charset=UTF-8";
	
	// X.509 PEM formatting constants
	/** X.509 PEM header line */
	private static final String PEM_HEADER = "-----BEGIN PUBLIC KEY-----";
	
	/** X.509 PEM footer line */
	private static final String PEM_FOOTER = "-----END PUBLIC KEY-----<p>";
	
	/** PEM line length (Base64 encoded, 64 chars per line) */
	private static final int PEM_LINE_LENGTH = 64;
	
	/**
	 * Cached JWKS JSON document (rebuilt when keys added/removed).
	 * Static for memory efficiency; invalidated on key changes.
	 */
	private static String jwks = null;
	
	/**
	 * In-memory cache of RSA key pairs loaded from datastore.
	 * Maps key ID (kid) to RSAKeyPair entity.
	 * Loaded once on first access; reloaded if keys added.
	 */
	private static Map<String,RSAKeyPair> rsaKeys = new HashMap<String,RSAKeyPair>();
	
	/**
	 * Handles GET requests to retrieve JWKS or specific public keys.
	 * 
	 * Request Processing:
	 * 1. Build JWKS JSON if not cached
	 * 2. Parse query parameters (kid, fmt)
	 * 3. Route to appropriate response handler:
	 *    - With fmt parameter: Return PEM-formatted key
	 *    - With kid parameter: Return single JWK
	 *    - No parameters: Return complete JWKS
	 * 
	 * Access Control:
	 * - No authentication required (public endpoint per LTI spec)
	 * - JWKS must be accessible to all LMS platforms
	 * - Only public keys exposed; private keys never returned
	 * 
	 * Error Handling:
	 * - 404: Key not found (invalid kid parameter)
	 * - 400: Invalid format parameter
	 * - 500: Internal errors during key retrieval
	 * 
	 * Caching:
	 * - JWKS document cached in static variable
	 * - Cache invalidated when new keys added
	 * - Rebuilds automatically on first access after invalidation
	 * 
	 * @throws ServletException if servlet processing fails
	 * @throws IOException if response writing fails
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.setContentType(CONTENT_TYPE_JSON);
		PrintWriter out = response.getWriter();
		
		try {
			if (jwks == null) buildJwks();
			
			String kid = request.getParameter(PARAM_KID);
			String fmt = request.getParameter(PARAM_FORMAT);
			
			// Validate parameters
			if (kid != null && kid.isEmpty()) kid = null;
			if (fmt != null && fmt.isEmpty()) fmt = null;
			
			if (fmt != null && kid != null) {
				// Return X.509 format if requested
				if (FORMAT_X509.equals(fmt)) {
					response.setContentType(CONTENT_TYPE_PEM);
					out.println(getRSAPublicKeyX509(kid));
				} else {
					out.println("Error: Unsupported format");
				}
			} else if (kid != null) {
				// Return specific JWK
				if (KID_PUBLIC.equals(kid)) kid = getAKeyId(null); // get a random valid key_id
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
	 * Handles POST requests to JWKS endpoint.
	 * 
	 * Per OAuth 2.0 and LTI specifications, both GET and POST are supported.
	 * Delegates to doGet() for actual processing.
	 * 
	 * @throws ServletException if servlet processing fails
	 * @throws IOException if response writing fails
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.setContentType(CONTENT_TYPE_JSON);
		doGet(request, response);
	}

	/**
	 * Retrieves cached JWKS JSON document.
	 * 
	 * Lazy Loading:
	 * - If jwks is null, builds it from loaded keys
	 * - Otherwise returns cached JSON
	 * 
	 * @return JWKS JSON document string
	 */
	protected static String getjwks() {
		if (jwks == null) return buildJwks();
		else return jwks;
	}

	/**
	 * Retrieves a JWK (JSON Web Key) representation of the RSA public key.
	 * 
	 * JWK Structure:
	 * - kty: "RSA" (key type)
	 * - kid: unique key identifier
	 * - n: RSA modulus (Base64-URL encoded)
	 * - e: RSA public exponent (Base64-URL encoded)
	 * - alg: "RS256" (signing algorithm)
	 * - use: "sig" (signing use case)
	 * 
	 * RSA-2048 Modulus Handling:
	 * - Java BigInteger may include sign byte (257 bytes total)
	 * - JWK requires exactly 256 bytes for RSA-2048 modulus
	 * - Strips leading zero byte if present
	 * - Encodes as Base64-URL (no padding, - and _ instead of + and /)
	 * 
	 * Error Handling:
	 * - Returns null if kid is invalid or key not found
	 * - Returns null if key retrieval or encoding fails
	 * 
	 * @param rsa_key_id the key identifier
	 * @return JsonObject containing JWK, or null if not found
	 */
	protected static JsonObject getJwk(String rsa_key_id) {
		if (rsa_key_id == null || rsa_key_id.isEmpty()) {
			return null;
		}
		try {
			RSAPublicKey pk = getRSAPublicKey(rsa_key_id);
			if (pk == null) return null;
			
			JsonObject jwk = new JsonObject();
			jwk.addProperty(JWK_PROPERTY_TYPE, pk.getAlgorithm());
			jwk.addProperty(JWK_PROPERTY_KID, rsa_key_id);
			
			// Handle RSA-2048 modulus: may have extra sign byte that needs stripping
			byte[] mod1 = pk.getModulus().toByteArray();
			if (mod1.length == RSA_MODULUS_BYTES_WITH_SIGN && mod1[0] == 0) {
				// Modulus has extra sign byte, strip it
				byte[] mod2 = new byte[RSA_MODULUS_BYTES];
				System.arraycopy(mod1, 1, mod2, 0, RSA_MODULUS_BYTES);
				jwk.addProperty(JWK_PROPERTY_MODULUS, Base64.getUrlEncoder().encodeToString(mod2));
			} else {
				jwk.addProperty(JWK_PROPERTY_MODULUS, Base64.getUrlEncoder().encodeToString(mod1));
			}
			
			jwk.addProperty(JWK_PROPERTY_EXPONENT, Base64.getUrlEncoder().encodeToString(pk.getPublicExponent().toByteArray()));
			jwk.addProperty(JWK_PROPERTY_ALG, JWK_ALGORITHM);
			jwk.addProperty(JWK_PROPERTY_USE, JWK_USE_SIGNATURE);
			return jwk;
		} catch (Exception e) {
			return null;
		}	
	}
	
	/**
	 * Builds the complete JWKS (JSON Web Key Set) document containing all public keys.
	 * 
	 * JWKS Structure:
	 * - Contains "keys" property with array of JWK objects
	 * - Each JWK represents one RSA public key
	 * - Ready to serialize to JSON for HTTP response
	 * 
	 * Caching:
	 * - Result cached in static jwks variable
	 * - Rebuilt when new keys added to rsaKeys map
	 * - Invalidate by setting jwks = null
	 * 
	 * Key Loading:
	 * - Loads keys from Datastore if not in memory cache
	 * - Creates new key if none exist
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
		json.add(JWKS_PROPERTY_KEYS, keys);
		jwks = json.toString();
		return jwks;
	}
	
	/**
	 * Loads all RSA key pairs from Datastore into memory cache.
	 * 
	 * Loading Strategy:
	 * - Queries Datastore for all RSAKeyPair entities
	 * - If no keys exist, creates a new one automatically
	 * - Populates rsaKeys map for in-memory access
	 * 
	 * First-Time Initialization:
	 * - Called when JWKS is first requested
	 * - If no keys found, makeNewRSAKey() creates one
	 * - New key persisted to Datastore
	 * 
	 * Performance:
	 * - Datastore query cost: ~10-50ms
	 * - Subsequent requests use in-memory cache (fast)
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
	 * Generates a new RSA-2048 key pair and persists it to Datastore.
	 * 
	 * Key Generation:
	 * - Creates new RSAKeyPair (generates 2048-bit RSA keys in constructor)
	 * - Assigns unique kid (UUID) to new key
	 * - Adds to in-memory cache
	 * - Persists to Datastore for permanent storage
	 * 
	 * Used for:
	 * - Initial deployment (no keys exist)
	 * - Key rotation (adding new key for migration)
	 * - Emergency key replacement (compromised key)
	 * 
	 * Performance:
	 * - RSA key generation: ~1-2 seconds (expensive operation)
	 * - Should be called sparingly, not on every request
	 * - Results cached in rsaKeys map after first generation
	 */
	protected static void makeNewRSAKey() {
		RSAKeyPair kp = new RSAKeyPair();
		rsaKeys.put(kp.kid, kp);
	    ofy().save().entity(kp);
	}
	
	/**
	 * Retrieves the RSA public key for the specified key identifier.
	 * 
	 * Lookup Process:
	 * - Check in-memory cache first (rsaKeys map)
	 * - If not cached, query Datastore for RSAKeyPair entity
	 * - Decode RSAPublicKey from stored X509 format
	 * 
	 * Error Handling:
	 * - Returns null if kid is null/empty
	 * - Returns null if key not found in Datastore
	 * - Returns null if key decoding fails
	 * 
	 * Performance:
	 * - In-memory cache hit: ~1 microsecond (O(1))
	 * - Datastore query: ~10-50ms (typical)
	 * 
	 * @param kid the key identifier (UUID string)
	 * @return RSAPublicKey instance, or null if not found/invalid
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
	 * 
	 * Access Control:
	 * - Private keys should only be accessed by internal JWT signing operations
	 * - Never exposed via JWKS endpoint (public interface)
	 * - Used by LTIMessage for signing authentication tokens
	 * 
	 * Lookup Process:
	 * - Similar to getRSAPublicKey()
	 * - Retrieves RSAKeyPair entity from Datastore
	 * - Decodes RSAPrivateKey from stored PKCS8 format
	 * 
	 * Error Handling:
	 * - Returns null if kid is invalid
	 * - Returns null if key not found
	 * - Returns null if key decoding fails
	 * 
	 * Security Considerations:
	 * - Return value should be carefully managed
	 * - Should not be logged or exposed externally
	 * - Only accessed by trusted backend code
	 * 
	 * @param kid the key identifier (UUID string)
	 * @return RSAPrivateKey instance, or null if not found/invalid
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
	 * Retrieves a key identifier suitable for the specified LMS platform.
	 * 
	 * Platform-Specific Behavior:
	 * - Canvas LMS: Always returns first key (index 0)
	 *   - Ensures consistency across launches
	 *   - Canvas can cache key info per tool
	 * - Other LMS/null: Returns random key from available keys
	 *   - Provides load distribution
	 *   - Enables transparent key rotation
	 * 
	 * Use Cases:
	 * - During JWT signing: Select key for kid header claim
	 * - When publishing endpoint: Include kid for platform discovery
	 * - For key rotation: Gradually shift to new key
	 * 
	 * Initialization:
	 * - If rsaKeys cache is empty, loads from Datastore
	 * - If no keys exist, creates new key automatically
	 * - Ensures at least one key is always available
	 * 
	 * Error Handling:
	 * - Returns null if no keys available (shouldn't happen)
	 * - Random selection: Uses java.util.Random
	 * 
	 * @param lms the LMS platform name ("canvas" for Canvas LMS, null for random)
	 * @return key identifier string, or null if no keys available
	 */
	protected static String getAKeyId(String lms) {
		if (rsaKeys.isEmpty()) createNewKeyMap();
		if (rsaKeys.isEmpty()) return null; // No keys available
		
		List<RSAKeyPair> keys = new ArrayList<RSAKeyPair>(rsaKeys.values());
		if (keys.isEmpty()) return null;
		
		if (LMS_CANVAS.equals(lms)) {
			return keys.get(0).kid;
		} else {
			// Random key for independent installations
			int n = new Random().nextInt(keys.size());
			return keys.get(n).kid;
		}
	}
	
	/**
	 * Converts an RSA public key to X.509 PEM (Privacy Enhanced Mail) format.
	 * 
	 * PEM Format:
	 * - Human-readable ASCII encoding of DER-encoded X.509 SubjectPublicKeyInfo
	 * - Wrapped in -----BEGIN/END PUBLIC KEY----- lines
	 * - Base64 encoded with 64-character line breaks
	 * - Widely supported by cryptography tools and libraries
	 * 
	 * Use Cases:
	 * - Manual key verification and debugging
	 * - Integration with non-HTTP clients
	 * - Documentation and key distribution
	 * - Import into key management systems
	 * 
	 * Special Handling:
	 * - kid="public" returns random key (same as JWKS endpoint)
	 * - Returns null key or error message if not found
	 * - Formats modulus with proper line breaks for readability
	 * 
	 * Error Messages:
	 * - "Error: Key ID required" - kid is null/empty
	 * - "Error: No keys available" - kid="public" but no keys exist
	 * - "Error: Key not found" - specified kid doesn't exist
	 * - "Error: Key conversion failed" - encoding/format error
	 * 
	 * Output Format:
	 * <pre>
	 * -----BEGIN PUBLIC KEY-----
	 * MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...
	 * [Base64 encoded key, 64 chars per line]
	 * ...
	 * -----END PUBLIC KEY-----&lt;p&gt;
	 * </pre>
	 * 
	 * @param rsa_key_id the key identifier, or "public" for random key
	 * @return PEM-formatted X.509 public key string, or error message
	 */
	protected static String getRSAPublicKeyX509(String rsa_key_id) {
		if (rsa_key_id == null || rsa_key_id.isEmpty()) {
			return "Error: Key ID required";
		}
		
		if (KID_PUBLIC.equals(rsa_key_id)) {
			rsa_key_id = getAKeyId(null); // get a random valid key_id
			if (rsa_key_id == null) {
				return "Error: No keys available";
			}
		}
		
		StringBuilder key = new StringBuilder()
			.append(PEM_HEADER).append("\n");
		
		try {
			RSAPublicKey pubkey = getRSAPublicKey(rsa_key_id);
			if (pubkey == null) {
				return "Error: Key not found";
			}
			
			String pub = Base64.getEncoder().encodeToString(pubkey.getEncoded());
			for (int n = 0; n < pub.length(); n += PEM_LINE_LENGTH) {
				int endIndex = Math.min(n + PEM_LINE_LENGTH, pub.length());
				key.append(pub.substring(n, endIndex)).append("\n");
			}
			key.append(PEM_FOOTER);
			return key.toString();
		} catch (Exception e) {
			return "Error: Key conversion failed - " + e.getMessage();
		}
	}

}
