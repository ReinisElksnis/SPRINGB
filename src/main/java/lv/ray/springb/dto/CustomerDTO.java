package lv.ray.springb.dto;

import lv.ray.springb.entity.Customer;
import lv.ray.springb.entity.CustomerType;


public class CustomerDTO
{
	private Long id;
	private String name;
	private String email;
	private String phone;
	private CustomerType customerType;
	private String customerTypeLabel;

	public CustomerDTO()
	{
	}

	/**
	 * @param customerTypeLabel the display name for {@code customer.getCustomerType()}, resolved
	 *                          against the caller's locale ({@code null} if the customer has no type)
	 */
	public CustomerDTO(final Customer customer, final String customerTypeLabel)
	{
		this.id = customer.getId();
		this.name = customer.getName();
		this.email = customer.getEmail();
		this.phone = customer.getPhone();
		this.customerType = customer.getCustomerType();
		this.customerTypeLabel = customerTypeLabel;
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

	public CustomerType getCustomerType()
	{
		return customerType;
	}

	public void setCustomerType(CustomerType customerType)
	{
		this.customerType = customerType;
	}

	public String getCustomerTypeLabel()
	{
		return customerTypeLabel;
	}

	public void setCustomerTypeLabel(String customerTypeLabel)
	{
		this.customerTypeLabel = customerTypeLabel;
	}
}
