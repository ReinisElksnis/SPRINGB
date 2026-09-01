package lv.ray.springb.service.impl;

import lv.ray.springb.entity.Customer;
import lv.ray.springb.repository.CustomerRepository;
import lv.ray.springb.service.CustomerService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;


@Service
@Transactional
public class CustomerServiceImpl implements CustomerService
{

	private final CustomerRepository customerRepository;

	public CustomerServiceImpl(final CustomerRepository customerRepository)
	{
		this.customerRepository = customerRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Customer> getAllCustomers()
	{
		return customerRepository.findAll();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Customer> getCustomerById(final Long id)
	{
		return customerRepository.findById(id);
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
					return customerRepository.save(customer);
				});
	}

	@Override
	public void deleteCustomer(final Long id)
	{
		customerRepository.deleteById(id);
	}
}
