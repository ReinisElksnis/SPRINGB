package lv.ray.springb.controller;

import lv.ray.springb.entity.Customer;
import lv.ray.springb.service.CustomerService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/api/customers")
@CrossOrigin(origins = "*")
public class CustomerController
{

	private final CustomerService customerService;

	public CustomerController(final CustomerService customerService)
	{
		this.customerService = customerService;
	}

	@GetMapping
	public List<Customer> getAllCustomers()
	{
		return customerService.getAllCustomers();
	}

	@GetMapping("/{id}")
	public ResponseEntity<Customer> getCustomerById(@PathVariable final Long id)
	{
		return customerService.getCustomerById(id)
				.map(ResponseEntity::ok)
				.orElse(ResponseEntity.notFound().build());
	}

	@PostMapping
	public Customer createCustomer(@RequestBody final Customer customer)
	{
		return customerService.createCustomer(customer);
	}

	@PutMapping("/{id}")
	public ResponseEntity<Customer> updateCustomer(@PathVariable final Long id, @RequestBody final Customer customerDetails)
	{
		return customerService.updateCustomer(id, customerDetails)
				.map(ResponseEntity::ok)
				.orElse(ResponseEntity.notFound().build());
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> deleteCustomer(@PathVariable final Long id)
	{
		customerService.deleteCustomer(id);
		return ResponseEntity.noContent().build();
	}
}
