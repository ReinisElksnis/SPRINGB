package lv.ray.springb.service.impl;

import lv.ray.springb.dto.CustomerDTO;
import lv.ray.springb.entity.Customer;
import lv.ray.springb.repository.CustomerRepository;
import lv.ray.springb.service.CustomerService;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;


@Service
@Transactional
public class CustomerServiceImpl implements CustomerService
{

	private final CustomerRepository customerRepository;

	private final MessageSource messageSource;

	public CustomerServiceImpl(final CustomerRepository customerRepository, final MessageSource messageSource)
	{
		this.customerRepository = customerRepository;
		this.messageSource = messageSource;
	}

	@Override
	@Transactional(readOnly = true)
	public List<CustomerDTO> getAllCustomers()
	{
		return customerRepository.findAll().stream()
				.map(this::toDto)
				.collect(Collectors.toList());
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CustomerDTO> getCustomerById(final Long id)
	{
		return customerRepository.findById(id)
				.map(this::toDto);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Customer> getCustomerByEmail(final String email)
	{
		return customerRepository.findByEmail(email);
	}

	@Override
	public Customer createCustomer(final Customer customer)
	{
		return customerRepository.save(customer);
	}

	@Override
	public Optional<Customer> updateCustomer(final Long id, final Customer customerDetails)
	{
		return customerRepository.findById(id)
				.map(customer ->
				{
					customer.setName(customerDetails.getName());
					customer.setEmail(customerDetails.getEmail());
					customer.setPhone(customerDetails.getPhone());
					customer.setCustomerType(customerDetails.getCustomerType());
					return customerRepository.save(customer);
				});
	}

	@Override
	public void deleteCustomer(final Long id)
	{
		customerRepository.deleteById(id);
	}

	private CustomerDTO toDto(final Customer customer)
	{
		final String customerTypeLabel = customer.getCustomerType() == null
				? null
				: messageSource.getMessage(customer.getCustomerType().messageKey(), null, LocaleContextHolder.getLocale());
		return new CustomerDTO(customer, customerTypeLabel);
	}
}
