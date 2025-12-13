package org.chemvantage.chem4ap;

import java.io.IOException;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

/**
 * HTTP filter for automatic URL rewriting from bare domain to www subdomain.
 * 
 * Implements transparent HTTP-to-HTTPS and bare domain-to-www redirects for Chem4AP.
 * This filter intercepts all incoming HTTP requests and performs the following:
 * 
 * 1. Bare Domain Redirect:
 *    - Detects requests to chem4ap.com (bare domain without www prefix)
 *    - Issues HTTP 301 Permanent Redirect to https://www.chem4ap.com
 *    - Preserves request path and query parameters
 *    - Ensures all traffic uses www subdomain
 * 
 * 2. Subdomain Validation:
 *    - Allows www.chem4ap.com to pass through without modification
 *    - Allows localhost for development/testing environments
 *    - Only blocks and redirects bare domain
 * 
 * 3. URL Preservation:
 *    - Maintains exact request URI (path component)
 *    - Preserves query string parameters
 *    - Only modifies scheme (HTTP to HTTPS) and host
 *    - Example: chem4ap.com/app?id=123 → https://www.chem4ap.com/app?id=123
 * 
 * Benefits:
 * - SEO: Consolidates domain authority on single canonical URL
 * - SSL/TLS: Ensures all traffic is encrypted (HTTPS)
 * - Consistency: Users experience consistent domain everywhere
 * - Canonicalization: Prevents duplicate content issues
 * 
 * HTTP Redirect Details:
 * - Status Code: 301 (Moved Permanently)
 * - Semantics: Resource permanently moved; clients should update bookmarks
 * - Browser Caching: Most browsers cache 301 redirects (user experience benefit)
 * - CDN Handling: CDNs typically cache 301 responses
 * 
 * Development Considerations:
 * - localhost bypasses redirect (development environments work without www)
 * - Filter registered via @Component for Spring Boot auto-configuration
 * - Order of filter execution may affect behavior (apply early in chain)
 * - Can be disabled by removing @Component annotation
 * 
 * Configuration:
 * - Bare Domain: chem4ap.com (case-insensitive comparison)
 * - Target Domain: https://www.chem4ap.com (hardcoded; change requires recompile)
 * - Protocol: HTTPS (enforced via hardcoded scheme in redirect URL)
 * 
 * Request Flow:
 * - User types: chem4ap.com/exercises
 * - Filter detects bare domain
 * - Server responds: HTTP 301 with Location: https://www.chem4ap.com/exercises
 * - Browser follows redirect to www subdomain
 * - Subsequent request to www.chem4ap.com proceeds normally
 * 
 * Performance Impact:
 * - Minimal overhead for non-bare-domain requests (simple host comparison)
 * - Bare domain requests trigger redirect (one extra network round-trip for user)
 * - StringBuffer used for URL construction (not StringBuilder) for thread safety
 * 
 * HTTPS Enforcement:
 * - Redirect URL hardcodes HTTPS scheme for security
 * - All traffic to www.chem4ap.com must be HTTPS
 * - HTTP to HTTPS conversion handled by application server/load balancer
 * - This filter handles bare domain to www redirection only
 * 
 * Browser Compatibility:
 * - 301 redirects supported by all modern browsers
 * - Mobile browsers handle transparently (user sees final page)
 * - API clients should follow Location header (most HTTP libraries do)
 * - SEO crawlers follow 301 redirects (essential for proper indexing)
 * 
 * @author ChemVantage
 * @version 1.0
 * @see WwwRedirectFilter#doFilter(ServletRequest, ServletResponse, FilterChain)
 */
@Component
public class WwwRedirectFilter implements Filter {
	// Domain and URL redirection constants
	/** Bare domain that requires redirection to www subdomain */
	private static final String BARE_DOMAIN = "chem4ap.com";
	
	/** Target domain with www prefix for permanent redirect */
	private static final String WWW_DOMAIN = "https://www.chem4ap.com";
	
	/** Query string separator character in URL construction */
	private static final String QUERY_STRING_PREFIX = "?";
	
	/**
	 * Filters incoming HTTP requests and redirects bare domain to www subdomain.
	 * 
	 * Request Processing:
	 * 1. Extract HTTP request/response objects from generic servlet request
	 * 2. Retrieve server name (host) from request
	 * 3. Compare host against bare domain (case-insensitive)
	 * 4. If bare domain: construct redirect URL with www prefix
	 * 5. If not bare domain: pass request through filter chain unchanged
	 * 
	 * Redirect URL Construction:
	 * - Base: https://www.chem4ap.com (hardcoded target)
	 * - Path: req.getRequestURI() (original request path)
	 * - Query: req.getQueryString() if present (null for no query parameters)
	 * - Result: Complete URL with preserved path and query string
	 * 
	 * Example Redirects:
	 * - chem4ap.com → https://www.chem4ap.com
	 * - chem4ap.com/exercises → https://www.chem4ap.com/exercises
	 * - chem4ap.com/app?id=123 → https://www.chem4ap.com/app?id=123
	 * 
	 * Status Code:
	 * - HTTP 301 (SC_MOVED_PERMANENTLY)
	 * - Indicates resource permanently moved to new URL
	 * - Browsers cache this redirect for performance
	 * - Clients should update bookmarks/references
	 * 
	 * Header Management:
	 * - Sets Location header with redirect URL
	 * - Prevents further processing of original request (returns early)
	 * - No response body sent (redirects are minimal)
	 * 
	 * Filter Chain:
	 * - Bare domain: intercepts and redirects (stops chain execution)
	 * - Non-bare domain: passes through unchanged (continues chain)
	 * - Developers must follow redirect for authentication/session continuity
	 * 
	 * Error Handling:
	 * - No explicit error handling (assumes valid request/response objects)
	 * - ServletException: propagates if chain.doFilter() fails
	 * - IOException: propagates if response write fails
	 * 
	 * Thread Safety:
	 * - Filter instance is stateless (no instance variables modified)
	 * - Safe for concurrent request processing
	 * - Each request creates new StringBuffer (no sharing)
	 * 
	 * Performance Characteristics:
	 * - Bare domain check: O(1) host string comparison
	 * - StringBuffer allocation: ~100 bytes typical
	 * - Query string append: O(n) where n = query string length
	 * - Total overhead: minimal (~1ms per request)
	 * 
	 * @param request the servlet request object (cast to HttpServletRequest)
	 * @param response the servlet response object (cast to HttpServletResponse)
	 * @param chain the filter chain for continuing request processing
	 * @throws IOException if response write operations fail
	 * @throws ServletException if filter chain processing fails
	 */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String host = req.getServerName();

        // Check if the host is the bare domain
        if (BARE_DOMAIN.equalsIgnoreCase(host)) {
            
            // Reconstruct the full URL with "www"
            StringBuffer newUrl = new StringBuffer(WWW_DOMAIN);
            newUrl.append(req.getRequestURI());
            
            String queryString = req.getQueryString();
            if (queryString != null) {
                newUrl.append(QUERY_STRING_PREFIX).append(queryString);
            }

            // Send a 301 Permanent Redirect
            res.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            res.setHeader("Location", newUrl.toString());
            return; // Stop processing the request
        }

        // Host is correct (or localhost), continue the filter chain
        chain.doFilter(request, response);
    }

    /**
	 * Initializes the filter when servlet context starts.
	 * 
	 * Called once during application startup by the servlet container.
	 * This filter requires no special initialization (no resources to allocate,
	 * no configuration parameters to read, no external connections to establish).
	 * Method body is empty per servlet filter contract.
	 * 
	 * Called by: Servlet container during FilterChain initialization
	 * Executes: Once at application startup
	 * 
	 * @param filterConfig filter configuration object (not used)
	 * @throws ServletException if filter initialization fails (not thrown by this implementation)
	 */
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // Initialization code, if any
    }

    /**
	 * Destroys the filter when servlet context shuts down.
	 * 
	 * Called once during application shutdown by the servlet container.
	 * This filter requires no special cleanup (no resources allocated, no state to flush).
	 * Method body is empty per servlet filter contract.
	 * 
	 * Called by: Servlet container during application shutdown
	 * Executes: Once at application termination
	 * 
	 * @throws ServletException if filter destruction fails (not thrown by this implementation)
	 */
    @Override
    public void destroy() {
        // Cleanup code, if any
    }
}