package org.chemvantage.chem4ap;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreOptions;
import com.googlecode.objectify.ObjectifyFactory;
import com.googlecode.objectify.ObjectifyService;

/**
 * Servlet context listener that initializes the Objectify ORM service at application startup.
 * 
 * This listener implements ServletContextListener to handle application lifecycle events.
 * When the servlet context is initialized, this listener:
 * 1. Creates a connection to Google Cloud Datastore
 * 2. Initializes ObjectifyService with an ObjectifyFactory
 * 3. Registers all entity classes that will be persisted to the datastore
 * 
 * Entity Registration: The following entity classes are registered for Objectify ORM:
 * - APChemTopic, APChemUnit: Chemistry curriculum structure
 * - Assignment, Question: Learning content entities
 * - Deployment, Nonce: OAuth/LTI support entities
 * - User, PremiumUser, UserReport: User management entities
 * - Score: Student performance tracking
 * - RSAKeyPair: Security/cryptography
 * - Voucher: Discount/promotion codes
 * - Util: Shared configuration entity
 * 
 * Datastore Configuration: By default, uses the primary datastore. To connect to a
 * specific database (e.g., backup datastore), uncomment and modify the DatastoreOptions
 * line with the appropriate database ID.
 * 
 * @see ServletContextListener
 * @see ObjectifyService
 * @see com.google.cloud.datastore.Datastore
 */
@WebListener
public class ObjectifyWebListener implements ServletContextListener {

	/**
	 * Initializes Objectify ORM service when the servlet context starts.
	 * 
	 * This method is called when the web application starts. It performs the following:
	 * 1. Creates a Google Cloud Datastore connection
	 * 2. Initializes ObjectifyService with a new ObjectifyFactory
	 * 3. Registers all entity classes for ORM mapping
	 * 
	 * The datastore can be configured to connect to a specific database instance by
	 * uncommenting the commented line and providing the desired database ID.
	 * 
	 * @param event the ServletContextEvent containing the context being initialized
	 */
	@Override
	public void contextInitialized(ServletContextEvent event) {
		// To connect to a backup or non-default datastore, uncomment and modify the next line:
		// final Datastore datastore = DatastoreOptions.newBuilder().setDatabaseId("backup1").build().getService();
		
		final Datastore datastore = DatastoreOptions.newBuilder().build().getService();
		ObjectifyService.init(new ObjectifyFactory(datastore));

		// Register all entity classes for Objectify ORM mapping
		// Curriculum and learning content entities
		ObjectifyService.register(APChemTopic.class);
		ObjectifyService.register(APChemUnit.class);
		ObjectifyService.register(Assignment.class);
		
		// Platform and security entities
		ObjectifyService.register(Deployment.class);
		ObjectifyService.register(Nonce.class);
		ObjectifyService.register(RSAKeyPair.class);
		
		// User and access control entities
		ObjectifyService.register(PremiumUser.class);
		ObjectifyService.register(User.class);
		ObjectifyService.register(UserReport.class);

		// Question and assessment entities
		ObjectifyService.register(Question.class);
		ObjectifyService.register(Score.class);

		// Commerce and configuration entities
		ObjectifyService.register(PayPalOrder.class);
		ObjectifyService.register(Voucher.class);
		ObjectifyService.register(Util.class);
	}

	/**
	 * Called when the servlet context is destroyed (application shutdown).
	 * 
	 * Currently performs no cleanup operations as Objectify and Google Cloud Datastore
	 * handle resource cleanup automatically when the application terminates.
	 * This method is implemented to satisfy the ServletContextListener interface contract.
	 * 
	 * @param event the ServletContextEvent containing the context being destroyed
	 */
	@Override
	public void contextDestroyed(ServletContextEvent event) {
		// No explicit cleanup needed - Objectify and Datastore resources are managed automatically
	}
}