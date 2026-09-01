package lv.ray.springb.controller;

import lv.ray.springb.constants.ApiConstants;
import lv.ray.springb.constants.ApiConstants.Endpoints;
import lv.ray.springb.constants.ApiConstants.SubPaths;
import lv.ray.springb.dto.OrderDTO;
import lv.ray.springb.entity.Order;
import lv.ray.springb.service.OrderService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping(Endpoints.ORDERS)
@CrossOrigin(origins = ApiConstants.ALL_ORIGINS)
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

	@GetMapping(SubPaths.BY_ID)
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

	@DeleteMapping(SubPaths.BY_ID)
	public ResponseEntity<Void> deleteOrder(@PathVariable final Long id)
	{
		orderService.deleteOrder(id);
		return ResponseEntity.noContent().build();
	}
}
