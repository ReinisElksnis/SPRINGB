package lv.ray.springb.controller;

import lv.ray.springb.constants.ApiConstants;
import lv.ray.springb.constants.ApiConstants.Endpoints;
import lv.ray.springb.constants.ApiConstants.SubPaths;
import lv.ray.springb.entity.Customer;
import lv.ray.springb.service.CustomerService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping(Endpoints.CUSTOMERS)
@CrossOrigin(origins = ApiConstants.ALL_ORIGINS)
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

	@GetMapping(SubPaths.BY_ID)
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

	@PutMapping(SubPaths.BY_ID)
	public ResponseEntity<Customer> updateCustomer(@PathVariable final Long id, @RequestBody final Customer customerDetails)
	{
		return customerService.updateCustomer(id, customerDetails)
				.map(ResponseEntity::ok)
				.orElse(ResponseEntity.notFound().build());
	}

	@DeleteMapping(SubPaths.BY_ID)
	public ResponseEntity<Void> deleteCustomer(@PathVariable final Long id)
	{
		customerService.deleteCustomer(id);
		return ResponseEntity.noContent().build();
	}
}
