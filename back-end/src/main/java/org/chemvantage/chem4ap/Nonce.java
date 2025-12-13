/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2012 ChemVantage LLC
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

import java.util.Date;
import java.util.List;
import java.util.Random;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

/**
 * Nonce (Number Used Once) represents a unique, one-time use value for OAuth security.
 * 
 * This class manages nonce values used in OAuth 1.0 and LTI authentication flows to protect
 * against eavesdropping and replay attacks. Each nonce is stored in the Datastore with a 
 * creation timestamp and is automatically expired after 90 minutes.
 * 
 * Usage:
 * - generateNonce(): Creates a new unique nonce string
 * - isUnique(String): Validates that a nonce hasn't been used before, stores it if valid
 * 
 * Security: Nonces older than the validity interval are automatically purged from the datastore
 * to prevent database growth and ensure strict replay attack protection.
 */
@Entity
public class Nonce {
	@Id String id;
	@Index	Date created;
	
	/** Nonce validity interval: 90 minutes in milliseconds */
	static final long NONCE_VALIDITY_INTERVAL = 5400000L;  // 90 minutes (90 * 60 * 1000)
	
	Nonce() {}
	
	/** Constructs a new Nonce with the specified id and current timestamp */
	Nonce(String id) {
		this.id = id;
		this.created = new Date();
	}
	
	/**
	 * Generates a unique nonce string using two random long values converted to hexadecimal.
	 * The resulting nonce is a 32-character hex string (2 * 16 hex chars per long).
	 * @return a unique nonce string suitable for OAuth security operations
	 */
	static String generateNonce() {
		Random random = new Random(new Date().getTime());
		long firstRandomValue = random.nextLong();
		long secondRandomValue = random.nextLong();
		String firstHash = Long.toHexString(firstRandomValue);
		String secondHash = Long.toHexString(secondRandomValue);
		return firstHash + secondHash;
	}

	/**
	 * Verifies that a nonce value is unique and hasn't been used before.
	 * 
	 * If the nonce is valid and unique, it is stored in the datastore for future validation.
	 * This method also automatically deletes all nonces older than the validity interval
	 * to prevent database growth and ensure strict replay attack protection.
	 * 
	 * @param nonce the nonce string to validate for uniqueness
	 * @return true if the nonce is unique and has been stored, false if already used
	 * @throws IllegalArgumentException if nonce is null or empty
	 */
	public static boolean isUnique(String nonce) {
		if (nonce == null || nonce.isEmpty()) throw new IllegalArgumentException("Nonce value is required and cannot be empty.");
		
		Date now = new Date();
		Date oldest = new Date(now.getTime() - NONCE_VALIDITY_INTERVAL);  // 90 minutes ago

		// delete all Nonce objects older than the validity interval
		List<Key<Nonce>> expired = ofy().load().type(Nonce.class).filter("created <", oldest).keys().list();
		if (expired.size() > 0) ofy().delete().keys(expired);
		
		// check to see if a Nonce with the specified id already exists in the database
		if (ofy().load().type(Nonce.class).id(nonce).now() != null) return false;  // nonce already used
		
		// store a new Nonce object in the datastore with the unique nonce string
		ofy().save().entity(new Nonce(nonce)).now();
		
		return true;
	}
/*	
	public static boolean isUnique(String nonce, String timestamp) {
		// This method provides a level of security for OAuth launches for LTI
		// by verifying that oauth_nonce strings are submitted only once
		// This protects against eavesdropping and copycat login attacks
		
		Date now = new Date();
		Date oldest = new Date(now.getTime()-interval); // converts seconds to millis

		try {
			//check the timestamp to ensure this is a new launch (within half of the interval)
			Date stamped = new Date(Long.parseLong(timestamp)*1000);  // millis since Jan 1, 1970 00:00 UTC
			if (Math.abs(stamped.getTime()-now.getTime()) > interval/2) throw new Exception();  // out of submission interval
			
			// delete all Nonce objects older than the interval
			List<Key<Nonce>> expired = ofy().load().type(Nonce.class).filter("created <",oldest).keys().list();
			if (expired.size() > 0) ofy().delete().keys(expired);
			
			// check to see if a Nonce with the specified id already exists in the database
			if (ofy().load().type(Nonce.class).id(nonce).now() != null) throw new Exception(); // if nonce exists
			
			// store a new Nonce object in the datastore with the unique nonce string
			ofy().save().entity(new Nonce(nonce)).now();
			
			return true;
		} catch (Exception e) {
			return false;
		}
	}
*/
}