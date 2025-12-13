package org.chemvantage.chem4ap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.web.servlet.ServletComponentScan;

/**
 * Spring Boot application entry point and configuration for Chem4AP web application.
 * 
 * Serves as the main Bootstrap class for launching the Spring Boot embedded Tomcat server
 * and configuring the application context for the Chem4AP LMS integration platform.
 * 
 * Application Architecture:
 * - Embedded Tomcat servlet container (Spring Boot default)
 * - Annotation-based servlet discovery and registration
 * - LTI 1.3 / LTI Advantage protocol support
 * - Google Cloud Datastore integration via Objectify ORM
 * - RESTful API and servlet-based HTTP handlers
 * 
 * Annotations and Configuration:
 * 
 * @ServletComponentScan:
 * - Enables auto-discovery of servlet classes annotated with @WebServlet
 * - Registers found servlets directly with embedded Tomcat
 * - Allows individual servlet classes to define their own URL mappings
 * - Alternative to centralized servlet registration in web.xml
 * 
 * @SpringBootApplication:
 * - Enables component scanning from this package downward
 * - Enables auto-configuration of Spring components
 * - Combines @Configuration, @EnableAutoConfiguration, @ComponentScan
 * 
 * SecurityAutoConfiguration Exclusion:
 * - Disables Spring Security's default authentication/authorization filters
 * - Reason: Chem4AP implements custom LTI-based security instead
 * - LTI protocol provides education-specific identity and role management
 * - Custom servlets (Admin.java, etc.) implement role-based access control
 * - Exclusion avoids conflicts with LTI bearer token and JWT validation
 * 
 * Startup Process:
 * 1. JVM launches this main() method
 * 2. Spring Boot initializes application context
 * 3. Scans for @WebServlet annotated classes
 * 4. Registers discovered servlets with embedded Tomcat
 * 5. Tomcat listens on configured port (typically 8080 or 8443)
 * 6. Application ready to handle HTTP requests
 * 
 * Request Flow:
 * - Incoming HTTP request → Tomcat → Servlet URL mapping → @WebServlet class
 * - Each servlet (Exercises.java, QuestionManager.java, etc.) handles specific routes
 * - Objectify transactional boundary controls datastore operations
 * - LTI request validation occurs in LTIRequest or custom servlet filters
 * 
 * Deployment Targets:
 * - Google App Engine Standard with embedded Tomcat
 * - Traditional application servers (Tomcat, Jetty)
 * - Docker containers with Spring Boot executable JAR
 * - Cloud Run with containerized execution
 * 
 * Environment Configuration:
 * - application.properties or application.yml for Spring Boot settings
 * - Environment variables for cloud platform specific config (GCP, Azure, etc.)
 * - Java system properties passed via -D flags
 * - Datastore credentials managed by Google Cloud SDK
 * 
 * Related Classes:
 * - Individual servlet classes: Exercises.java, QuestionManager.java, Admin.java, etc.
 * - Objectify ORM for datastore persistence
 * - LTIRequest for protocol validation
 * - LTIMessage for token generation and verification
 * 
 * @author ChemVantage
 * @version 1.0
 * @see Exercises.java for servlet example
 * @see QuestionManager.java for complex servlet example
 * @see LTIRequest for LTI protocol handling
 */
@ServletComponentScan
@SpringBootApplication(exclude = { SecurityAutoConfiguration.class })
public class SpringbootMain {

	/**
	 * Main entry point for Spring Boot application.
	 * 
	 * Execution Flow:
	 * 1. JVM calls this method with command-line arguments
	 * 2. SpringApplication.run() initializes Spring context and starts Tomcat
	 * 3. Servlet component scanner discovers all @WebServlet annotated classes
	 * 4. Each discovered servlet is registered with Tomcat using its URL pattern
	 * 5. Embedded Tomcat begins listening on configured port
	 * 6. Application is ready to accept and process HTTP requests
	 * 
	 * Command-Line Arguments:
	 * - Passed through to Spring Boot configuration system
	 * - Examples: --server.port=8080, --spring.profiles.active=production
	 * - Can override application.properties or application.yml settings
	 * - Useful for environment-specific configurations
	 * 
	 * Startup Time:
	 * - Typically 5-15 seconds depending on datastore initialization
	 * - Datastore client libraries loaded lazily on first request if needed
	 * - Servlet discovery and registration adds minimal overhead
	 * 
	 * Error Handling:
	 * - If servlet discovery fails, exception is logged and startup halts
	 * - Missing dependencies or configuration errors prevent server start
	 * - Stack traces logged to console and application logs
	 * 
	 * Production Considerations:
	 * - Set appropriate heap size: -Xmx512m or -Xmx1024m for cloud environments
	 * - Configure logging levels via application.properties
	 * - Monitor startup time and memory usage metrics
	 * - Set application name and version for tracking
	 * 
	 * @param args Command-line arguments passed to Spring Boot configuration
	 * @see SpringApplication#run(Class, String[]) for default behavior
	 */
	public static void main(String[] args) {
		SpringApplication.run(SpringbootMain.class, args);
	}
	
}

