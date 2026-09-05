package lv.ray.springb.service.impl;

import lv.ray.springb.dto.CustomerDTO;
import lv.ray.springb.entity.Customer;
import lv.ray.springb.entity.CustomerType;
import lv.ray.springb.repository.CustomerRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceImplTest
{

	@Mock
	private CustomerRepository customerRepository;

	@Mock
	private MessageSource messageSource;

	private CustomerServiceImpl customerService;

	@BeforeEach
	void setUp()
	{
		customerService = new CustomerServiceImpl(customerRepository, messageSource);
	}

	private static Customer customer(final Long id, final CustomerType type)
	{
		final Customer customer = new Customer("Jane Doe", "jane@example.com", "555-1234");
		customer.setId(id);
		customer.setCustomerType(type);
		return customer;
	}

	@Test
	void getAllCustomers_resolvesLocalizedLabelForEachCustomerType()
	{
		when(customerRepository.findAll()).thenReturn(List.of(customer(1L, CustomerType.B2B)));
		when(messageSource.getMessage(eq("customer.type.B2B"), isNull(), any(Locale.class)))
				.thenReturn("Business to Business");

		final List<CustomerDTO> result = customerService.getAllCustomers();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getCustomerType()).isEqualTo(CustomerType.B2B);
		assertThat(result.get(0).getCustomerTypeLabel()).isEqualTo("Business to Business");
	}

	@Test
	void getAllCustomers_leavesLabelNullAndSkipsLookupWhenCustomerHasNoType()
	{
		when(customerRepository.findAll()).thenReturn(List.of(customer(2L, null)));

		final List<CustomerDTO> result = customerService.getAllCustomers();

		assertThat(result.get(0).getCustomerTypeLabel()).isNull();
		verify(messageSource, never()).getMessage(any(), any(), any(Locale.class));
	}

	@Test
	void getCustomerById_returnsDtoWhenFound()
	{
		when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L, null)));

		assertThat(customerService.getCustomerById(1L)).isPresent();
	}

	@Test
	void getCustomerById_returnsEmptyWhenNotFound()
	{
		when(customerRepository.findById(99L)).thenReturn(Optional.empty());

		assertThat(customerService.getCustomerById(99L)).isEmpty();
	}

	@Test
	void getCustomerByEmail_delegatesDirectlyToRepository()
	{
		final Customer customer = customer(1L, null);
		when(customerRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(customer));

		assertThat(customerService.getCustomerByEmail("jane@example.com")).contains(customer);
	}

	@Test
	void createCustomer_savesAndReturnsTheEntityUnchanged()
	{
		final Customer newCustomer = new Customer("New Guy", "new@example.com", null);
		when(customerRepository.save(newCustomer)).thenReturn(newCustomer);

		assertThat(customerService.createCustomer(newCustomer)).isSameAs(newCustomer);
	}

	@Test
	void updateCustomer_copiesEveryFieldIncludingCustomerTypeThenSaves()
	{
		final Customer existing = customer(1L, CustomerType.LEGACY);
		final Customer changes = new Customer("Renamed", "renamed@example.com", "555-9999");
		changes.setCustomerType(CustomerType.B2C);
		when(customerRepository.findById(1L)).thenReturn(Optional.of(existing));
		when(customerRepository.save(existing)).thenReturn(existing);

		final Optional<Customer> result = customerService.updateCustomer(1L, changes);

		assertThat(result).isPresent();
		assertThat(result.get().getName()).isEqualTo("Renamed");
		assertThat(result.get().getEmail()).isEqualTo("renamed@example.com");
		assertThat(result.get().getPhone()).isEqualTo("555-9999");
		assertThat(result.get().getCustomerType()).isEqualTo(CustomerType.B2C);
	}

	@Test
	void updateCustomer_returnsEmptyAndNeverSavesWhenNotFound()
	{
		when(customerRepository.findById(404L)).thenReturn(Optional.empty());

		assertThat(customerService.updateCustomer(404L, customer(null, null))).isEmpty();
		verify(customerRepository, never()).save(any());
	}

	@Test
	void deleteCustomer_delegatesDirectlyToRepository()
	{
		customerService.deleteCustomer(1L);

		verify(customerRepository).deleteById(1L);
	}
}
