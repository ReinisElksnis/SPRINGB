package lv.ray.springb.interceptor;

import jakarta.persistence.*;
import lv.ray.springb.entity.Customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class CustomerEntityListener
{

	private static final Logger logger = LoggerFactory.getLogger(CustomerEntityListener.class);

	@PrePersist
	public void prePersist(final Customer customer)
	{
		logger.info("CustomerEntityListener: @PrePersist - Before saving new customer: {}", customer.getName());
		// Example: Set default values, validate data
		if (customer.getEmail() != null)
		{
			customer.setEmail(customer.getEmail().toLowerCase());
		}
	}

	@PostPersist
	public void postPersist(final Customer customer)
	{
		logger.info("CustomerEntityListener: @PostPersist - After saving new customer with ID: {}", customer.getId());
		// Example: Send notification, trigger events
	}

	@PreUpdate
	public void preUpdate(final Customer customer)
	{
		logger.info("CustomerEntityListener: @PreUpdate - Before updating customer ID: {}", customer.getId());
		// Example: Validate changes, audit logging
	}

	@PostUpdate
	public void postUpdate(final Customer customer)
	{
		logger.info("CustomerEntityListener: @PostUpdate - After updating customer ID: {}", customer.getId());
		// Example: Clear cache, send update notifications
	}

	@PreRemove
	public void preRemove(final Customer customer)
	{
		logger.info("CustomerEntityListener: @PreRemove - Before deleting customer ID: {}", customer.getId());
		// Example: Check dependencies, create backup
	}

	@PostRemove
	public void postRemove(final Customer customer)
	{
		logger.info("CustomerEntityListener: @PostRemove - After deleting customer ID: {}", customer.getId());
		// Example: Cleanup related data
	}

	@PostLoad
	public void postLoad(final Customer customer)
	{
		logger.info("CustomerEntityListener: @PostLoad - After loading customer ID: {}", customer.getId());
		// Example: Initialize transient fields, decrypt data
	}
}
