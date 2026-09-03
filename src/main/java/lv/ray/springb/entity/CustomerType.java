package lv.ray.springb.entity;

/**
 * Segments a {@link Customer} for reporting and display purposes. {@code null} is a valid value -
 * it means the customer has not been classified.
 */
public enum CustomerType
{
	B2C,
	B2B,
	LEGACY;

	/** Key of this value's display name in the {@code messages*.properties} bundles. */
	public String messageKey()
	{
		return "customer.type." + name();
	}
}
