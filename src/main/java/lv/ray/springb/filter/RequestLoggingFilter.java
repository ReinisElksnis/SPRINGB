package lv.ray.springb.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;


@Component
public class RequestLoggingFilter implements Filter
{

	private static final Logger logger = LoggerFactory.getLogger(RequestLoggingFilter.class);

	@Override
	public void doFilter(final ServletRequest request,
			final ServletResponse response,
			final FilterChain chain) throws IOException, ServletException
	{

		final HttpServletRequest httpRequest = (HttpServletRequest) request;
		final HttpServletResponse httpResponse = (HttpServletResponse) response;

		final long startTime = System.currentTimeMillis();

		logger.info("Filter: Incoming request - Method: {}, URI: {}, Remote Address: {}",
				httpRequest.getMethod(),
				httpRequest.getRequestURI(),
				httpRequest.getRemoteAddr());

		try
		{
			chain.doFilter(request, response);
		}
		finally
		{
			final long duration = System.currentTimeMillis() - startTime;
			logger.info("Filter: Completed request - Method: {}, URI: {}, Status: {}, Duration: {}ms",
					httpRequest.getMethod(),
					httpRequest.getRequestURI(),
					httpResponse.getStatus(),
					duration);
		}
	}
}
