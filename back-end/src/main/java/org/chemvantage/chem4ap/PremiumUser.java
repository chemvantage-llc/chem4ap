package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.util.Calendar;
import java.util.Date;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

/**
 * PremiumUser domain entity for managing premium subscription records.
 * 
 * Represents a user with an active or past premium subscription to Chem4AP.
 * Stores subscription period information (start and expiration dates) and payment tracking.
 * Premium subscriptions grant access to all Chem4AP units and features.
 * 
 * Entity Relationships:
 * - Links to User entity via hashedId (user identifier)
 * - Created by Checkout servlet when subscription is purchased (via PayPal or voucher)
 * - Created by Voucher entity when voucher code is redeemed
 * - Queried by User.getUser() and other services to determine premium status
 * 
 * Index Strategy:
 * - exp (expiration date): Indexed to efficiently query for active or expired subscriptions
 * - start (start date): Indexed for subscription history queries and analytics
 * - paid (payment amount): Indexed for payment tracking and financial reports
 * - hashedId: Primary key, implicitly indexed for direct lookups
 * 
 * Data Model:
 * - Subscription term is calculated in months and converted to calendar date
 * - Expiration date determines subscription validity (User.isPremium() checks this)
 * - Payment information stored for auditing and compliance
 * - Organization field tracks institution for multi-tenant deployments
 * - Order ID links to external payment processor records for reconciliation
 * 
 * Usage Examples:
 * - Creating: new PremiumUser(hashedId, 12, 1600, "School District", "paypal-order-123")
 * - Querying: ofy().load().type(PremiumUser.class).id(hashedId).now()
 * - Checking active: PremiumUser.exp.after(new Date())
 * 
 * @author ChemVantage
 * @version 2.0
 * @see User#isPremium()
 * @see Checkout
 * @see Voucher
 * @see PremiumUser#PremiumUser(String, int, int, String, String)
 */
@Entity
class PremiumUser {
	// Subscription validation constants
	/** Calendar field for month calculation */
	private static final int CALENDAR_FIELD_MONTH = Calendar.MONTH;
	
	// Error message constants
	/** Error message for missing or invalid user ID */
	private static final String ERROR_INVALID_ID = "User ID (hashedId) is required and cannot be empty.";
	/** Error message for invalid subscription duration */
	private static final String ERROR_INVALID_MONTHS = "Subscription duration (months) must be a positive number. Received: ";
	
	/**
	 * Default constructor for Objectify ORM deserialization.
	 * Required by Objectify persistence framework for entity instantiation.
	 */
	PremiumUser() {}
	
	/**
	 * User identifier (hashed for security purposes).
	 * Unique identifier linking this subscription to a specific user.
	 * Maps to User.hashedId for relational integrity.
	 */
	@Id String hashedId;
	
	/**
	 * Premium subscription expiration date (indexed).
	 * Subscription is active if expiration date is in the future.
	 * Queries for expired subscriptions use this index for efficient filtering.
	 * @see User#isPremium()
	 */
	@Index Date exp;
	
	/**
	 * Premium subscription start date (indexed).
	 * Tracks when the subscription was activated.
	 * Used for subscription history queries and analytics reporting.
	 */
	@Index Date start;
	
	/**
	 * Amount paid for subscription in cents (indexed).
	 * Tracks payment amount for auditing and financial reconciliation.
	 * Must be non-negative; typically calculated from subscription duration and pricing.
	 */
	@Index int paid;
	
	/**
	 * Organization or institution name associated with the premium user.
	 * Identifies the school, institution, or organization that purchased the subscription.
	 * Used for multi-tenant deployments and organization-specific reporting.
	 * Can be null for individual user subscriptions.
	 */
	String org;
	
	/**
	 * External payment processor order identifier.
	 * Stores the order/transaction ID from PayPal or other payment providers.
	 * Used for payment reconciliation and dispute resolution.
	 * Example: "PAYPAL-ORDER-123ABC" or "VOUCHER-CODE-XYZ"
	 */
	String order_id;
	
	/**
	 * Creates a new PremiumUser with subscription details and persists to datastore.
	 * 
	 * Validates inputs and immediately saves the subscription record to the datastore.
	 * Calculates expiration date by adding the specified number of months to current date
	 * using Calendar arithmetic to properly handle month boundaries and leap years.
	 * 
	 * Subscription Validation:
	 * - Requires non-empty hashedId (user identifier)
	 * - Requires positive months value (minimum 1 month)
	 * - Throws IllegalArgumentException for invalid inputs
	 * 
	 * Example Usage:
	 * <code>
	 * // 12-month subscription for $16 (1600 cents) via PayPal
	 * PremiumUser premium = new PremiumUser(
	 *     user.getHashedId(),      // hashedId
	 *     12,                       // months
	 *     1600,                     // paid in cents
	 *     "Lincoln High School",    // organization
	 *     "PAYPAL-ORDER-ABC123"     // PayPal order ID
	 * );
	 * // Entity is automatically saved to datastore
	 * </code>
	 * 
	 * @param id the user's hashed identifier (required, must be non-empty)
	 * @param months number of months for the subscription duration (must be >= 1)
	 * @param paid amount paid for subscription in cents (typically 0 for voucher redemption)
	 * @param org organization or institution name (can be null for individual users)
	 * @param order_id external payment processor order identifier (can be null)
	 * @throws IllegalArgumentException if id is null/empty or months is not positive
	 * @see User#hashedId
	 * @see Calendar#add(int, int)
	 */
	public PremiumUser(String id, int months, int paid, String org, String order_id) {
		if (id == null || id.isEmpty()) throw new IllegalArgumentException(ERROR_INVALID_ID);
		if (months <= 0) throw new IllegalArgumentException(ERROR_INVALID_MONTHS + months);
		
		hashedId = id;
		start = new Date();
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(start);
		calendar.add(CALENDAR_FIELD_MONTH, months);
		exp = calendar.getTime();
		this.paid = paid;
		this.org = org;
		this.order_id = order_id;
		ofy().save().entity(this).now();
	}
}
