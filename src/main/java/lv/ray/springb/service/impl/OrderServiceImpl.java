package lv.ray.springb.service.impl;

import lv.ray.springb.dto.OrderDTO;
import lv.ray.springb.entity.Order;
import lv.ray.springb.repository.OrderRepository;
import lv.ray.springb.service.OrderService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;


@Service
@Transactional
public class OrderServiceImpl implements OrderService
{

	private final OrderRepository orderRepository;

	public OrderServiceImpl(final OrderRepository orderRepository)
	{
		this.orderRepository = orderRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public List<OrderDTO> getAllOrders()
	{
		return orderRepository.findAll().stream()
				.map(OrderDTO::new)
				.collect(Collectors.toList());
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<OrderDTO> getOrderById(final Long id)
	{
		return orderRepository.findById(id)
				.map(OrderDTO::new);
	}

	@Override
	public Order createOrder(final Order order)
	{
		return orderRepository.save(order);
	}

	@Override
	public void deleteOrder(final Long id)
	{
		orderRepository.deleteById(id);
	}
}
