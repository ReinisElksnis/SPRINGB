-- Mock data for customers and orders

-- Upsert customers (email is the natural key - re-running this script updates existing rows
-- instead of failing on the unique constraint)
INSERT INTO customers (name, email, phone, customer_type) VALUES
('John Doe', 'john.doe@example.com', '555-1234', 'B2C'),
('Jane Smith', 'jane.smith@example.com', '555-5678', 'B2B'),
('Bob Johnson', 'bob.johnson@example.com', '555-9012', 'LEGACY'),
('Alice Williams', 'alice.williams@example.com', '555-3456', NULL),
('Charlie Brown', 'charlie.brown@example.com', '555-7890', 'B2C')
ON CONFLICT (email) DO UPDATE SET
	name = EXCLUDED.name,
	phone = EXCLUDED.phone,
	customer_type = EXCLUDED.customer_type;

-- Insert orders
INSERT INTO orders (customer_id, product, amount, order_date) VALUES
(1, 'Laptop', 999.99, NOW()),
(1, 'Mouse', 29.99, NOW() - INTERVAL '1 day'),
(2, 'Keyboard', 79.99, NOW() - INTERVAL '2 days'),
(2, 'Monitor', 299.99, NOW() - INTERVAL '3 days'),
(3, 'Headphones', 149.99, NOW() - INTERVAL '4 days'),
(3, 'Webcam', 89.99, NOW() - INTERVAL '5 days'),
(4, 'USB Cable', 12.99, NOW() - INTERVAL '6 days'),
(4, 'External SSD', 199.99, NOW() - INTERVAL '7 days'),
(5, 'Desk Chair', 349.99, NOW() - INTERVAL '8 days'),
(5, 'Desk Lamp', 45.99, NOW() - INTERVAL '9 days'),
(1, 'Notebook', 15.99, NOW() - INTERVAL '10 days'),
(2, 'Pen Set', 24.99, NOW() - INTERVAL '11 days'),
(3, 'Coffee Mug', 9.99, NOW() - INTERVAL '12 days'),
(4, 'Water Bottle', 19.99, NOW() - INTERVAL '13 days'),
(5, 'Backpack', 59.99, NOW() - INTERVAL '14 days');
