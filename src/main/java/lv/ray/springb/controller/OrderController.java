package lv.ray.springb.controller;

import lv.ray.springb.dto.OrderDTO;
import lv.ray.springb.entity.Order;
import lv.ray.springb.service.OrderService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "*")
public class OrderController
{

	private final OrderService orderService;

	public OrderController(final OrderService orderService)
	{
		this.orderService = orderService;
	}

	@GetMapping
	public List<OrderDTO> getAllOrders()
	{
		return orderService.getAllOrders();
	}

	@GetMapping("/{id}")
	public ResponseEntity<OrderDTO> getOrderById(@PathVariable final Long id)
	{
		return orderService.getOrderById(id)
				.map(ResponseEntity::ok)
				.orElse(ResponseEntity.notFound().build());
	}

	@PostMapping
	public Order createOrder(@RequestBody final Order order)
	{
		return orderService.createOrder(order);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> deleteOrder(@PathVariable final Long id)
	{
		orderService.deleteOrder(id);
		return ResponseEntity.noContent().build();
	}
}
