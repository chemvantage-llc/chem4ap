package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.util.Calendar;
import java.util.Date;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

/**
 * PremiumUser represents a user with an active premium subscription.
 * 
 * This entity stores premium subscription information for users, including subscription
 * period (start and expiration dates) and payment tracking. Premium status determines
 * what features and content are available to the user.
 * 
 * Field Visibility:
 * - hashedId: User identifier (hashed for security)
 * - exp: Subscription expiration date (indexed for querying expired subscriptions)
 * - start: Subscription start date (indexed for range queries)
 * - paid: Amount paid in cents (indexed for payment tracking)
 * - org: Organization or institution name
 * - order_id: External payment processor order identifier (e.g., PayPal)
 * 
 * Usage: Create a PremiumUser when a user subscribes, update exp date for renewals,
 * and query by exp date to find active premium users.
 */
@Entity
class PremiumUser {
	/** User identifier (hashed for security purposes) */
	@Id String hashedId;
	
	/** Premium subscription expiration date (indexed for querying expired subscriptions) */
	@Index Date exp;
	
	/** Premium subscription start date (indexed for range queries) */
	@Index Date start;
	
	/** Amount paid for subscription in cents (indexed for payment tracking) */
	@Index int paid;
	
	/** Organization or institution name associated with the premium user */
	String org;
	
	/** External payment processor order identifier (e.g., PayPal order number) */
	String order_id;
	
	/** Default constructor for Objectify ORM deserialization. */
	public PremiumUser() {}
	
	/**
	 * Creates a new PremiumUser with subscription details and persists to datastore.
	 * 
	 * Calculates the expiration date by adding the specified number of months to the
	 * current date. The new PremiumUser entity is immediately saved to the datastore.
	 * 
	 * @param id the user's hashed identifier (required, non-empty)
	 * @param months number of months for the premium subscription duration (must be positive)
	 * @param paid amount paid for subscription in cents (must be non-negative)
	 * @param org organization or institution name (can be null or empty)
	 * @param order_id external payment processor order identifier (can be null or empty)
	 * @throws IllegalArgumentException if id is null or empty, or months is not positive
	 */
	public PremiumUser(String id, int months, int paid, String org, String order_id) {
		if (id == null || id.isEmpty()) throw new IllegalArgumentException("User ID (hashedId) is required and cannot be empty.");
		if (months <= 0) throw new IllegalArgumentException("Subscription duration (months) must be a positive number. Received: " + months);
		
		hashedId = id;
		start = new Date();
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(start);
		calendar.add(Calendar.MONTH, months);
		exp = calendar.getTime();
		this.paid = paid;
		this.org = org;
		this.order_id = order_id;
		ofy().save().entity(this).now();
	}
}
