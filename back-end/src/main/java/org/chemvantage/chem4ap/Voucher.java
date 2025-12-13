package org.chemvantage.chem4ap;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.util.Date;
import java.util.Random;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

/**
 * Voucher represents a promotional or gift code for premium subscription purchases.
 * 
 * Vouchers are created with a specified subscription duration and price, and can be
 * redeemed by users to activate premium access. Each voucher has a unique 6-character
 * hexadecimal code.
 * 
 * Fields:
 * - code: Unique 6-character hexadecimal identifier
 * - purchased: Date the voucher was created
 * - activated: Date the voucher was redeemed (null if not yet activated)
 * - months: Number of months of subscription provided by this voucher
 * - paid: Price paid for the voucher in cents
 * - org: Organization that created/owns the voucher
 * 
 * A voucher can only be activated once. Attempting to activate an already-activated
 * voucher returns false.
 * 
 * @see PremiumUser for subscription details after voucher redemption
 */
@Entity
public class Voucher {
	// Voucher code generation constants
	private static final int HEX_OFFSET = 1048576;  // 0x100000 - offset for 6-digit hex codes
	private static final int HEX_RANGE = 15728640;  // 0xF00000 - max offset for random generation
	
	@Id 	String 	code;
	@Index	Date purchased;
	@Index	Date activated;
			int months;
			int paid;
	@Index	String org;
	
	/** Default constructor for Objectify ORM deserialization. */
	public Voucher() {}
	
	/**
	 * Creates a new Voucher with the specified parameters.
	 * 
	 * Generates a unique 6-character hexadecimal code and sets the purchase date.
	 * The voucher is not activated until {@link #activate()} is called.
	 * 
	 * @param org organization name that created this voucher
	 * @param price voucher price in cents
	 * @param nMonths number of months of subscription this voucher provides
	 */
	public Voucher(String org, int price, int nMonths) {
		this.code = Integer.toHexString(HEX_OFFSET + new Random().nextInt(HEX_RANGE)).toUpperCase(); // 6-character HEX
		this.purchased = new Date();
		this.months = nMonths;
		this.paid = price;
		this.org = org;
	}
	
	/**
	 * Activates the voucher if it hasn't been activated yet.
	 * 
	 * Sets the activation date and persists the change to the datastore.
	 * Once activated, a voucher cannot be reactivated.
	 * 
	 * @return true if voucher was successfully activated, false if already activated
	 */
	public boolean activate() {
		if (this.activated == null) {
			this.activated = new Date();
			ofy().save().entity(this);
			return true;
		} else return false;
	}
}
