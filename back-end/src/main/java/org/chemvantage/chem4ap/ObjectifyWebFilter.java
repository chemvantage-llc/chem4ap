package org.chemvantage.chem4ap;

import com.googlecode.objectify.ObjectifyService;

import jakarta.servlet.annotation.WebFilter;

/**
 * Servlet filter that initializes Objectify ORM for each HTTP request.
 * 
 * ObjectifyService is a lightweight ORM (Object-Relational Mapping) wrapper around
 * Google Cloud Datastore that provides type-safe data persistence operations. This
 * filter extends ObjectifyService.Filter to automatically manage the Objectify request
 * lifecycle, ensuring that the datastore session is properly initialized for each
 * incoming HTTP request and cleaned up when the request completes.
 * 
 * This filter is applied to all URL patterns ("/*") and must be active for any servlet
 * to access Objectify datastore operations such as ofy().load(), ofy().save(), etc.
 * 
 * URL Pattern: /* (matches all HTTP requests)
 */
@WebFilter(urlPatterns = {"/*"})
public class ObjectifyWebFilter extends ObjectifyService.Filter {}