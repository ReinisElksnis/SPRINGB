package lv.ray.springb.service;

import lv.ray.springb.dto.OrderDTO;
import lv.ray.springb.entity.Order;

import java.util.List;
import java.util.Optional;


public interface OrderService
{
	List<OrderDTO> getAllOrders();

	Optional<OrderDTO> getOrderById(Long id);

	Order createOrder(Order order);

	void deleteOrder(Long id);
}
