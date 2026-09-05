package lv.ray.springb.service.impl;

import lv.ray.springb.dto.OrderDTO;
import lv.ray.springb.entity.Customer;
import lv.ray.springb.entity.Order;
import lv.ray.springb.repository.OrderRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest
{

	@Mock
	private OrderRepository orderRepository;

	private OrderServiceImpl orderService;

	@BeforeEach
	void setUp()
	{
		orderService = new OrderServiceImpl(orderRepository);
	}

	private static Order order(final Long id)
	{
		final Customer customer = new Customer("Jane Doe", "jane@example.com", null);
		customer.setId(1L);
		final Order order = new Order(customer, "Widget", 19.99);
		order.setId(id);
		return order;
	}

	@Test
	void getAllOrders_mapsEveryOrderToDto()
	{
		when(orderRepository.findAll()).thenReturn(List.of(order(1L), order(2L)));

		final List<OrderDTO> result = orderService.getAllOrders();

		assertThat(result).hasSize(2);
		assertThat(result.get(0).getCustomerName()).isEqualTo("Jane Doe");
		assertThat(result.get(0).getProduct()).isEqualTo("Widget");
	}

	@Test
	void getOrderById_returnsDtoWhenFound()
	{
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order(1L)));

		assertThat(orderService.getOrderById(1L)).isPresent();
	}

	@Test
	void getOrderById_returnsEmptyWhenNotFound()
	{
		when(orderRepository.findById(99L)).thenReturn(Optional.empty());

		assertThat(orderService.getOrderById(99L)).isEmpty();
	}

	@Test
	void createOrder_delegatesDirectlyToRepository()
	{
		final Order newOrder = order(null);
		when(orderRepository.save(newOrder)).thenReturn(newOrder);

		assertThat(orderService.createOrder(newOrder)).isSameAs(newOrder);
	}

	@Test
	void deleteOrder_delegatesDirectlyToRepository()
	{
		orderService.deleteOrder(1L);

		verify(orderRepository).deleteById(1L);
	}
}
