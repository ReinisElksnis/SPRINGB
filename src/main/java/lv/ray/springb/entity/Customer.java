package lv.ray.springb.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;
import lv.ray.springb.interceptor.CustomerEntityListener;

import java.util.ArrayList;
import java.util.List;


@Entity
@Table(name = "customers")
@EntityListeners(CustomerEntityListener.class)
public class Customer
{

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, unique = true)
	private String email;

	@Column
	private String phone;

	@OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
	@JsonIgnore
	private List<Order> orders = new ArrayList<>();

	public Customer()
	{
	}

	public Customer(String name, String email, String phone)
	{
		this.name = name;
		this.email = email;
		this.phone = phone;
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

	public String getName()
	{
		return name;
	}

	public void setName(String name)
	{
		this.name = name;
	}

	public String getEmail()
	{
		return email;
	}

	public void setEmail(String email)
	{
		this.email = email;
	}

	public String getPhone()
	{
		return phone;
	}

	public void setPhone(String phone)
	{
		this.phone = phone;
	}

	public List<Order> getOrders()
	{
		return orders;
	}

	public void setOrders(List<Order> orders)
	{
		this.orders = orders;
	}
}
