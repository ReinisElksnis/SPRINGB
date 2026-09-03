package lv.ray.springb.service;

import lv.ray.springb.dto.CustomerDTO;
import lv.ray.springb.entity.Customer;

import java.util.List;
import java.util.Optional;


public interface CustomerService
{
	List<CustomerDTO> getAllCustomers();

	Optional<CustomerDTO> getCustomerById(Long id);

	Optional<Customer> getCustomerByEmail(String email);

	Customer createCustomer(Customer customer);

	Optional<Customer> updateCustomer(Long id, Customer customerDetails);

	void deleteCustomer(Long id);
}
