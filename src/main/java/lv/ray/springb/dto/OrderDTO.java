package lv.ray.springb.dto;

import lv.ray.springb.entity.Order;

import java.time.LocalDateTime;


public class OrderDTO
{
	private Long id;
	private Long customerId;
	private String customerName;
	private String product;
	private Double amount;
	private LocalDateTime orderDate;

	public OrderDTO()
	{
	}

	public OrderDTO(Order order)
	{
		this.id = order.getId();
		this.customerId = order.getCustomer().getId();
		this.customerName = order.getCustomer().getName();
		this.product = order.getProduct();
		this.amount = order.getAmount();
		this.orderDate = order.getOrderDate();
	}

	// Getters and Setters
	public Long getId()
	{
		return id;
	}

	public void setId(Long id)
	{
		this.id = id;
	}

	public Long getCustomerId()
	{
		return customerId;
	}

	public void setCustomerId(Long customerId)
	{
		this.customerId = customerId;
	}

	public String getCustomerName()
	{
		return customerName;
	}

	public void setCustomerName(String customerName)
	{
		this.customerName = customerName;
	}

	public String getProduct()
	{
		return product;
	}

	public void setProduct(String product)
	{
		this.product = product;
	}

	public Double getAmount()
	{
		return amount;
	}

	public void setAmount(Double amount)
	{
		this.amount = amount;
	}

	public LocalDateTime getOrderDate()
	{
		return orderDate;
	}

	public void setOrderDate(LocalDateTime orderDate)
	{
		this.orderDate = orderDate;
	}
}
