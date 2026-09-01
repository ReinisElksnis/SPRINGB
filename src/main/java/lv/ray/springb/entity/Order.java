package lv.ray.springb.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;


@Entity
@Table(name = "orders")
public class Order
{

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "customer_id", nullable = false)
	private Customer customer;

	@Column(nullable = false)
	private String product;

	@Column(nullable = false)
	private Double amount;

	@Column(nullable = false)
	private LocalDateTime orderDate;

	public Order()
	{
		this.orderDate = LocalDateTime.now();
	}

	public Order(Customer customer, String product, Double amount)
	{
		this.customer = customer;
		this.product = product;
		this.amount = amount;
		this.orderDate = LocalDateTime.now();
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

	public Customer getCustomer()
	{
		return customer;
	}

	public void setCustomer(Customer customer)
	{
		this.customer = customer;
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
