package org.chemvantage.chem4ap;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;

/**
 * RSAKeyPair domain entity for storing cryptographic RSA key pairs.
 * 
 * Manages generation, storage, and retrieval of RSA-2048 public/private key pairs
 * used for cryptographic signing and verification in Chem4AP. Each key pair is
 * assigned a unique key identifier (kid) for reference and rotation.
 * 
 * Key Pair Generation:
 * - Algorithm: RSA with 2048-bit key size
 * - Generated on construction using SecureRandom from KeyPairGenerator
 * - Both private and public keys encoded in standard formats (PKCS8 and X509)
 * - Stored as byte arrays in Datastore for efficient serialization
 * 
 * Key Usage:
 * - Public key: Distributed to LMS platforms for verifying JWT signatures
 * - Private key: Used by ChemVantage backend to sign JWTs for LTI authentication
 * - Key ID: Referenced in JWT header (kid claim) for key identification
 * 
 * Encoding Formats:
 * - Private Key: PKCS8 (Public Key Cryptography Standards format)
 * - Public Key: X.509 SubjectPublicKeyInfo (X509EncodedKeySpec)
 * - Both formats are industry-standard and widely supported
 * 
 * Datastore Persistence:
 * - kid field is primary key (unique identifier)
 * - pub and pri fields store encoded key bytes
 * - Indexed for efficient lookups by kid
 * 
 * Error Handling:
 * - Constructor catches exceptions during key generation (silent failure)
 * - Key retrieval methods return null on decoding failures
 * - No exceptions thrown; errors result in null returns
 * 
 * JWT Integration:
 * - Public key endpoints expose this key for LMS verification
 * - Private key used to sign LTI Authorization Tokens
 * - Key rotation supported via new RSAKeyPair instance creation
 * 
 * @author ChemVantage
 * @version 1.0
 * @see LTIMessage for JWT signing usage
 * @see KeyFactory for RSA key handling
 */
@Entity
public class RSAKeyPair {
	// RSA algorithm and key generation constants
	/** RSA algorithm name for key generation and factory */
	private static final String ALGORITHM_RSA = "RSA";
	/** RSA key size in bits (2048-bit keys for current security standards) */
	private static final int KEY_SIZE_BITS = 2048;
	
	/**
	 * Unique key identifier for reference and rotation.
	 * Generated as UUID to ensure global uniqueness across all key pairs.
	 * Referenced in JWT header (kid claim) to identify which public key was used for signing.
	 */
	@Id String kid;
	
	/**
	 * Encoded public key in X.509 SubjectPublicKeyInfo format.
	 * Can be decoded back to RSAPublicKey for verification operations.
	 * Stored as byte array for efficient datastore serialization.
	 * Distributed to LMS platforms for JWT signature verification.
	 */
	byte[] pub;
	
	/**
	 * Encoded private key in PKCS8 format.
	 * Can be decoded back to RSAPrivateKey for signing operations.
	 * Stored as byte array for efficient datastore serialization.
	 * Never exposed externally; used only by backend for JWT signing.
	 */
	byte[] pri;
	
	/**
	 * Generates new RSA-2048 key pair and assigns unique key identifier.
	 * 
	 * Creates a new RSA-2048 key pair using SecureRandom from KeyPairGenerator.
	 * Both keys are immediately encoded to standard formats (PKCS8 for private,
	 * X509 for public) and stored as byte arrays. Key ID is generated as UUID.
	 * 
	 * Error Handling:
	 * - Any exception during generation is silently caught
	 * - Instance may be created with null fields if generation fails
	 * - Should validate fields before persisting to datastore
	 * 
	 * Performance:
	 * - Key generation typically takes 1-2 seconds
	 * - Should be called sparingly and results cached
	 * 
	 * @see KeyPairGenerator#getInstance(String)
	 * @see UUID#randomUUID()
	 */
	RSAKeyPair() {
		try {
			KeyPairGenerator keyGen = KeyPairGenerator.getInstance(ALGORITHM_RSA);
	        keyGen.initialize(KEY_SIZE_BITS);
	        KeyPair keyPair = keyGen.genKeyPair();
	        pri = keyPair.getPrivate().getEncoded();
	        pub = keyPair.getPublic().getEncoded();
	        kid = UUID.randomUUID().toString();
	    } catch (Exception e) {			
		}
	}
	
	/**
	 * Retrieves RSA public key by decoding from PKCS8-encoded byte array.
	 * 
	 * Converts the stored encoded public key bytes back to RSAPublicKey object
	 * that can be used for signature verification. Uses X509EncodedKeySpec for
	 * standard X.509 SubjectPublicKeyInfo format.
	 * 
	 * Error Handling:
	 * - Returns null if decoding fails (invalid format, corrupted data, etc.)
	 * - No exceptions thrown; errors result in null return
	 * 
	 * Usage:
	 * - Called when verifying JWT signatures from external parties
	 * - Called when exposing public key endpoint for LMS integration
	 * - Never called for signing (that uses private key)
	 * 
	 * @return RSAPublicKey object for signature verification, or null if decoding fails
	 * @see #getRSAPrivateKey()
	 * @see KeyFactory#generatePublic(java.security.spec.KeySpec)
	 */
	protected RSAPublicKey getRSAPublicKey() {
		try {
			return (RSAPublicKey) KeyFactory.getInstance(ALGORITHM_RSA).generatePublic(new X509EncodedKeySpec(pub));
		} catch (Exception e) {
			return null;
		}
	}
	
	/**
	 * Retrieves RSA private key by decoding from PKCS8-encoded byte array.
	 * 
	 * Converts the stored encoded private key bytes back to RSAPrivateKey object
	 * that can be used for signing operations (e.g., JWT token generation).
	 * Uses PKCS8EncodedKeySpec for standard PKCS#8 Private Key Information Syntax format.
	 * 
	 * Error Handling:
	 * - Returns null if decoding fails (invalid format, corrupted data, etc.)
	 * - No exceptions thrown; errors result in null return
	 * 
	 * Usage:
	 * - Called only by backend services for JWT signing
	 * - Never exposed to client applications
	 * - Used in LTI token generation during authentication
	 * 
	 * Security Considerations:
	 * - Return value should be carefully managed
	 * - Should not be logged or exposed externally
	 * - Only accessed by trusted backend code
	 * 
	 * @return RSAPrivateKey object for signing operations, or null if decoding fails
	 * @see #getRSAPublicKey()
	 * @see KeyFactory#generatePrivate(java.security.spec.KeySpec)
	 */
	protected RSAPrivateKey getRSAPrivateKey() {
		try {
			return (RSAPrivateKey) KeyFactory.getInstance(ALGORITHM_RSA).generatePrivate(new PKCS8EncodedKeySpec(pri));
		} catch (Exception e) {
			return null;
		}
	}

}
