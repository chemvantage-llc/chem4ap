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

@Component
public class WwwRedirectFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String host = req.getServerName();

        // Check if the host is the bare domain
        if ("chem4ap.com".equalsIgnoreCase(host)) {
            
            // Reconstruct the full URL with "www"
            StringBuffer newUrl = new StringBuffer("https://www.chem4ap.com");
            newUrl.append(req.getRequestURI());
            
            String queryString = req.getQueryString();
            if (queryString != null) {
                newUrl.append("?").append(queryString);
            }

            // Send a 301 Permanent Redirect
            res.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            res.setHeader("Location", newUrl.toString());
            return; // Stop processing the request
        }

        // Host is correct (or localhost), continue the filter chain
        chain.doFilter(request, response);
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // Initialization code, if any
    }

    @Override
    public void destroy() {
        // Cleanup code, if any
    }
}